package com.macau.printhub.relay

import android.content.Context
import android.util.Log
import com.macau.printhub.data.DeviceStore
import com.macau.printhub.model.LogEntry
import com.macau.printhub.model.PrintJobDto
import com.macau.printhub.model.PrinterCfgDto
import com.macau.printhub.model.PrinterDevice
import com.macau.printhub.net.EscPosRenderer
import com.macau.printhub.net.SdkPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Job runner — claim → render → dispatch → report.
 * Ported from print-agent-android, adapted for pure LAN hub (no Sunmi).
 */
object JobRunner {

  private const val TAG = "JobRunner"
  private const val CLAIM_LIMIT = 5
  private const val MAX_COPIES = 5

  /**
   * 防 org.json.optString 嘅 "null" 地雷：JSON null 值會被轉成字面字串 "null"，
   * 而 takeIf { isNotBlank() } 擋唔住（"null" 唔係空白）。見 docs/103。
   */
  private fun JSONObject.optCleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
  }

  data class DrainResult(val claimed: Int, val sent: Int, val failed: Int, val error: String?)

    suspend fun drain(
        context: Context,
        prefs: RelayPrefs,
        api: RelayApi,
        baseUrl: String,
    ): DrainResult = withContext(Dispatchers.IO) {
        val agentId = prefs.agentId ?: return@withContext DrainResult(0, 0, 0, "未生成 agentId")
        val token = prefs.agentToken ?: return@withContext DrainResult(0, 0, 0, "未完成配對")
        val storeId = prefs.storeId ?: return@withContext DrainResult(0, 0, 0, "未綁定店舖")

        RelayState.lastClaimAt = System.currentTimeMillis()

        val res = api.claim(baseUrl, agentId, token, storeId, CLAIM_LIMIT)
        if (res.error != null) return@withContext DrainResult(0, 0, 0, res.error)
        if (res.jobs.isEmpty()) return@withContext DrainResult(0, 0, 0, null)

        var sent = 0
        var failed = 0
        for (row in res.jobs) {
            val jobId = row.optString("id")
            if (jobId.isBlank()) continue
            val startedAt = System.currentTimeMillis()

            // printOne 一定要出 outcome（成功/失敗 + 原因 + 打印機名），
            // 唔可以再靠「無聲失敗」——雲端中繼以前完全冇寫 LogEntryLog，UI 日誌一片空白。
            val outcome = runCatching { printOne(context, prefs, row, res.printers) }
                .getOrElse { e -> PrintOutcome(false, e.message ?: e.toString(), "(未能解析打印機)") }

            val err = if (outcome.ok) null else outcome.error
            val ok = outcome.ok
            val elapsed = System.currentTimeMillis() - startedAt

            if (ok) {
                sent++
                RelayState.printedCount++
            } else {
                failed++
                RelayState.failedCount++
                Log.w(TAG, "列印失敗 $jobId：$err")
                // 寫低原因畀通知欄用：LogEntryLog 得 app 內 UI 睇到，
                // headless 中繼專用機淨係靠常駐通知，冇呢個就完全無從追查。
                RelayState.notePrintError(
                    row.optString("orderNo").takeIf { it.isNotBlank() } ?: jobId,
                    outcome.printerLabel,
                    err,
                )
            }

            LogEntryLog.add(
                LogEntry(
                    id = LogEntry.nextId(),
                    timestamp = System.currentTimeMillis(),
                    source = "雲端中繼",
                    targetPrinter = outcome.printerLabel,
                    summary = buildString {
                        append(row.optString("orderNo").takeIf { it.isNotBlank() } ?: jobId)
                        append("　${row.optString("kind").takeIf { it.isNotBlank() } ?: "kitchen"}")
                        append("（${elapsed}ms）")
                    },
                    success = ok,
                    error = err,
                )
            )

            api.report(
                baseUrl, agentId, token, jobId,
                if (ok) "sent" else "failed",
                err,
            )
        }
        DrainResult(res.jobs.size, sent, failed, null)
    }

    /** 每次列印嘅結果：成功與否 + 原因 + 目標打印機（畀日誌用）。 */
    private data class PrintOutcome(
        val ok: Boolean,
        val error: String?,
        val printerLabel: String,
    )

    private fun PrinterCfgDto.label(): String = when (connectionType) {
        "usb" -> "$name (USB ${usbLabel ?: "?"})"
        "bluetooth" -> "$name (BT ${bluetoothAddress ?: "?"})"
        else -> "$name (${ipAddress ?: "冇 IP"}:${lanPort.takeIf { it > 0 } ?: 9100})"
    }

    private suspend fun printOne(
        context: Context,
        prefs: RelayPrefs,
        row: JSONObject,
        printers: List<JSONObject>,
    ): PrintOutcome {
        val job = PrintJobDto.fromRow(row)
        val cfg = resolvePrinter(context, prefs, row, printers)
        val label = cfg.label()
        val kind = row.optString("kind").takeIf { it.isNotBlank() && it != "null" }
            ?: job.template?.kind
            ?: "kitchen"
        val storeName = row.optCleanString("store_name")
            ?: job.content?.get("store_name")?.takeIf { it.isNotBlank() && it != "null" }
            ?: prefs.storeName
        val payment = row.optCleanString("payment_method")
        val total = row.opt("total") as? Double ?: row.optString("total").toDoubleOrNull()
        val copies = row.optInt("copies", 1).coerceIn(1, MAX_COPIES)

        val bytes = render(job, cfg, kind, storeName, payment, total)

        for (i in 0 until copies) {
            val r = dispatch(context, cfg, bytes)
            if (r.isFailure) {
                return PrintOutcome(
                    false,
                    r.exceptionOrNull()?.message ?: "列印失敗（第 ${i + 1}/$copies 份）",
                    label,
                )
            }
            if (i < copies - 1) kotlinx.coroutines.delay(600)
        }
        return PrintOutcome(true, null, label)
    }

    private fun render(
        job: PrintJobDto,
        cfg: PrinterCfgDto,
        kind: String,
        storeName: String?,
        payment: String?,
        total: Double?,
    ): ByteArray = when {
        job.template != null -> EscPosRenderer.renderTemplateTicket(job, cfg)
        kind == "receipt" -> EscPosRenderer.renderReceiptTicket(job, cfg, storeName, payment, total)
        kind == "test" -> EscPosRenderer.renderTestPage(cfg, storeName)
        else -> EscPosRenderer.renderKitchenTicket(job, cfg, storeName)
    }

    private suspend fun dispatch(
        context: Context,
        cfg: PrinterCfgDto,
        bytes: ByteArray,
    ): Result<Unit> = SdkPrinter.printBytes(context, cfg, bytes)

    /**
     * Printer resolution order:
     *  0. row.printer (jsonb snapshot from server — 未寫入，保留兼容)
     *  1. **web POS 路由配置**（問題二 / docs/98）：job.printer_id（web uid）== RoutingPrinter.id，
     *     直接 match 到配置機嘅 IP:port，權威揀機，取代舊 fallback「第一個開 9100 嘅機」（H6）
     *  2. claim response printers array (match by printer_id → printer_name，目前恒為 [])
     *  3. Local LAN hub devices (match by name → key)
     *  4. Fallback: first available LAN device
     */
    private fun resolvePrinter(
        context: Context,
        prefs: RelayPrefs,
        row: JSONObject,
        printers: List<JSONObject>,
    ): PrinterCfgDto {
        row.optJSONObject("printer")?.let {
            return PrinterCfgDto.fromJson(it)
        }

        val pid = row.optString("printer_id").takeIf { it.isNotBlank() }
        val pname = row.optString("printer_name").takeIf { it.isNotBlank() }

        // 問題二（docs/98）：用 web POS 同步過嚟嘅路由配置做權威揀機。
        // job.printer_id（web uid）== RoutingPrinter.id，直接 match 到配置機嘅 IP:port。
        // 取代舊 fallback「第一個開 9100 嘅機」（H6）。配置機 IP 空白就跌落本地發現匹配。
        val routed = RelayState.deviceConfigPrinters
            .firstOrNull { it.id == pid && it.enabled }
        if (routed != null && !routed.ipAddress.isNullOrBlank()) {
            return PrinterCfgDto(
                id = routed.id,
                name = routed.name,
                connectionType = "lan",
                ipAddress = routed.ipAddress,
                lanPort = routed.lanPort ?: 9100,
                paperSize = prefs.defaultPaperSize,
                usbLabel = null,
                charset = null,
                kanjiEnlarge = routed.kanjiEnlarge,
            )
        }

        if (printers.isNotEmpty()) {
            val list = printers.map { PrinterCfgDto.fromJson(it) }
            list.firstOrNull { pid != null && it.id == pid }?.let { return it }
            list.firstOrNull { pname != null && it.name == pname }?.let { return it }
        }

        runCatching {
            val store = DeviceStore(context)
            val devices = store.load()
            devices.values.firstOrNull { pname != null && it.name == pname }?.let {
                return PrinterCfgDto(
                    id = it.key,
                    name = it.name,
                    connectionType = "lan",
                    ipAddress = it.ip,
                    lanPort = 9100,
                    paperSize = prefs.defaultPaperSize,
                    usbLabel = null,
                    charset = null,
                )
            }
            // Fallback: first device with port 9100 open
            devices.values.firstOrNull { it.canRawPrint }?.let {
                return PrinterCfgDto(
                    id = it.key,
                    name = it.name,
                    connectionType = "lan",
                    ipAddress = it.ip,
                    lanPort = 9100,
                    paperSize = prefs.defaultPaperSize,
                    usbLabel = null,
                    charset = null,
                )
            }
        }

        return PrinterCfgDto(
            id = "fallback-lan",
            name = "LAN 打印機",
            connectionType = "lan",
            ipAddress = null,
            lanPort = 9100,
            paperSize = prefs.defaultPaperSize,
            usbLabel = null,
            charset = null,
        )
    }
}
