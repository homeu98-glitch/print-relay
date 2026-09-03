package com.macau.pos.printagent.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.macau.pos.printagent.model.UsbPrinterCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * USB ESC/POS 打印機傳輸層（Android USB Host API）。
 *
 * 做法：枚舉所有 USB_CLASS_PRINTER (7) 設備 → 用家授權（UsbManager.requestPermission）
 * → openDevice → claim interface → 搵 bulk-OUT endpoint → bulkTransfer 寫 ESC/POS byte[]。
 *
 * 採用 **open-per-print**：每次打印都 open/close，簡單、唔使處理連線快取失效；
 * 單張單據 < 1s，實際可用。detach 時設備自然喺 getDeviceList 消失，下次打印會報錯俾 POS。
 */
class UsbPrinter(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** 畀 UsbController 註冊廣播用嘅同一個 action。 */
    val permissionIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        0x5551,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun hasUsbHostFeature(): Boolean =
        runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) }
            .getOrDefault(false)

    /** 列舉目前插著嘅 USB 打印機（class 7），附權限狀態。 */
    fun enumerate(): List<UsbPrinterCandidate> {
        if (!hasUsbHostFeature()) return emptyList()
        val list = usbManager.deviceList ?: return emptyList()
        return list.values
            .filter { isPrinterDevice(it) }
            .map { dev ->
                UsbPrinterCandidate.fromDevice(
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    deviceName = dev.deviceName,
                    productName = dev.productName,
                    serialNumber = safeSerial(dev),
                    hasPermission = usbManager.hasPermission(dev),
                )
            }
            .sortedBy { it.brandLabel }
    }

    /** 用家未授權就返 false 並彈系統授權對話框；已授權返 true。 */
    fun requestPermission(key: UsbKey): Boolean {
        val dev = findDevice(key) ?: return false
        if (usbManager.hasPermission(dev)) return true
        runCatching { usbManager.requestPermission(dev, permissionIntent) }
        return false
    }

    fun hasPermission(key: UsbKey): Boolean {
        val dev = findDevice(key) ?: return false
        return usbManager.hasPermission(dev)
    }

    /**
     * 打印 byte[] 去指定 USB 打印機。
     * - 搵唔到設備 → failure("找不到 USB 打印機 ...")
     * - 未授權 → failure 帶 marker "USB_PRINTER_NEEDS_PERMISSION"（POS 應叫 requestPermission 再試）
     * - 成功 → success(Unit)
     */
    suspend fun printBytes(
        key: UsbKey,
        data: ByteArray,
        timeoutMs: Int = 8000,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dev = findDevice(key) ?: return@withContext Result.failure(
            PrintError("找不到 USB 打印機 (VID:${key.vendorId} PID:${key.productId})，請確認已插好")
        )
        if (!usbManager.hasPermission(dev)) {
            runCatching { usbManager.requestPermission(dev, permissionIntent) }
            return@withContext Result.failure(
                PrintError("USB_PRINTER_NEEDS_PERMISSION", "USB 打印機未授權，已彈出授權對話框，請允許後重試")
            )
        }

        var connection: android.hardware.usb.UsbDeviceConnection? = null
        try {
            connection = usbManager.openDevice(dev)
                ?: return@withContext Result.failure(PrintError("開啟 USB 設備失敗（openDevice 返 null，可能已被其他 App 佔用）"))

            val iface = (0 until dev.interfaceCount)
                .asSequence()
                .map { dev.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                ?: dev.getInterface(0)

            if (!connection.claimInterface(iface, true)) {
                return@withContext Result.failure(PrintError("claimInterface 失敗（無法取得打印機控制權）"))
            }

            val epOut = (0 until iface.endpointCount)
                .asSequence()
                .map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
                ?: return@withContext Result.failure(PrintError("搵唔到 USB bulk-OUT endpoint（唔係標準 ESC/POS USB 打印機？）"))

            // 分塊寫，避免單次過大被拒。
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK, data.size)
                val chunk = data.copyOfRange(offset, end)
                val written = connection.bulkTransfer(epOut, chunk, chunk.size, timeoutMs)
                if (written < 0) {
                    return@withContext Result.failure(PrintError("bulkTransfer 失敗（written=$written）"))
                }
                offset = end
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PrintError(e.message ?: "USB 打印異常"))
        } finally {
            runCatching {
                connection?.releaseInterface(
                    (0 until dev.interfaceCount).firstNotNullOfOrNull { dev.getInterface(it) }
                        ?: dev.getInterface(0)
                )
            }
            runCatching { connection?.close() }
        }
    }

    private fun findDevice(key: UsbKey): UsbDevice? {
        val list = usbManager.deviceList ?: return null
        return list.values.firstOrNull { dev ->
            dev.vendorId == key.vendorId &&
                dev.productId == key.productId &&
                (key.serial.isNullOrBlank() || safeSerial(dev) == key.serial)
        }
    }

    private fun isPrinterDevice(dev: UsbDevice): Boolean {
        if (dev.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        return (0 until dev.interfaceCount).any {
            dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER
        }
    }

    private fun safeSerial(dev: UsbDevice): String? =
        runCatching { dev.serialNumber?.takeIf { it.isNotBlank() } }.getOrNull()

    class PrintError(message: String, val userMessage: String = message) : Exception(message)

    companion object {
        const val ACTION_USB_PERMISSION = "com.macau.pos.printagent.USB_PERMISSION"
        private const val CHUNK = 8 * 1024
    }
}
