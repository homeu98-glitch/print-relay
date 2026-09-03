package com.macau.pos.printagent.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.macau.pos.printagent.MainActivity
import com.macau.pos.printagent.R
import com.macau.pos.printagent.net.LanScanner

class PrintHubService : Service() {
    private var server: LanHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ip = LanScanner(this).detectLocalIpv4()
        val url = if (ip.isNullOrBlank()) {
            "port ${PrinterHub.PORT}"
        } else {
            "http://$ip:${PrinterHub.PORT}"
        }
        // startForeground 一定要成功（Android 要求 service 起身幾秒內 call），
        // 但萬一 foreground service type 喺某部機被系統 reject，吞底先唔好連累成個 app 彈出。
        // ⚠️ 如果 startForeground 真係掟錯而冇入到 foreground 狀態，系統會喺幾秒內
        // kill 成個 hosting process（"did not call Service.startForeground"）。
        // 所以 onFailure 即時 stopSelf()，等系統唔使 kill 成個 app。
        runCatching { startAsForeground(url) }.onFailure {
            Log.e(TAG, "startForeground 失敗，停止 PrintHubService 避免被系統 kill 成個 app", it)
            stopSelf()
        }
        try {
            server = LanHttpServer(this).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 啟動失敗", e)
            PrinterHub.get(this).listening = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startAsForeground(url: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.hub_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.hub_running))
            .setContentText(url)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PrintHubService"
        private const val CHANNEL_ID = "pos_print_hub"
        private const val NOTIF_ID = 8787

        fun start(context: Context) {
            val i = Intent(context, PrintHubService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
