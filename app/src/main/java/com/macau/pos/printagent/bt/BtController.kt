package com.macau.pos.printagent.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.macau.pos.printagent.model.BtPrinterCandidate
import com.macau.pos.printagent.net.BtPrinter
import org.json.JSONArray

/**
 * 藍牙打印機總控：擁有 BtPrinter + 探索（discovery）廣播 + WebView 回調
 * （onBtPrinterFound / onBtDiscoveryFinished / onBtPermissionNeeded）。
 *
 * 探索出嚟嘅設備若未配對，打印前要先喺系統設定配對（標準 ESC/POS BT 行為）。
 */
class BtController(
    private val context: Context,
    private val postJs: (String) -> Unit,
) {
    private val btPrinter = BtPrinter(context)
    private var discoveryReceiver: BroadcastReceiver? = null

    fun isSupported(): Boolean = btPrinter.isSupported()

    fun hasConnectPermission(): Boolean = btPrinter.hasConnectPermission()

    /** API 31+ 探索需要 BLUETOOTH_SCAN。 */
    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun enumerateBonded(): List<BtPrinterCandidate> = btPrinter.enumerateBonded()

    fun bondedJson(): String {
        val arr = JSONArray()
        enumerateBonded().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun startDiscovery() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            postJs("onBtDiscoveryError('藍牙未開啟')")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !hasScanPermission()) {
            postJs("onBtPermissionNeeded()")
            return
        }
        if (discoveryReceiver != null) return // 已經喺探索

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        dev?.let {
                            val cand = BtPrinterCandidate.fromDevice(it, it.bondState == BluetoothDevice.BOND_BONDED)
                            postJs("onBtPrinterFound(${cand.toJson().toString()})")
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        postJs("onBtDiscoveryFinished()")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 13+（API 33）規定 implicit filter registerReceiver 要落
        // RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，否則掟 SecurityException。
        // 呢個 receiver 只收探索結果，用 NOT_EXPORTED。
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(discoveryReceiver, filter)
        }
        adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    fun stopDiscovery() {
        discoveryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        discoveryReceiver = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
    }

    fun unregister() = stopDiscovery()

    suspend fun printBytes(address: String, data: ByteArray, timeoutMs: Int = 8000) =
        btPrinter.printBytes(address, data, timeoutMs)
}
