package com.macau.pos.printagent.net

import android.content.Context
import android.util.Log
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
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
 * 採用廠商 printer-lib AAR（net.posprinter）做「連線 + 傳輸」，替代舊有手寫 USB/BT socket。
 *
 * 重要：呢個 AAR build（3.5.3）冇 setLineSpacing / selectCodePage 呢兩個 high-level API，
 * 而且 setCharSet 嘅 token 映射唔公開。所以渲染仍然交畀 EscPosRenderer（已含大字體行距
 * ESC 3 n 修正 + 中文編碼 + Kanji 倍大），產好 ESC/POS byte[] 之後經 conn.sendData() 寫落去。
 * 咁做同時消滅三個 bug：
 *  1. 大字體變扁 —— EscPosRenderer 每行列印前同步 ESC 3 n（l→60，s/m→30）。
 *  2. 中文唔變大 —— EscPosRenderer 用 FS &/GS !n Kanji 倍大（商頌 POS-80 實機驗證）。
 *  3. USB/BT 連線脆弱 —— 改用 AAR 嘅 POSConnect（處理 USB 授權 / BT SPP / LAN TCP）。
 * 對應 docs/75 路(b)。
 *
 * 連線模式（印製手冊規格）：POSConnect.createDevice(type) → conn.connect(info) { code,_,_ -> }。
 * info 格式：USB="vid,pid" / BLUETOOTH="mac" / ETHERNET="ip,port"（見 Android 接口编程手册）。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 2026-09-02 修：連線必須有 timeout
 * ─────────────────────────────────────────────────────────────
 * 廠商 SDK 嘅 `conn.connect(info, callback)` **唔保證 callback 會 fire**。
 * IP 唔通、port 冇開、對方 drop 咗個 SYN 而唔俾 RST 嘅時候，callback 可以永遠唔 call 返嚟。
 * 舊版用 `suspendCancellableCoroutine` 包住但冇 timeout → coroutine 永久掛起 →
 * 唔 throw、唔返 result → UI 冇反應、日誌冇記錄，用家只會覺得「掣失靈」。
 * （同一個 bug 喺 Print Hub v1.1.2 已經修過，呢邊係同一招。）
 *
 * 另外 LAN 打印機改行 raw socket 優先：AAR 嘅 ethernet 其實都係 raw TCP 9100，
 * 直出快、有硬性 timeout、唔使靠 AAR；失敗先 fallback 落 SDK。
 * USB / 藍牙 / Sunmi 一定要行 SDK（要處理 USB 授權同 BT SPP），唔可以 raw。
 */
object SdkPrinter {

    private const val TAG = "SdkPrinter"
    const val CONNECT_TIMEOUT_MS = 6000L

    @Volatile
    private var initialized = false

    /** 最近一次 SDK 連線回傳碼，畀診斷用（0 = 未試過）。 */
    @Volatile
    var lastConnectCode: Int = 0
        private set

    /** 最近一次 raw socket 直連嘅錯誤訊息。 */
    @Volatile
    var lastRawError: String? = null
        private set

    /** LAN raw 直出用。EscPosPrinter 同時俾 PrinterHub 用，所以唔改做 object。 */
    private val rawPrinter = EscPosPrinter()

    /** POSConnect.init 只需叫一次。建議搬去 Application.onCreate（見 docs/75）。 */
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

    /** LAN 先試 raw socket；USB / 藍牙 / Sunmi 一定要經 SDK。 */
    private fun canUseRaw(cfg: PrinterCfgDto): Boolean =
        (cfg.connectionType == "lan" || cfg.connectionType == "ethernet") &&
            !cfg.ipAddress.isNullOrBlank()

    private fun describeCode(code: Int): String = when (code) {
        POSConnect.CONNECT_SUCCESS -> "成功"
        POSConnect.CONNECT_FAIL -> "失敗"
        POSConnect.CONNECT_INTERRUPT -> "連線中斷"
        POSConnect.SEND_FAIL -> "送紙失敗"
        else -> "未知碼 $code"
    }

    /**
     * 異步連線 → 掛起等 CONNECT_SUCCESS，**帶硬性 timeout**。
     * timeout 時會 close 條 connection，而且一定會有 Result 返，絕唔會靜默掛起。
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

    /** 主入口：渲染 job 並印出（LAN 行 raw 優先，其餘行 SDK）。 */
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
     * 雲端中繼用（docs/96 §7）：連線 → 送已經 render 好嘅 raw ESC/POS bytes → 斷線。
     *
     * 拆出嚟係因為中繼嘅 bytes 由 JobRunner 決定點 render（模板 / receipt / kitchen），
     * 唔可以再行 `print()` 入面嗰個 `when(kind)` —— 否則中繼會印錯版面。
     */
    suspend fun printBytes(
        context: Context,
        cfg: PrinterCfgDto,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (canUseRaw(cfg)) {
            val ip = cfg.ipAddress!!
            val port = if (cfg.lanPort > 0) cfg.lanPort else 9100
            val raw = rawPrinter.printRaw(ip, port, bytes, EscPosPrinter.RAW_TIMEOUT_MS)
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
                IOException("兩種通道都失敗｜直連：$rawErr｜SDK：${sdk.exceptionOrNull()?.message}")
            )
        }
        printViaSdk(context, cfg, bytes)
    }

    /** 經廠商 AAR 送紙（USB / 藍牙 / LAN fallback 都行呢條）。 */
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

    /** 測試頁。 */
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
