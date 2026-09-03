package com.macau.pos.printagent.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.macau.pos.printagent.model.BtPrinterCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 藍牙 ESC/POS 打印機傳輸層（Android Bluetooth API）。
 *
 * 做法：BluetoothDevice → createRfcommSocketToServiceRecord(SPP UUID) → connect →
 * 寫 byte[] 去 outputStream。SPP UUID = 00001101-0000-1000-8000-00805F9B34FB。
 *
 * 注意：Android 打印多數要先喺系統「配對」先 connect 到（createRfcomm 對未配對設備會掟 exception）。
 * 本層負責傳輸 + 權限檢查；探索/配對 UI 由 BtController + WebView 處理。
 */
class BtPrinter(private val context: Context) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val sppUuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun isSupported(): Boolean = adapter != null

    /** API 31+ 需要 BLUETOOTH_CONNECT runtime 權限先可以 connect。 */
    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 已配對（bonded）嘅藍牙設備，可直接打印。 */
    fun enumerateBonded(): List<BtPrinterCandidate> {
        val devices = adapter?.bondedDevices ?: return emptyList()
        return devices.map { BtPrinterCandidate.fromDevice(it, bonded = true) }
            .sortedBy { it.name ?: it.address }
    }

    /**
     * 打印 byte[] 去指定 MAC。未授權 → failure 帶 marker "BT_NEEDS_PERMISSION"。
     */
    suspend fun printBytes(
        address: String,
        data: ByteArray,
        timeoutMs: Int = 8000,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) {
            return@withContext Result.failure(
                PrintError("BT_NEEDS_PERMISSION", "藍牙打印機需要授權，請允許後重試")
            )
        }
        val dev: BluetoothDevice = adapter?.getRemoteDevice(address)
            ?: return@withContext Result.failure(PrintError("找不到藍牙設備 $address（請先配對）"))

        var socket: BluetoothSocket? = null
        try {
            socket = dev.createRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            socket.outputStream.use { out ->
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + CHUNK, data.size)
                    out.write(data, offset, end - offset)
                    offset = end
                }
                out.flush()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val unpaired = e is java.io.IOException && dev.bondState != BluetoothDevice.BOND_BONDED
            Result.failure(
                PrintError(
                    e.message ?: "藍牙打印失敗",
                    if (unpaired) "藍牙設備未配對，請先喺系統設定配對「${dev.name ?: address}」"
                    else e.message ?: "藍牙打印失敗"
                )
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    class PrintError(message: String, val userMessage: String = message) : Exception(message)

    companion object {
        private const val CHUNK = 4 * 1024
    }
}
