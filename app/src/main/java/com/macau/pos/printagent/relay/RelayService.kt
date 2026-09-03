package com.macau.pos.printagent.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.macau.pos.printagent.BuildConfig
import com.macau.pos.printagent.CrashLog
import com.macau.pos.printagent.R
import com.macau.pos.printagent.net.SdkPrinter
import com.macau.pos.printagent.net.SunmiPrinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 雲端列印中繼常駐服務（docs/96 §7、§10）。
 *
 * 用途：iPad（或任何跑 web POS 嘅裝置）冇辦法直接掂 LAN 打印機（HTTPS 頁面打
 * HTTP/9100 會被 mixed content 擋死），所以由呢部 Android 機做橋：
 *
 * ```
 * iPad web POS ──(HTTPS)──> Supabase pos_print_jobs ──(WSS wake-up)──> RelayService
 *                                    ▲                                      │
 *                                    └──── POST /result ◀─── render ────────┤
 *                                                                           ▼
 *                                                    Sunmi 內置打印機 / LAN / USB / BT
 * ```
 *
 * ## 可靠性
 * - **Realtime 淨係 wake-up**：>1MB payload 會被 Supabase 截斷，所以收到訊號後
 *   一定要 `POST /claim` 由 DB 攞全行（見 RealtimeClient class doc）。
 * - **30s 對帳 tick**：Realtime 斷線／漏 event 都唔會漏單。
 * - **WifiLock**：鎖屏後 Wi-Fi 唔好瞓着（Android 會為咗省電切斷背景網絡）。
 * - **START_STICKY**：被系統殺咗會自動重開。
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: RelayPrefs
    private lateinit var api: RelayApi

    private var realtime: RealtimeClient? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastDrainAt = 0L
    private var lastHeartbeatAt = 0L
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        // 成個 onCreate 包住：任何非預期錯誤（例如 startForeground 掟錯、
        // 普通手機初始化擲 Throwable）都唔可以令系統 kill 成個 app 程序。
        // 失敗就 stopSelf()，等 MainActivity 嘅 POS WebView 照常開到。
        try {
            super.onCreate()
            prefs = RelayPrefs(this)
            api = RelayApi()
            // 普通手機（非 Sunmi）上 net.posprinter（POSConnect.init）／Sunmi AIDL 初始化
            // 有可能失敗。必須吞底，唔可以令個 relay service（同埋成個 app 程序）彈出。
            // 印嘢嗰陣 SdkPrinter.print / SunmiPrinter.sendRaw 本身已經有 Result 處理，
            // 初始化失敗只會令「暫時印唔到」，唔會 crash。
            runCatching { SdkPrinter.initOnce(this) }
            runCatching { SunmiPrinter.ensureBound(this) }
            acquireWifiLock()
            runCatching { startAsForeground(getString(R.string.relay_running)) }
                .onFailure {
                    // Android 14+：startForegroundService 之後如果冇成功入 foreground，
                    // 系統會 RemoteServiceException kill 成個 app。所以失敗就 stopSelf 收尾。
                    Log.e(TAG, "startForeground 失敗，停止 RelayService 避免被系統 kill 成個 app", it)
                    stopSelf()
                    return
                }
            RelayState.phase = if (prefs.isPaired()) "connecting" else "pairing"
            scope.launch { loop() }
        } catch (e: Throwable) {
            Log.e(TAG, "RelayService.onCreate 失敗，停止服務避免 crash", e)
            CrashLog.write(this, "RelayService.onCreate", e)
            runCatching { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        realtime?.stop()
        realtime = null
        scope.cancel()
        runCatching { wifiLock?.release() }
        wifiLock = null
        RelayState.phase = "idle"
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                Log.w(TAG, "loop 例外", e)
                RelayState.note("迴圈例外：${e.message}")
            }
            delay(TICK_MS)
        }
    }

    private suspend fun tick() {
        // Sunmi 內置打印機狀態（每次 loop 刷新，UI 同心跳都用）
        RelayState.sunmiReady = SunmiPrinter.isReady()
        RelayState.sunmiModal = SunmiPrinter.printerModal()

        if (!prefs.isPaired()) {
            // ---- 配對階段：等 iPad 掃碼 ----
            realtime?.stop(); realtime = null
            RelayState.phase = "pairing"
            val poll = api.pollPair(BuildConfig.POS_URL, prefs.ensureAgentId())
            if (poll.status == "paired" && !poll.storeId.isNullOrBlank()) {
                prefs.storeId = poll.storeId
                prefs.storeName = poll.storeName
                prefs.supabaseUrl = poll.supabaseUrl
                prefs.anonKey = poll.anonKey
                prefs.pairedAt = System.currentTimeMillis()
                RelayState.phase = "connecting"
                RelayState.note("已配對：${poll.storeName ?: poll.storeId}")
                lastDrainAt = 0L // 強制立即 drain 一次
            } else if (poll.error != null) {
                RelayState.note("配對輪詢失敗：${poll.error}")
            }
            updateNotification()
            return
        }

        // ---- 已配對：Realtime + drain + heartbeat ----
        startRealtimeIfNeeded()

        val now = System.currentTimeMillis()
        val wokeUp = RelayState.lastWakeAt > lastDrainAt
        val stale = now - lastDrainAt > RECONCILE_MS
        if (wokeUp || stale) {
            drainNow()
            lastDrainAt = System.currentTimeMillis()
        }

        if (now - lastHeartbeatAt > HEARTBEAT_MS) {
            heartbeat()
            lastHeartbeatAt = now
        }

        RelayState.phase = if (RelayState.realtimeConnected) "online" else "offline"
        updateNotification()
    }

    private fun startRealtimeIfNeeded() {
        if (realtime != null) return
        val url = prefs.supabaseUrl
        val key = prefs.anonKey
        val storeId = prefs.storeId
        if (url.isNullOrBlank() || key.isNullOrBlank() || storeId.isNullOrBlank()) {
            RelayState.note("欠 supabaseUrl / anonKey，用 30s 輪詢兜底")
            return
        }
        realtime = RealtimeClient(
            supabaseUrl = url,
            anonKey = key,
            storeId = storeId,
            onWake = { RelayState.lastWakeAt = System.currentTimeMillis() },
            onStatus = { connected, detail ->
                RelayState.realtimeConnected = connected
                detail?.let { RelayState.note(it) }
            },
        ).also { it.start() }
    }

    private suspend fun drainNow() {
        val r = JobRunner.drain(this, prefs, api, BuildConfig.POS_URL)
        when {
            r.error != null -> RelayState.note("認領失敗：${r.error}")
            r.claimed > 0 -> RelayState.note("處理 ${r.claimed} 張（成功 ${r.sent}／失敗 ${r.failed}）")
        }
    }

    private fun heartbeat() {
        val info = JSONObject()
            .put("sunmiReady", SunmiPrinter.isReady())
            .put("sunmiModal", SunmiPrinter.printerModal() ?: JSONObject.NULL)
            .put("printedCount", RelayState.printedCount)
            .put("failedCount", RelayState.failedCount)
            .put("realtimeConnected", RelayState.realtimeConnected)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidSdk", Build.VERSION.SDK_INT)
        val resp = api.heartbeat(
            BuildConfig.POS_URL,
            prefs.agentId ?: return,
            prefs.agentToken ?: return,
            prefs.storeId,
            info,
        )
        if (resp != null) RelayState.lastHeartbeatAt = System.currentTimeMillis()
    }

    /**
     * Wi-Fi 鎖：鎖屏後 Android 會為咗省電閂背景網絡，Realtime 會斷。
     * `WIFI_MODE_FULL_HIGH_PERF` 係官方建議畀「需要持續低延遲網絡」嘅長期連線。
     */
    private fun acquireWifiLock() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                wifiLock = if (Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
                } else {
                    @Suppress("DEPRECATION")
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG)
                }.apply { acquire() }
            }
        }
    }

    private fun updateNotification() {
        val base = when (RelayState.phase) {
            "pairing" -> "等待配對：請喺 web POS「設置 → 打印機」輸入店舗 ID 配對"
            "connecting" -> "連線中…"
            "online" -> "已連線 · 已印 ${RelayState.printedCount} 張" +
                (if (RelayState.failedCount > 0) " · 失敗 ${RelayState.failedCount}" else "")
            "offline" -> "雲端斷線，用 30s 輪詢兜底 · 已印 ${RelayState.printedCount} 張"
            else -> "待機"
        }
        // 中繼專用機多數係 headless 行（鎖屏擺喺角落），通知係唯一會俾人睇到嘅嘢。
        // 淨係寫「失敗 N 張」等於冇講 —— 一定要帶埋原因落去。
        val text = if (RelayState.failedCount > 0 && RelayState.lastPrintError.isNotBlank()) {
            "$base\n⚠ ${RelayState.lastPrintError}"
        } else {
            base
        }
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.relay_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, RelayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.relay_running))
            .setContentText(text)
            // BigTextStyle：失敗原因可以好長（「兩種通道都失敗｜直連：…｜SDK：…」），
            // 冇咗呢個，摺埋嘅通知會cut 到只剩頭幾個字，等於冇講。
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "pos_print_relay"
        private const val NOTIF_ID = 8788

        /** 主 tick：1s。唔係 polling —— 淨係用嚟判定「有冇 wake-up / 到唔到對帳時間」。 */
        private const val TICK_MS = 1_000L
        /** 對帳：Realtime 斷線／漏 event 時嘅兜底 drain 間隔。 */
        private const val RECONCILE_MS = 30_000L
        private const val HEARTBEAT_MS = 60_000L

        fun start(context: Context) {
            val i = Intent(context, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

/** Doze 提示用：判斷係咪已經入白名單（冇入就 UI 提示店員）。 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}
