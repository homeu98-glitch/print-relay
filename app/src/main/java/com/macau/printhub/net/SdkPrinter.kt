package com.macau.printhub.net

import android.content.Context
import android.util.Log
import com.macau.printhub.model.PrintJobDto
import com.macau.printhub.model.PrinterCfgDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Vendor AAR printer connection (net.posprinter 3.5.3).
 *
 * 重要：廠商 SDK 嘅 connect callback **唔保證一定會 fire**（IP 唔通／port 冇開被 drop 時
 * 佢可以永遠唔 call 返嚟）。所以所有連線都必須包 timeout，否則 coroutine 永久掛起 →
 * UI 無反應、LogEntryLog 無記錄，變成「撳咗冇反應又冇日誌」嘅鬼打牆。
 *
 * 對 LAN 打印機，優先行 EscPosPrinter 直出 raw socket（有硬性 timeout），
 * 失敗先 fallback 落 SDK。
 */
object SdkPrinter {

    private const val TAG = "SdkPrinter"
    const val CONNECT_TIMEOUT_MS = 6000L
    const val SEND_TIMEOUT_MS = 8000L

    @Volatile
    private var initialized = false

    /** 最近一次 SDK 連線回傳碼，畀 UI 顯示診斷用（0 = 未試過）。 */
    @Volatile
    var lastConnectCode: Int = 0
        private set

    /** 最近一次 raw socket 直連嘅錯誤訊息。 */
    @Volatile
    var lastRawError: String? = null
        private set

    fun initOnce(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    POSConnect.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    private fun deviceTypeOf(cfg: PrinterCfgDto): Int = when (cfg.connectionType) {
        "usb" -> POSConnect.DEVICE_TYPE_USB
        "bluetooth" -> POSConnect.DEVICE_TYPE_BLUETOOTH
        else -> POSConnect.DEVICE_TYPE_ETHERNET
    }

    private fun connectInfoOf(cfg: PrinterCfgDto): String = when (cfg.connectionType) {
        "usb" -> "${cfg.usbVendorId},${cfg.usbProductId}"
        "bluetooth" -> cfg.bluetoothAddress ?: ""
        else -> "${cfg.ipAddress ?: ""},${cfg.lanPort}"
    }

    private fun describeCode(code: Int): String = when (code) {
        POSConnect.CONNECT_SUCCESS -> "成功"
        POSConnect.CONNECT_FAIL -> "失敗"
        POSConnect.CONNECT_INTERRUPT -> "連線中斷"
        POSConnect.SEND_FAIL -> "送紙失敗"
        else -> "未知碼 $code"
    }

    private fun isLan(cfg: PrinterCfgDto): Boolean =
        cfg.connectionType != "usb" && cfg.connectionType != "bluetooth"

    /**
     * 連線，帶硬性 timeout。timeout 時會 close 條 connection，而且一定會有 Result 返。
     */
    private suspend fun connect(
        conn: IDeviceConnection,
        info: String,
        label: String,
    ): Result<Unit> {
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                cont.invokeOnCancellation { runCatching { conn.close() } }
                conn.connect(info) { code, _, _ ->
                    lastConnectCode = code
                    // 同一條 connection 有機會 callback 多次；isActive 保護唔好 resume 兩次
                    if (cont.isActive) cont.resume(code == POSConnect.CONNECT_SUCCESS)
                }
            }
        }

        return when (ok) {
            true -> Result.success(Unit)
            false -> Result.failure(
                IOException("打印機連線失敗（$label → ${describeCode(lastConnectCode)}）")
            )
            null -> Result.failure(
                IOException(
                    "打印機連線逾時（${CONNECT_TIMEOUT_MS / 1000}s 冇回應，$label）— " +
                        "檢查 IP／port／防火牆，對方可能直接 drop 咗個連線"
                )
            )
        }
    }

    suspend fun print(
        context: Context,
        job: PrintJobDto,
        cfg: PrinterCfgDto,
        kind: String,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val bytes = when {
            job.template != null -> EscPosRenderer.renderTemplateTicket(job, cfg)
            kind == "receipt" -> EscPosRenderer.renderReceiptTicket(job, cfg, storeName, paymentMethod, total)
            kind == "test" -> EscPosRenderer.renderTestPage(cfg, storeName)
            else -> EscPosRenderer.renderKitchenTicket(job, cfg, storeName)
        }
        printBytes(context, cfg, bytes)
    }

    /**
     * 統一嘅送紙入口。
     * LAN → 先試 raw socket 直出（快、有 timeout、唔靠 SDK）；失敗先 fallback 落 SDK。
     * USB / BT → 只能行 SDK。
     */
    suspend fun printBytes(
        context: Context,
        cfg: PrinterCfgDto,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isLan(cfg)) {
            val ip = cfg.ipAddress
            if (!ip.isNullOrBlank()) {
                val port = if (cfg.lanPort > 0) cfg.lanPort else 9100
                val raw = EscPosPrinter.printRaw(ip, port, bytes)
                if (raw.isSuccess) {
                    lastRawError = null
                    return@withContext Result.success(Unit)
                }
                val rawErr = raw.exceptionOrNull()?.message ?: "未知錯誤"
                lastRawError = rawErr
                Log.w(TAG, "raw socket 失敗（$ip:$port）：$rawErr，改行 SDK")

                val sdk = printViaSdk(context, cfg, bytes)
                return@withContext if (sdk.isSuccess) Result.success(Unit)
                else Result.failure(
                    IOException(
                        "兩種通道都失敗｜直連：$rawErr｜SDK：${sdk.exceptionOrNull()?.message}"
                    )
                )
            }
        }
        printViaSdk(context, cfg, bytes)
    }

    private suspend fun printViaSdk(
        context: Context,
        cfg: PrinterCfgDto,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val label = "${cfg.connectionType} ${connectInfoOf(cfg)}"
        var conn: IDeviceConnection? = null
        try {
            initOnce(context)
            conn = POSConnect.createDevice(deviceTypeOf(cfg))
            connect(conn, connectInfoOf(cfg), label).getOrThrow()
            conn.sendData(bytes)
            // 畀 SDK 少少時間 flush，特別係藍牙；LAN 都無害
            delay(120)
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.w(TAG, "SDK 送紙失敗（$label）", e)
            Result.failure(e)
        } finally {
            runCatching { conn?.close() }
        }
    }

    suspend fun testPrint(
        context: Context,
        cfg: PrinterCfgDto,
        storeName: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            printBytes(context, cfg, EscPosRenderer.renderTestPage(cfg, storeName)).getOrThrow()
        }
    }
}
