package com.macau.printhub.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start relay on boot or app update — only if already paired.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = RelayPrefs(context)
        if (!prefs.isPaired()) return
        try {
            HubService.start(context)
            Log.i(TAG, "開機自動啟動列印中繼（$action）")
        } catch (e: Exception) {
            Log.w(TAG, "開機啟動中繼失敗（ROM 限制？）", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
