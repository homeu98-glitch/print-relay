package com.macau.pos.printagent.relay

import android.content.Context
import android.util.Log
import com.macau.pos.printagent.hub.PrinterHub
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import com.macau.pos.printagent.net.EscPosRenderer
import com.macau.pos.printagent.net.SdkPrinter
import com.macau.pos.printagent.net.SunmiPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 中繼嘅「攞單 → 渲染 → 出紙 → 回報」核心（docs/96 §7）。
 *
 * 每次 drain()：
 *  1. `POST /claim` 認領一批（server 用 `for update skip locked` → 多機唔會重複印）
 *  2. 逐張 render + 送紙
 *  3. 逐張 `POST /result` 回報 `sent` / `failed`
 *
 * ⚠️ **三倉 renderer 合約（docs/95）**：呢度**唔會**自己砌 bytes，一律行
 * `EscPosRenderer`（web / Companion / APK 共用同一套規則）。Sunmi 內置打印機
 * 都係行同一個 renderer，只係最後一段由 TCP socket 換成 AIDL `sendRAWData`。
 */
object JobRunner {

    private const val TAG = "JobRunner"
    private const val CLAIM_LIMIT = 5
    private const val MAX_COPIES = 5

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
            val result = runCatching { printOne(context, prefs, row, res.printers) }
            val err = result.exceptionOrNull()?.message
            val ok = result.getOrElse { false }
            if (ok) {
                sent++
                RelayState.printedCount++
            } else {
                failed++
                RelayState.failedCount++
                Log.w(TAG, "列印失敗 $jobId：$err")
                // 一定要寫低原因：否則用戶淨係見到「失敗 N 張」，完全無從追查。
                RelayState.lastPrintError = buildString {
                    append(row.optString("orderNo").takeIf { it.isNotBlank() } ?: jobId)
                    append(" → ")
                    append(printerLabelOf(row))
                    append("：")
                    append(err ?: "未知錯誤")
                }
                RelayState.lastPrintErrorAt = System.currentTimeMillis()
            }
            api.report(
                baseUrl, agentId, token, jobId,
                if (ok) "sent" else "failed",
                err,
            )
        }
        DrainResult(res.jobs.size, sent, failed, null)
    }

    /**
     * 由 job row 度搵出目標打印機名，淨係畀錯誤訊息顯示用。
     * 次序同 resolvePrinter() 一致：server 快照 printer.name → printer_name → printer_id。
     */
    private fun printerLabelOf(row: JSONObject): String =
        row.optJSONObject("printer")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: row.optString("printer_name").takeIf { it.isNotBlank() }
            ?: row.optString("printer_id").takeIf { it.isNotBlank() }
            ?: "(未能解析打印機)"

    /** 印一張；成功返 true，失敗拲 exception（caller 會轉做 failed + last_error）。 */
    private suspend fun printOne(
        context: Context,
        prefs: RelayPrefs,
        row: JSONObject,
        printers: List<JSONObject>,
    ): Boolean {
        val job = PrintJobDto.fromRow(row)
        val cfg = resolvePrinter(context, prefs, row, printers)
        val kind = row.optString("kind").takeIf { it.isNotBlank() }
            ?: job.template?.kind
            ?: "kitchen"
        val storeName = row.optString("store_name").takeIf { it.isNotBlank() } ?: prefs.storeName
        val payment = row.optString("payment_method").takeIf { it.isNotBlank() }
        val total = row.opt("total") as? Double ?: row.optString("total").toDoubleOrNull()
        val copies = row.optInt("copies", 1).coerceIn(1, MAX_COPIES)

        val bytes = render(job, cfg, kind, storeName, payment, total)

        for (i in 0 until copies) {
            val r = dispatch(context, cfg, bytes)
            if (r.isFailure) throw r.exceptionOrNull() ?: IllegalStateException("列印失敗")
            // 多份之間畀打印機少少緩衝，避免第二份 overwrite 未吐完嘅紙
            if (i < copies - 1) kotlinx.coroutines.delay(600)
        }
        return true
    }

    /** 三倉共用嘅渲染入口 —— 同 web `renderEscPosLines` / Companion `renderEscPos` 同源。 */
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
    ): Result<Unit> = when (cfg.connectionType) {
        // Sunmi 內置打印機（58mm）：同一個 renderer，最後一程行 AIDL
        "sunmi" -> withContext(Dispatchers.IO) {
            if (!SunmiPrinter.ensureReady(context)) {
                Result.failure(IllegalStateException("Sunmi 內置打印機未就緒（${SunmiPrinter.lastError ?: "未綁定"}）"))
            } else {
                SunmiPrinter.sendRaw(bytes)
            }
        }

        else -> SdkPrinter.printBytes(context, cfg, bytes)
    }

    /**
     * 目標打印機解析順序：
     *  1. `row.printer` —— server 端解析好嘅打印機快照（jsonb，最權威）
     *  2. claim 回傳嘅 `printers` 陣列（按 printer_id → printer_name 配對）
     *  3. 本機 LAN hub 已綁定設備（按 name → key 配對）
     *  4. Sunmi 內置打印機（單 Sunmi 舖嘅預設）
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
        if (printers.isNotEmpty()) {
            val list = printers.map { PrinterCfgDto.fromJson(it) }
            list.firstOrNull { pid != null && it.id == pid }?.let { return it }
            list.firstOrNull { pname != null && it.name == pname }?.let { return it }
        }

        runCatching {
            val snap = PrinterHub.get(context).snapshot()
            snap.firstOrNull { pname != null && it.name == pname }?.let {
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

        // 兜底：Sunmi 內置（單 Sunmi 舖嘅正常路徑）
        return PrinterCfgDto(
            id = "sunmi-builtin",
            name = "Sunmi 內置打印機",
            connectionType = "sunmi",
            ipAddress = null,
            lanPort = 9100,
            paperSize = prefs.defaultPaperSize,
            usbLabel = null,
            charset = null,
        )
    }
}
