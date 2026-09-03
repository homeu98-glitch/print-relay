package com.macau.pos.printagent.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime（Phoenix channel over WebSocket）客戶端 —— docs/96 §7。
 *
 * ## 定位：**淨係 wake-up（叫醒）訊號，唔係資料來源**
 *
 * Supabase `postgres_changes` 有 **1,024 KB payload 上限**；超咗嘅話 `new`/`old`
 * 只會剩低 ≤ 64 bytes 嘅欄位 —— 即係 `template` / `items` / `content` 會被**靜默剷走**，
 * 中繼機收到一個「有 id、冇內容」嘅空 event。所以：
 *
 *  - event 觸發 → 叫 RelayService 去 call `POST /claim` 由 DB 攞 authoritative 全行；
 *  - Realtime 斷線／漏 event → 30s 對帳 tick 兜底。
 *
 * 呢個設計同時解決三件事：payload 截斷、event 遺失、多機爭奪（claim 用 skip locked）。
 *
 * ## Phoenix 協定（vsn 1.0.0）
 * - URL：`wss://<ref>.supabase.co/realtime/v1/websocket?apikey=<anon>&vsn=1.0.0`
 * - join：`{topic, event:"phx_join", payload:{config:{postgres_changes:[...]}}, ref, join_ref}`
 * - 心跳：**必須** app 層自己做，每 25s `{"topic":"phoenix","event":"heartbeat"}`；
 *   OkHttp 嘅 `pingInterval` 只係 TCP/WebSocket protocol ping，Phoenix server 唔認。
 */
class RealtimeClient(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val storeId: String,
    private val onWake: () -> Unit,
    private val onStatus: (connected: Boolean, detail: String?) -> Unit,
) {

    private val topic = "realtime:public:pos_print_jobs"

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refSeq = AtomicInteger(0)

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastInboundAt = 0L

    @Volatile
    var connected: Boolean = false
        private set

    private var heartbeatJob: Job? = null
    private var retryDelayMs = RETRY_MIN_MS

    fun start() {
        if (running) return
        running = true
        scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            ws?.close(1000, "stop")
        } catch (_: Exception) {
        }
        ws = null
        setConnected(false, null)
    }

    private suspend fun connectLoop() {
        while (scope.isActive && running) {
            open()
            // 等連線真正建立（onOpen 會置 connected=true）或超時
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (scope.isActive && running && !connected && System.currentTimeMillis() < deadline) {
                delay(250)
            }
            if (!running) break
            if (connected) {
                retryDelayMs = RETRY_MIN_MS
                // 連住之後一直等，直到斷線／watchdog 踢
                while (scope.isActive && running && connected) delay(1000)
            } else {
                setConnected(false, "連線失敗，${retryDelayMs / 1000}s 後重試")
            }
            if (!running) break
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    private fun open() {
        val base = supabaseUrl.trim().trimEnd('/')
        val wsUrl = (when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }) + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastInboundAt = System.currentTimeMillis()
                setConnected(false, "已連線，訂閱中…")
                sendJoin(webSocket)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundAt = System.currentTimeMillis()
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setConnected(false, "Realtime 錯誤：${t.message}")
                try {
                    webSocket.cancel()
                } catch (_: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setConnected(false, "Realtime 已關閉（$code $reason）")
            }
        })
    }

    private fun handle(raw: String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val event = o.optString("event")
        when (event) {
            "phx_reply" -> {
                val status = o.optJSONObject("payload")?.optString("status")
                if (status == "ok") {
                    setConnected(true, "已訂閱 $topic")
                } else {
                    setConnected(false, "訂閱被拒：${o.optJSONObject("payload")?.optString("response")}")
                }
            }

            "phx_error", "phx_close" -> setConnected(false, "channel 錯誤（$event）")

            "heartbeat" -> Unit // server 回應，唔使做嘢

            else -> {
                // 任何喺我哋 topic 上面嘅其它 event（insert / update / *）都當叫醒訊號。
                // 刻意唔解析 payload：postgres_changes 嘅 payload 形狀會變，
                // 而且 >1MB 時會被截斷，解析佢只會引入 bug（見 class doc）。
                if (o.optString("topic") == topic) onWake()
            }
        }
    }

    private fun sendJoin(webSocket: WebSocket) {
        val ref = refSeq.incrementAndGet().toString()
        val filter = "store_id=eq.$storeId"
        val changes = org.json.JSONArray().put(
            JSONObject()
                .put("event", "INSERT")
                .put("schema", "public")
                .put("table", "pos_print_jobs")
                .put("filter", filter)
        )
        val payload = JSONObject()
            .put(
                "config",
                JSONObject().put("postgres_changes", changes)
            )
        val msg = JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", ref)
        runCatching { webSocket.send(msg.toString()) }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running) {
                delay(HEARTBEAT_MS)
                if (!running) break
                // Watchdog：超過 70s 完全冇 inbound → 當死連，踢去重連
                if (System.currentTimeMillis() - lastInboundAt > WATCHDOG_MS) {
                    setConnected(false, "watchdog：${WATCHDOG_MS / 1000}s 無收訊，重連")
                    try {
                        webSocket.cancel()
                    } catch (_: Exception) {
                    }
                    break
                }
                val msg = JSONObject()
                    .put("topic", "phoenix")
                    .put("event", "heartbeat")
                    .put("payload", JSONObject())
                    .put("ref", refSeq.incrementAndGet().toString())
                runCatching { webSocket.send(msg.toString()) }
            }
        }
    }

    private fun setConnected(v: Boolean, detail: String?) {
        connected = v
        onStatus(v, detail)
    }

    companion object {
        private const val HEARTBEAT_MS = 25_000L
        private const val WATCHDOG_MS = 70_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
    }
}
