package com.macau.printhub.relay

import android.content.Context
import android.util.Log
import com.macau.printhub.model.LogEntry
import com.macau.printhub.model.PrintJobDto
import com.macau.printhub.model.PrinterCfgDto
import com.macau.printhub.net.EscPosRenderer
import com.macau.printhub.net.SdkPrinter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Local HTTP server (NanoHTTPD) on port 8787.
 * Accepts direct LAN print requests from macau-pos or other devices.
 *
 * POST /print
 * Body: { "printerIp": "192.168.1.50", "printerName": "廚房", "job": {...}, "format": "escpos"|"json" }
 *
 * If format="escpos" and content is a base64 byte array → forward raw bytes directly.
 * If format="json" (or omitted) → render via EscPosRenderer, then forward.
 *
 * Response: { "ok": true/false, "error": "..." }
 */
class HubHttpServer(
    private val context: Context,
    private val prefs: RelayPrefs,
) : NanoHTTPD(PORT) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        return when {
            uri == "/print" && method == Method.POST -> handlePrint(session)
            uri == "/status" && method == Method.GET -> handleStatus()
            uri == "/printers" && method == Method.GET -> handlePrinters()
            else -> addCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
        }
    }

    private fun addCors(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, x-agent-id, x-agent-token")
        return resp
    }

    private fun handlePrint(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val req = JSONObject(body)
            val printerIp = req.optString("printerIp").takeIf { it.isNotBlank() }
            val printerName = req.optString("printerName").takeIf { it.isNotBlank() }
            val format = req.optString("format", "json")
            val jobObj = req.optJSONObject("job")

            if (printerIp.isNullOrBlank() && jobObj == null) {
                val resp = JSONObject().put("ok", false).put("error", "Missing printerIp and job")
                return addCors(newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", resp.toString()))
            }

            scope.launch {
                printDirect(printerIp, printerName, format, jobObj)
            }

            val resp = JSONObject().put("ok", true).put("message", "Print job queued")
            addCors(newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "handlePrint error", e)
            val resp = JSONObject().put("ok", false).put("error", e.message ?: "Unknown error")
            addCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", resp.toString()))
        }
    }

    private fun handleStatus(): Response {
        val resp = JSONObject()
            .put("paired", prefs.isPaired())
            .put("phase", RelayState.phase)
            .put("realtimeConnected", RelayState.realtimeConnected)
            .put("printedCount", RelayState.printedCount)
            .put("failedCount", RelayState.failedCount)
            .put("lastMessage", RelayState.lastMessage)
            .put("localIp", RelayState.localIp ?: "")
        return addCors(newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()))
    }

    private fun handlePrinters(): Response {
        val store = com.macau.printhub.data.DeviceStore(context)
        val devices = store.load().values.toList()
        val arr = org.json.JSONArray()
        devices.sortedByDescending { it.lastSeen }.forEach { d ->
            arr.put(org.json.JSONObject()
                .put("key", d.key)
                .put("name", d.name)
                .put("ip", d.ip)
                .put("mac", d.mac ?: "")
                .put("canRawPrint", d.canRawPrint)
            )
        }
        return addCors(newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString()))
    }

    private suspend fun printDirect(
        printerIp: String?,
        printerName: String?,
        format: String,
        jobObj: JSONObject?,
    ) {
        val ip = printerIp ?: run {
            // Try to resolve from local device store by name
            val store = com.macau.printhub.data.DeviceStore(context)
            val dev = store.load().values.firstOrNull { it.name == printerName && it.canRawPrint }
            dev?.ip
        }
        if (ip.isNullOrBlank()) {
            val entry = LogEntry(
                id = LogEntry.nextId(),
                timestamp = System.currentTimeMillis(),
                source = "HTTP /print",
                targetPrinter = printerName ?: "(unknown)",
                summary = "Cannot resolve printer IP",
                success = false,
                error = "No printerIp and could not resolve by name",
            )
            LogEntryLog.add(entry)
            RelayState.failedCount++
            RelayState.notePrintError(entry.summary, entry.targetPrinter, entry.error)
            return
        }

        val cfg = PrinterCfgDto(
            id = "http:$ip",
            name = printerName ?: ip,
            connectionType = "lan",
            ipAddress = ip,
            lanPort = 9100,
            paperSize = prefs.defaultPaperSize,
            usbLabel = null,
            charset = null,
        )

        try {
            val bytes = if (format == "escpos") {
                // Raw base64-encoded ESC/POS bytes — forward directly
                val base64 = jobObj?.optString("content") ?: ""
                if (base64.isBlank()) throw IllegalArgumentException("Missing content for escpos format")
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } else {
                // JSON job → render via EscPosRenderer
                val job = jobObj?.let { PrintJobDto.fromJson(it) }
                    ?: throw IllegalArgumentException("Missing job for json format")
                val kind = jobObj?.optString("kind")?.takeIf { it.isNotBlank() }
                    ?: job.template?.kind ?: "kitchen"
                val storeName = prefs.storeName ?: "Macau POS"
                when (kind) {
                    "receipt" -> EscPosRenderer.renderReceiptTicket(job, cfg, storeName, null, null)
                    "test" -> EscPosRenderer.renderTestPage(cfg, storeName)
                    else -> if (job.template != null) EscPosRenderer.renderTemplateTicket(job, cfg)
                            else EscPosRenderer.renderKitchenTicket(job, cfg, storeName)
                }
            }

            val result = SdkPrinter.printBytes(context, cfg, bytes)
            val entry = LogEntry(
                id = LogEntry.nextId(),
                timestamp = System.currentTimeMillis(),
                source = "HTTP /print",
                targetPrinter = "${cfg.name} ($ip)",
                summary = jobObj?.optString("orderNo")?.takeIf { it.isNotBlank() } ?: "Print job",
                success = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            )
            LogEntryLog.add(entry)
            if (result.isSuccess) {
                RelayState.printedCount++
            } else {
                RelayState.failedCount++
                RelayState.notePrintError(entry.summary, entry.targetPrinter, entry.error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "printDirect failed", e)
            val entry = LogEntry(
                id = LogEntry.nextId(),
                timestamp = System.currentTimeMillis(),
                source = "HTTP /print",
                targetPrinter = "${printerName ?: ip}",
                summary = "Print failed",
                success = false,
                error = e.message,
            )
            LogEntryLog.add(entry)
            RelayState.failedCount++
            RelayState.notePrintError(entry.summary, entry.targetPrinter, e.message)
        }
    }

    companion object {
        private const val TAG = "HubHttpServer"
        const val PORT = 8787
    }
}

/**
 * Simple in-memory log store for the UI.
 */
object LogEntryLog {
    private val entries = mutableListOf<LogEntry>()
    private val lock = Any()
    private const val MAX_ENTRIES = 200

    fun add(entry: LogEntry) {
        synchronized(lock) {
            entries.add(0, entry)
            if (entries.size > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size).clear()
        }
    }

    fun all(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun filtered(successFilter: Boolean?, searchText: String): List<LogEntry> = synchronized(lock) {
        entries.filter { e ->
            (successFilter == null || e.success == successFilter) &&
            (searchText.isBlank() ||
                e.targetPrinter.contains(searchText, ignoreCase = true) ||
                e.summary.contains(searchText, ignoreCase = true) ||
                (e.error?.contains(searchText, ignoreCase = true) ?: false) ||
                e.source.contains(searchText, ignoreCase = true))
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
