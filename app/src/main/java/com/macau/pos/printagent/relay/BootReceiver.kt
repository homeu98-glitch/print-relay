package com.macau.pos.printagent.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 掉電重啟／升級 app 之後自動起身中繼（docs/96 §10）。
 *
 * 得「已配對」先至起服務 —— 未配對起咗都係空轉兼食電。
 *
 * ⚠️ Android 12+ 限制背景啟動 foreground service，但官方豁免清單包括
 * `ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED`，所以呢度合法。
 * 仍然包 try/catch：某啲 ROM（尤其國產）會自行加限制，唔可以因為咁 crash 個 receiver。
 * Sunmi V2 係 API 25，根本冇呢個限制。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = RelayPrefs(context)
        if (!prefs.isPaired()) return
        try {
            RelayService.start(context)
            Log.i(TAG, "開機自動啟動雲端中繼（$action）")
        } catch (e: Exception) {
            Log.w(TAG, "開機啟動中繼失敗（ROM 限制？）", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
