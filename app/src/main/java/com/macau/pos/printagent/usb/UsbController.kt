package com.macau.pos.printagent.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.macau.pos.printagent.model.UsbPrinterCandidate
import com.macau.pos.printagent.net.UsbKey
import com.macau.pos.printagent.net.UsbPrinter
import org.json.JSONArray
import org.json.JSONObject

/**
 * USB 打印機總控：擁有 UsbPrinter + 監聽 attach/detach/permission 廣播，
 * 並經 postJs 回傳事件畀 WebView（onUsbPrinterAttached / onUsbPrinterDetached /
 * onUsbPermissionResult），實現「插上即自動加機 + 自動彈授權」。
 */
class UsbController(
    private val context: Context,
    private val postJs: (String) -> Unit,
) {
    private val usbPrinter = UsbPrinter(context)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbPrinter.ACTION_USB_PERMISSION -> {
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val key = dev?.let { UsbKey(it.vendorId, it.productId, dev.serialNumber) }
                    postJs(
                        "if(window.onUsbPermissionResult)onUsbPermissionResult(${key != null}, ${JSONObject.quote(key?.stableKey() ?: "")}, $granted)"
                    )
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // 插上即自動請求授權 + 通知 WebView 刷新列表（auto-add）
                    usbPrinter.enumerate().forEach { cand ->
                        if (!cand.hasPermission) UsbKey.parse(cand.key)?.let { usbPrinter.requestPermission(it) }
                    }
                    postJs("if(window.onUsbPrinterAttached)onUsbPrinterAttached(${candidatesJson()})")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    postJs("if(window.onUsbPrinterDetached)onUsbPrinterDetached(${candidatesJson()})")
                }
            }
        }
    }

    fun register() {
        if (!usbPrinter.hasUsbHostFeature()) return
        val filter = IntentFilter().apply {
            addAction(UsbPrinter.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Android 13+（API 33）規定：用 implicit filter registerReceiver 一定要落
        // RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，否則掟 SecurityException 令 app 彈出
        // （見 crash.txt：UsbController.register @ MainActivity.setup:85）。呢個 receiver 淨係
        // app 內部收 USB 事件 + 自己嘅 USB_PERMISSION，用 NOT_EXPORTED 啱。
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun enumerate(): List<UsbPrinterCandidate> = usbPrinter.enumerate()

    fun requestPermission(key: UsbKey): Boolean = usbPrinter.requestPermission(key)

    fun hasPermission(key: UsbKey): Boolean = usbPrinter.hasPermission(key)

    suspend fun printBytes(key: UsbKey, data: ByteArray, timeoutMs: Int = 8000) =
        usbPrinter.printBytes(key, data, timeoutMs)

    /** 畀 WebView 用嘅 JSON 陣列字串。 */
    fun candidatesJson(): String {
        val arr = JSONArray()
        enumerate().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }
}
