package com.macau.pos.printagent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 我哋 web POS 經 PosNative.printJob(json) 落嚟嘅 PrintJob，對應
 * src/lib/types.ts 嘅 PrintJob。只擷取打印需要嘅欄位。
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
    /** 模板靜態內容：block id → 文字（對應 web PrintJob.content）。 */
    val content: Map<String, String>?,
    /** 模板快照（對應 web PrintJob.template）：消費 size/bold/align 做 ESC 字型。 */
    val template: TemplateDto?,
    val createdAt: Long?,
    /** 收據二維碼原始網址（淨供除錯／續印；實際出紙用下面 qr 點陣）。 */
    val qrUrl: String?,
    /** 收據二維碼點陣（POS 端 encode 好，對應 web QrPayload）。null = 唔印。 */
    val qr: QrPayload?,
) {
    data class Item(
        val name: String,
        val quantity: Int,
        val specs: List<String>?,
        val note: String?,
        /**
         * 折後單價 × 數量（web PrintItemLine.price）。
         *
         * ⚠️ 呢個欄位係「收據印唔出價錢」嘅根因：web 一早就算好並傳落嚟，
         * 但 Android / Companion 兩個 renderer 以前完全冇讀 → 出紙淨得品名 + 數量。
         * 廚房單唔帶（見 docs/95 §1）。
         */
        val price: Double? = null,
        /** 折扣率百分比（80 = 8 折 / 100 = 冇折扣）。 */
        val discountRate: Double? = null,
        /** 折扣前單價。 */
        val originalUnitPrice: Double? = null,
        /** 折後單價。 */
        val discountedUnitPrice: Double? = null,
        /** 折讓金額（(原價 - 折後價) × 數量）。 */
        val savingAmount: Double? = null,
    )

    /**
     * 收據二維碼點陣（對應 web src/lib/types.ts 嘅 QrPayload）。
     * POS 端 encode 一次，三個 repo 共用同一個矩陣 → 出紙 == 螢幕預覽（docs/95 §2）。
     */
    data class QrPayload(
        /** 邊長（modules），未計 quiet zone。 */
        val size: Int,
        /** 逐行 bit 字串，長度 = size × size；'1' = 黑點。 */
        val bits: String,
    ) {
        companion object {
            /** 欄位唔齊 / 點陣長度唔夠 → 當冇（唔印，唔好印半格亂碼）。 */
            fun fromJson(o: JSONObject?): QrPayload? {
                if (o == null) return null
                val size = o.optInt("size", 0)
                val bits = o.optString("bits", "")
                if (size <= 0 || bits.length < size * size) return null
                return QrPayload(size = size, bits = bits)
            }
        }
    }

    /** 模板快照入面嘅單一 block（對應 web EscPosBlockStyle）。 */
    data class Block(
        val id: String,
        val visible: Boolean = true,
        val size: String = "s",
        val bold: Boolean = false,
        val align: String = "left",
        val subSize: String = "s",
        val layout: String = "card",
    )

    /** 模板快照（對應 web EscPosTemplateSnapshot）。 */
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

        /**
         * 雲端中繼用：直接食 `pos_print_jobs` **DB row**（docs/96 §5.1）。
         *
         * 同 fromJson 嘅差別只有欄位名 —— DB 係 snake_case（PostgREST 直接出嚟），
         * web payload 係 camelCase。兩個 camelCase fallback 係為咗將來 server 端
         * 萬一用 `select(..., 別名)` 都唔會全 blank。
         *
         * 既有欄位名對照（migration 0011 + 0015 實讀）：
         * id / store_id / order_id / order_no / table_name / ticket_type /
         * printer_group / printer_name / printer_id / items / template / content /
         * status / created_at
         */
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

        /** 依次試幾個 key，第一個非空白就用（snake_case 優先，camelCase 兜底）。 */
        private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                val v = o.optString(k)
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        /**
         * 讀 optional 數字：欄位唔喺度 / 係 null / 唔係數字 → null。
         * ⚠️ 唔可以用 optDouble(key, 0.0)：咁會令「冇價錢」同「價錢 0」撈亂，
         * 而且舊版 web payload 冇呢啲欄位時會變成 0 印出「$0」。
         */
        private fun optDouble(o: JSONObject, key: String): Double? {
            if (!o.has(key) || o.isNull(key)) return null
            return runCatching { o.getDouble(key) }.getOrNull()
        }

        /** 接受 ISO 字串或 epoch millis。 */
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

/**
 * 對應 web DevicePrinterConfig 嘅子集：render 同 socket 都用得到。
 * charset 係我哋新增、每台可配嘅 ESC/POS 中文編碼（預設 GB18030）。
 */
data class PrinterCfgDto(
    val id: String,
    val name: String,
    val connectionType: String,
    val ipAddress: String?,
    val lanPort: Int,
    val paperSize: String?,
    val usbLabel: String?,
    val charset: String?,
    /** 中文（Kanji）倍大指令：商頌 POS-80 等機 "GS!"；標準 ESC/POS 機 "FS!"。缺省 GS!（接上就用安全值）。 */
    val kanjiEnlarge: String? = null,
    val usbVendorId: Int = 0,
    val usbProductId: Int = 0,
    val bluetoothAddress: String? = null,
) {
    companion object {
        /**
         * 解析 USB VID/PID：web 可能送 number、decimal string、或 hex string（"0x04B8"）。
         * 統一轉成 Int，避免 web 用 hex 顯示但 Android optInt 解唔到 → 變 0。
         */
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

        /** 從 deviceConfig.printers JSON 陣列搵匹配 printer（先 id 後 name）。 */
        fun resolve(printers: JSONArray?, job: PrintJobDto): PrinterCfgDto? {
            if (printers == null) return null
            val list = (0 until printers.length()).map { printers.getJSONObject(it) }.map { fromJson(it) }
            return list.firstOrNull { it.id == job.printerId }
                ?: list.firstOrNull { it.name == job.printerName }
        }
    }
}
