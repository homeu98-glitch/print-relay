package com.macau.printhub.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * PrintJob DTO — ported from print-agent-android, same contract as web PrintJob.
 * Only fields needed for printing are captured.
 */
data class PrintJobDto(
    val id: String,
    val orderNo: String?,
    val tableName: String?,
    /** "normal" | "addon" | "void" */
    val ticketType: String,
    val printerId: String?,
    val printerName: String?,
    val items: List<Item>?,
    val content: Map<String, String>?,
    val template: TemplateDto?,
    val createdAt: Long?,
    val qrUrl: String?,
    val qr: QrPayload?,
) {
    data class Item(
        val name: String,
        val quantity: Int,
        val specs: List<String>?,
        val note: String?,
        val price: Double? = null,
        val discountRate: Double? = null,
        val originalUnitPrice: Double? = null,
        val discountedUnitPrice: Double? = null,
        val savingAmount: Double? = null,
    )

    data class QrPayload(
        val size: Int,
        val bits: String,
    ) {
        companion object {
            fun fromJson(o: JSONObject?): QrPayload? {
                if (o == null) return null
                val size = o.optInt("size", 0)
                val bits = o.optString("bits", "")
                if (size <= 0 || bits.length < size * size) return null
                return QrPayload(size = size, bits = bits)
            }
        }
    }

    data class Block(
        val id: String,
        val visible: Boolean = true,
        val size: String = "s",
        val bold: Boolean = false,
        val align: String = "left",
        val subSize: String = "s",
        val layout: String = "card",
    )

    data class TemplateDto(
        val kind: String,
        val blocks: List<Block>,
    )

    companion object {
        fun fromJson(o: JSONObject): PrintJobDto = PrintJobDto(
            id = o.optString("id"),
            orderNo = o.optString("orderNo").takeIf { it.isNotBlank() },
            tableName = o.optString("tableName").takeIf { it.isNotBlank() },
            ticketType = o.optString("ticketType", "normal"),
            printerId = o.optString("printerId").takeIf { it.isNotBlank() },
            printerName = o.optString("printerName").takeIf { it.isNotBlank() },
            items = parseItems(o.optJSONArray("items")),
            content = parseContent(o.optJSONObject("content")),
            template = parseTemplate(o.optJSONObject("template")),
            createdAt = parseTimestamp(o.optString("createdAt")),
            qrUrl = o.optString("qrUrl").takeIf { s -> s.isNotBlank() },
            qr = QrPayload.fromJson(o.optJSONObject("qr")),
        )

        fun fromRow(o: JSONObject): PrintJobDto = PrintJobDto(
            id = o.optString("id"),
            orderNo = firstNonBlank(o, "order_no", "orderNo"),
            tableName = firstNonBlank(o, "table_name", "tableName"),
            ticketType = firstNonBlank(o, "ticket_type", "ticketType") ?: "normal",
            printerId = firstNonBlank(o, "printer_id", "printerId"),
            printerName = firstNonBlank(o, "printer_name", "printerName"),
            items = parseItems(o.optJSONArray("items")),
            content = parseContent(o.optJSONObject("content")),
            template = parseTemplate(o.optJSONObject("template")),
            createdAt = parseTimestamp(firstNonBlank(o, "created_at", "createdAt")),
            qrUrl = firstNonBlank(o, "qr_url", "qrUrl"),
            qr = QrPayload.fromJson(o.optJSONObject("qr")),
        )

        private fun parseItems(arr: JSONArray?): List<Item>? = arr?.let { a ->
            (0 until a.length()).map { i ->
                val it = a.getJSONObject(i)
                val specs = it.optJSONArray("specs")?.let { s ->
                    (0 until s.length()).map { j -> s.getString(j) }
                }
                Item(
                    name = it.optString("name"),
                    quantity = it.optInt("quantity", 1),
                    specs = specs,
                    note = it.optString("note").takeIf { s -> s.isNotBlank() },
                    price = optDouble(it, "price"),
                    discountRate = optDouble(it, "discountRate"),
                    originalUnitPrice = optDouble(it, "originalUnitPrice"),
                    discountedUnitPrice = optDouble(it, "discountedUnitPrice"),
                    savingAmount = optDouble(it, "savingAmount"),
                )
            }
        }

        private fun parseContent(obj: JSONObject?): Map<String, String>? = obj?.let { c ->
            val map = LinkedHashMap<String, String>()
            c.keys().forEach { k -> map[k] = c.optString(k) }
            map
        }

        private fun parseTemplate(obj: JSONObject?): TemplateDto? = obj?.let { t ->
            val kind = t.optString("kind", "receipt")
            val blocks = t.optJSONArray("blocks")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val b = arr.getJSONObject(i)
                    Block(
                        id = b.optString("id"),
                        visible = b.optBoolean("visible", true),
                        size = b.optString("size", "s"),
                        bold = b.optBoolean("bold", false),
                        align = b.optString("align", "left"),
                        subSize = b.optString("subSize", "s"),
                        layout = b.optString("layout", "card"),
                    )
                }
            } ?: emptyList()
            TemplateDto(kind = kind, blocks = blocks)
        }

        private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                val v = o.optString(k)
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        private fun optDouble(o: JSONObject, key: String): Double? {
            if (!o.has(key) || o.isNull(key)) return null
            return runCatching { o.getDouble(key) }.getOrNull()
        }

        fun parseTimestamp(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            raw.toLongOrNull()?.let { return it }
            return runCatching {
                java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    java.util.Locale.US,
                ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(raw)?.time
            }.getOrNull()
        }
    }
}

data class PrinterCfgDto(
    val id: String,
    val name: String,
    val connectionType: String,
    val ipAddress: String?,
    val lanPort: Int,
    val paperSize: String?,
    val usbLabel: String?,
    val charset: String?,
    val kanjiEnlarge: String? = null,
    val usbVendorId: Int = 0,
    val usbProductId: Int = 0,
    val bluetoothAddress: String? = null,
) {
    companion object {
        private fun parseId(value: Any?): Int {
            if (value == null) return 0
            if (value is Number) return value.toInt()
            val s = value.toString().trim()
            if (s.isEmpty()) return 0
            return try {
                if (s.startsWith("0x", ignoreCase = true) || s.startsWith("0X")) {
                    s.substring(2).toIntOrNull(16) ?: 0
                } else {
                    s.toIntOrNull() ?: 0
                }
            } catch (_: Exception) { 0 }
        }

        fun fromJson(o: JSONObject): PrinterCfgDto = PrinterCfgDto(
            id = o.optString("id"),
            name = o.optString("name"),
            connectionType = o.optString("connectionType", "lan"),
            ipAddress = o.optString("ipAddress").takeIf { it.isNotBlank() },
            lanPort = o.optInt("lanPort", 9100),
            paperSize = o.optString("paperSize").takeIf { it.isNotBlank() },
            usbLabel = o.optString("usbLabel").takeIf { it.isNotBlank() },
            charset = o.optString("charset").takeIf { it.isNotBlank() },
            kanjiEnlarge = o.optString("kanjiEnlarge").takeIf { it.isNotBlank() },
            usbVendorId = parseId(o.opt("usbVendorId")),
            usbProductId = parseId(o.opt("usbProductId")),
            bluetoothAddress = o.optString("bluetoothAddress").takeIf { it.isNotBlank() }
                ?: o.optString("bluetoothName").takeIf { it.isNotBlank() },
        )

        fun resolve(printers: JSONArray?, job: PrintJobDto): PrinterCfgDto? {
            if (printers == null) return null
            val list = (0 until printers.length()).map { printers.getJSONObject(it) }.map { fromJson(it) }
            return list.firstOrNull { it.id == job.printerId }
                ?: list.firstOrNull { it.name == job.printerName }
        }
    }
}
