package com.macau.printhub.relay

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
import com.macau.printhub.BuildConfig
import com.macau.printhub.CrashLog
import com.macau.printhub.R
import com.macau.printhub.model.LogEntry
import com.macau.printhub.model.PrintJobDto
import com.macau.printhub.model.PrinterCfgDto
import com.macau.printhub.net.EscPosRenderer
import com.macau.printhub.net.SdkPrinter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hub foreground service:
 *  1. Realtime wake-up → claim loop (cloud relay, Scheme B)
 *  2. Local HTTP endpoint (NanoHTTPD on port 8787) for direct LAN calls from macau-pos
 *  3. Notification + WifiLock + START_STICKY for reliability
 *
 * Architecture (Scheme B):
 *   iPad (HTTPS/Vercel) → Supabase Realtime (WSS) → HubService (LAN) → LAN printer (:9100)
 *   Also accepts direct HTTP POST from LAN devices: POST http://hub-ip:8787/print
 */
class HubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: RelayPrefs
    private lateinit var api: RelayApi

    private var realtime: RealtimeClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var httpServer: HubHttpServer? = null

    private var lastDrainAt = 0L
    private var lastHeartbeatAt = 0L
    private var lastConfigFetchAt = 0L
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        try {
            super.onCreate()
            prefs = RelayPrefs(this)
            api = RelayApi()
            runCatching { SdkPrinter.initOnce(this) }
            acquireWifiLock()
            runCatching { startAsForeground(getString(R.string.relay_running)) }
                .onFailure {
                    Log.e(TAG, "startForeground 失敗", it)
                    stopSelf()
                    return
                }
            startHttpServer()
            RelayState.phase = if (prefs.isPaired()) "connecting" else "pairing"
            scope.launch { loop() }
        } catch (e: Throwable) {
            Log.e(TAG, "HubService.onCreate 失敗", e)
            CrashLog.write(this, "HubService.onCreate", e)
            runCatching { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        realtime?.stop()
        realtime = null
        runCatching { httpServer?.stop() }
        httpServer = null
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
        if (!prefs.isPaired()) {
            realtime?.stop(); realtime = null
            RelayState.phase = "pairing"
            val poll = api.pollPair(BuildConfig.POS_URL, prefs.ensureAgentId())
            if (poll.status == "paired" && !poll.storeId.isNullOrBlank()) {
                prefs.storeId = poll.storeId
                // pos_print_agents 冇 store_name 欄 → poll.storeName 永遠 null。
                // 淨喺真係有值嗰陣先覆寫，保住 login 嗰陣存低嘅店名。
                if (!poll.storeName.isNullOrBlank()) prefs.storeName = poll.storeName
                prefs.supabaseUrl = poll.supabaseUrl
                prefs.anonKey = poll.anonKey
                prefs.pairedAt = System.currentTimeMillis()
                RelayState.phase = "connecting"
                RelayState.note("已配對：${poll.storeName ?: poll.storeId}")
                lastDrainAt = 0L
            } else if (poll.error != null) {
                RelayState.note("配對輪詢失敗：${poll.error}")
            }
            updateNotification()
            return
        }

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

        // 問題二（docs/98）：定時同步 web POS 嘅打印機路由配置，令 Hub UI 睇到
        // 「邊部機負責印咩內容」。60s 一次；用子 coroutine，唔阻塞 tick。
        if (now - lastConfigFetchAt > CONFIG_FETCH_MS) {
            lastConfigFetchAt = now
            fetchDeviceConfig()
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
            .put("printedCount", RelayState.printedCount)
            .put("failedCount", RelayState.failedCount)
            .put("realtimeConnected", RelayState.realtimeConnected)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("hubMode", true)
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
     * 拉 web POS 嘅打印機路由配置（問題二：Hub 睇唔到「邊部機負責咩」）。
     * 子 coroutine 執行網絡，完成先寫 RelayState（null = 失敗，保留舊值唔覆寫）。
     */
    private fun fetchDeviceConfig() {
        val storeId = prefs.storeId ?: return
        scope.launch {
            val list = api.fetchDeviceConfig(BuildConfig.POS_URL, storeId)
            if (list != null) RelayState.deviceConfigPrinters = list
        }
    }

    /**
     * Local HTTP server (NanoHTTPD) on port 8787.
     * Accepts POST /print from any LAN device with {printerIp, printerName, content, format, job}.
     * This is the direct-LAN-call path (alternative to cloud relay).
     */
    private fun startHttpServer() {
        runCatching {
            httpServer = HubHttpServer(this, prefs)
            httpServer?.start()
            Log.i(TAG, "HTTP server started on port ${HubHttpServer.PORT}")
        }.onFailure {
            Log.e(TAG, "HTTP server start failed", it)
            CrashLog.write(this, "HubHttpServer.start", it)
        }
    }

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
            "pairing" -> "等待配對：請喺 web POS「設置 → 打印機」輸入店舖 ID 配對"
            "connecting" -> "連線中…"
            "online" -> "已連線 · 已印 ${RelayState.printedCount} 張" +
                (if (RelayState.failedCount > 0) " · 失敗 ${RelayState.failedCount}" else "")
            "offline" -> "雲端斷線，30s 輪詢兜底 · 已印 ${RelayState.printedCount} 張"
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
            Intent(this, com.macau.printhub.ui.MainActivity::class.java),
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
            // 冇咗呢個，摺埋嘅通知會 cut 到剩頭幾個字，等於冇講。
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HubService"
        private const val CHANNEL_ID = "pos_print_hub"
        private const val NOTIF_ID = 8788
        private const val TICK_MS = 1_000L
        private const val RECONCILE_MS = 30_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val CONFIG_FETCH_MS = 60_000L

        fun start(context: Context) {
            val i = Intent(context, HubService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

/** Doze helper */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}
