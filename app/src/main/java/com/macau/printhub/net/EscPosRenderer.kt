package com.macau.printhub.net

import com.macau.printhub.model.PrintJobDto
import com.macau.printhub.model.PrinterCfgDto
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ESC/POS renderer — ported from print-agent-android (same contract as web escpos.mjs).
 * Produces ESC/POS byte[] for raw TCP socket or vendor AAR sendData.
 */
object EscPosRenderer {

    private const val ESC = 0x1B
    private const val FS = 0x1C
    private const val GS = 0x1D
    private const val LF = 0x0A

    private val SIZE_BYTE = mapOf("s" to 0x00, "m" to 0x20, "l" to 0x30)
    private val KANJI_SIZE_BYTE = mapOf("s" to 0x00, "m" to 0x20, "l" to 0x30)

    private fun hasCJK(s: String): Boolean {
        for (c in s) {
            val cp = c.code
            if ((cp in 0x3400..0x9FFF) || (cp in 0xF900..0xFAFF) ||
                (cp in 0xFF00..0xFFEF) || (cp in 0x3000..0x303F)) return true
        }
        return false
    }

    private const val RECEIPT_PAPER_COLUMNS = 48
    private const val PAPER_COLUMNS_58MM = 32

    private fun paperColumns(printer: PrinterCfgDto?): Int =
        if ((printer?.paperSize ?: "").contains("58")) PAPER_COLUMNS_58MM else RECEIPT_PAPER_COLUMNS

    private fun isWideChar(cp: Int): Boolean =
        (cp in 0x1100..0x115F) ||
            (cp in 0x2E80..0xA4CF) ||
            (cp in 0xAC00..0xD7A3) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0xFE30..0xFE4F) ||
            (cp in 0xFF00..0xFF60) ||
            (cp in 0xFFE0..0xFFE6) ||
            (cp in 0x20000..0x3FFFD)

    private fun displayWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += if (isWideChar(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return w
    }

    private fun twoColumn(left: String, right: String, cols: Int = RECEIPT_PAPER_COLUMNS): String {
        val pad = cols - displayWidth(left) - displayWidth(right)
        return if (pad >= 1) left + " ".repeat(pad) + right else "$left  $right"
    }

    private data class SpecParts(val label: String, val price: String?)

    private fun splitSpecLine(s: String): SpecParts {
        val m = Regex("^(.*?)\\s+(-?\\$\\d+|-\\d+)$").find(s)
            ?: return SpecParts(s, null)
        val label = m.groupValues[1].trimEnd()
        val tail = m.groupValues[2]
        return SpecParts(label, if (tail.startsWith("$")) tail else " $tail")
    }

    private fun num(v: Double): String =
        "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun discountLine(item: PrintJobDto.Item, prefix: String, cols: Int): String {
        val rate = item.discountRate ?: return ""
        val saving = item.savingAmount ?: return ""
        val rateText = if (rate % 1.0 == 0.0) {
            "${rate.toLong()}%"
        } else {
            "%.1f".format(Locale.US, rate) + "%"
        }
        val original = item.originalUnitPrice?.let { "（原價 \$${num(it)}）" } ?: ""
        val left = "${prefix}折扣率 $rateText$original"
        return twoColumn(left, "折讓 \$${Math.round(saving)}", cols)
    }

    private const val QR_QUIET_MODULES = 4

    private fun qrRaster(buf: Buf, qr: PrintJobDto.QrPayload, align: String) {
        val size = qr.size
        val bits = qr.bits
        if (size <= 0 || bits.length < size * size) return
        val scale = (160 / (size + QR_QUIET_MODULES * 2)).coerceIn(2, 6)
        val total = (size + QR_QUIET_MODULES * 2) * scale
        val rowBytes = (total + 7) / 8
        val data = ByteArray(rowBytes * total)
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (bits[r * size + c] != '1') continue
                val y0 = (r + QR_QUIET_MODULES) * scale
                val x0 = (c + QR_QUIET_MODULES) * scale
                for (dy in 0 until scale) {
                    val y = y0 + dy
                    for (dx in 0 until scale) {
                        val x = x0 + dx
                        val idx = y * rowBytes + (x shr 3)
                        data[idx] = (data[idx].toInt() or (0x80 shr (x and 7))).toByte()
                    }
                }
            }
        }
        buf.resetMagnify()
        buf.align(align)
        buf.cmd(LF)
        buf.cmd(GS, 0x76, 0x30, 0x00)
        buf.cmd(rowBytes and 0xFF, (rowBytes shr 8) and 0xFF)
        buf.cmd(total and 0xFF, (total shr 8) and 0xFF)
        buf.bytes(data)
        buf.cmd(LF)
        buf.cmd(ESC, 0x33, 30)
    }

    private val SUPPORTED = setOf("gb18030", "gbk", "big5", "utf-8", "utf8")

    fun resolveCharset(name: String?): Charset {
        val n = name?.trim()?.lowercase()
        if (!n.isNullOrBlank() && n in SUPPORTED) {
            return runCatching { Charset.forName(n) }.getOrElse { defaultCharset() }
        }
        return defaultCharset()
    }

    private fun defaultCharset(): Charset =
        runCatching { Charset.forName("GB18030") }.getOrElse { Charset.forName("UTF-8") }

    private fun encode(text: String, cs: Charset): ByteArray =
        text.replace("\r\n", "\n").replace("\r", "\n").toByteArray(cs)

    private class Buf(private val cs: Charset, kanjiEnlarge: String? = null) {
        private val out = java.io.ByteArrayOutputStream()
        private val kanjiCmd: Int = if (kanjiEnlarge == "FS!") FS else GS
        private var curSize: String = "s"
        fun cmd(vararg b: Int) = apply { b.forEach { out.write(it) } }
        fun str(s: String) = apply {
            cmd(ESC, 0x33, if (curSize == "l") 60 else 30)
            val cjk = hasCJK(s)
            if (cjk) cmd(FS, 0x26)
            if (cjk) cmd(kanjiCmd, 0x21, KANJI_SIZE_BYTE[curSize] ?: 0x00)
            out.write(encode(s, cs))
            if (cjk) cmd(FS, 0x2E)
        }
        fun line(s: String = "", inverse: Boolean = false) = apply {
            cmd(ESC, 0x33, if (curSize == "l") 60 else 30)
            val cjk = hasCJK(s)
            if (cjk) cmd(FS, 0x26)
            if (cjk) cmd(kanjiCmd, 0x21, KANJI_SIZE_BYTE[curSize] ?: 0x00)
            if (inverse) cmd(ESC, 0x7B, 0x01)
            out.write(encode(s, cs))
            if (inverse) cmd(ESC, 0x7B, 0x00)
            out.write(LF)
            if (cjk) cmd(FS, 0x2E)
        }
        fun bytes(ba: ByteArray) = apply { out.write(ba) }
        fun toBytes(): ByteArray = out.toByteArray()

        fun style(size: String, bold: Boolean) = apply {
            curSize = size
            cmd(ESC, 0x21, SIZE_BYTE[size] ?: 0x00)
            cmd(ESC, 0x45, if (bold) 1 else 0)
        }
        fun align(a: String) = apply {
            val code = when (a) { "center" -> 1; "right" -> 2; else -> 0 }
            cmd(ESC, 0x61, code)
        }
        fun reset() = apply {
            curSize = "s"
            cmd(ESC, 0x45, 0x00)
            cmd(ESC, 0x61, 0x00)
        }

        fun resetMagnify() = apply {
            curSize = "s"
            cmd(GS, 0x21, 0x00)
            cmd(ESC, 0x21, 0x00)
            cmd(ESC, 0x33, 30)
        }
    }

    private fun separator(width: Int, cs: Charset): ByteArray =
        encode("-".repeat(width) + "\n", cs)

    private fun ticketTypeLabel(ticketType: String?): String = when (ticketType) {
        "addon" -> "【加單】"
        "void" -> "【退菜】"
        else -> "【廚房單】"
    }

    fun renderKitchenTicket(job: PrintJobDto, printer: PrinterCfgDto?, storeName: String?): ByteArray {
        val width = if ((printer?.paperSize ?: "").contains("58")) 32 else 42
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)
        buf.cmd(ESC, 0x61, 0x01)
        buf.line(storeName ?: "Macau POS")
        buf.line(ticketTypeLabel(job.ticketType))
        buf.cmd(ESC, 0x61, 0x00)
        buf.bytes(separator(width, cs))

        job.orderNo?.takeIf { it.isNotBlank() }?.let { buf.line("單號: $it") }
        job.tableName?.takeIf { it.isNotBlank() }?.let { buf.line("桌台: $it") }
        job.printerName?.takeIf { it.isNotBlank() }?.let { buf.line("打印機: $it") }
        buf.bytes(separator(width, cs))

        for (item in job.items.orEmpty()) {
            val qty = if (item.quantity <= 0) 1 else item.quantity
            buf.line("${item.name}  x$qty")
            for (spec in item.specs.orEmpty()) buf.line("  · $spec")
            item.note?.takeIf { it.isNotBlank() }?.let { buf.line("  注：$it") }
        }

        buf.bytes(separator(width, cs))
        val ts = runCatching {
            SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault())
                .format(Date(job.createdAt ?: System.currentTimeMillis()))
        }.getOrElse { Date().toString() }
        buf.line(ts)
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }

    fun renderTestPage(printer: PrinterCfgDto?, storeName: String?): ByteArray {
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)
        buf.cmd(ESC, 0x61, 0x01)
        buf.line(storeName ?: "Macau POS")
        buf.line("打印測試頁")
        buf.cmd(ESC, 0x61, 0x00)
        buf.bytes(separator(32, cs))
        buf.line("打印機: ${printer?.name ?: "-"}")
        val conn = printer?.connectionType ?: "-"
        buf.line("連接: $conn")
        if (conn == "lan") {
            buf.line("IP: ${printer?.ipAddress ?: "-"}:${printer?.lanPort ?: 9100}")
        } else {
            buf.line("USB: ${printer?.usbLabel ?: "-"}")
        }
        buf.line("Charset: ${cs.name()}")
        buf.bytes(separator(32, cs))
        buf.line("若看到此行，LAN 橋接正常。")
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }

    fun renderReceiptTicket(
        job: PrintJobDto,
        printer: PrinterCfgDto?,
        storeName: String?,
        paymentMethod: String?,
        total: Double?,
    ): ByteArray {
        val width = if ((printer?.paperSize ?: "").contains("58")) 32 else 42
        val cs = resolveCharset(printer?.charset)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)
        buf.cmd(ESC, 0x61, 0x01)
        buf.line(storeName ?: "Macau POS")
        buf.line("收據")
        buf.cmd(ESC, 0x61, 0x00)
        buf.bytes(separator(width, cs))
        job.orderNo?.takeIf { it.isNotBlank() }?.let { buf.line("單號: $it") }
        job.tableName?.takeIf { it.isNotBlank() }?.let { buf.line("桌台: $it") }
        buf.bytes(separator(width, cs))
        for (item in job.items.orEmpty()) {
            buf.line("${item.name} x${item.quantity}")
        }
        buf.bytes(separator(width, cs))
        total?.let { buf.line("總計: MOP ${"%.0f".format(it)}") }
        paymentMethod?.takeIf { it.isNotBlank() }?.let { buf.line("支付: $it") }
        val ts = SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault()).format(Date())
        buf.line(ts)
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }

    fun renderTemplateTicket(job: PrintJobDto, printer: PrinterCfgDto?): ByteArray {
        val template = job.template
            ?: return renderKitchenTicket(job, printer, null)
        val content = job.content ?: emptyMap()
        val items = job.items.orEmpty()
        val cs = resolveCharset(printer?.charset)
        val cols = paperColumns(printer)
        val buf = Buf(cs, printer?.kanjiEnlarge)
        buf.cmd(ESC, 0x40)

        val titleText = when (template.kind) {
            "receipt" -> "＊＊＊ 收據 ＊＊＊"
            "kitchen" -> "＊＊＊ 廚房 ＊＊＊"
            else -> ""
        }
        if (titleText.isNotEmpty()) {
            buf.align("center"); buf.style("m", true); buf.line(titleText)
            buf.reset()
        }

        val divider = "-".repeat(cols)
        for (b in template.blocks) {
            if (!b.visible) continue
            if (b.id == "items") {
                buf.line(divider)
                val layout = b.layout
                val isReceipt = template.kind == "receipt"
                items.forEachIndexed { i, it ->
                    val qty = if (it.quantity <= 0) 1 else it.quantity
                    val qtyText = "x$qty"
                    val priceText = if (isReceipt && it.price != null && it.price > 0.0) "  \$${num(it.price)}" else ""
                    val hasDiscount =
                        isReceipt &&
                            it.discountRate != null && it.discountRate > 0.0 && it.discountRate < 100.0 &&
                            it.savingAmount != null && it.savingAmount > 0.0

                    if (layout == "card") {
                        val nameLine = if (isReceipt) {
                            twoColumn("${i + 1}. ${it.name}", "$qtyText$priceText", cols)
                        } else {
                            "${i + 1}. ${it.name}  $qtyText"
                        }
                        buf.align(b.align); buf.style(b.size, b.bold); buf.line(nameLine)
                        buf.line(divider)
                        for (s in it.specs.orEmpty()) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            val sp = splitSpecLine(s)
                            buf.line(
                                if (sp.price != null) twoColumn("  ${sp.label}", sp.price, cols)
                                else "  ${sp.label}"
                            )
                        }
                        it.note?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            buf.align(b.align); buf.style(b.subSize, false); buf.line("  注：$n")
                        }
                        if (hasDiscount) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            buf.line(discountLine(it, prefix = "  ", cols = cols), inverse = true)
                        }
                        if (i < items.size - 1) {
                            buf.align(b.align); buf.style("s", false); buf.line("")
                        }
                    } else {
                        val nameLine = if (isReceipt) {
                            twoColumn(it.name, "$qtyText$priceText", cols)
                        } else {
                            "${it.name}  $qtyText"
                        }
                        buf.align(b.align); buf.style(b.size, b.bold); buf.line(nameLine)
                        for (s in it.specs.orEmpty()) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            val sp = splitSpecLine(s)
                            buf.line(
                                if (sp.price != null) twoColumn("  · ${sp.label}", sp.price, cols)
                                else "  · ${sp.label}"
                            )
                        }
                        it.note?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            buf.align(b.align); buf.style(b.subSize, false); buf.line("  注：$n")
                        }
                        if (hasDiscount) {
                            buf.align(b.align); buf.style(b.subSize, false)
                            buf.line(discountLine(it, prefix = "  · ", cols = cols), inverse = true)
                        }
                    }
                }
                buf.line(divider)
            } else if (b.id == "qr_code") {
                job.qr?.let { qrRaster(buf, it, b.align.ifBlank { "center" }) }
            } else {
                val text = content[b.id] ?: continue
                buf.align(b.align); buf.style(b.size, b.bold); buf.line(text)
            }
        }
        buf.reset()
        buf.cmd(LF, LF, LF)
        buf.cmd(GS, 0x56, 0x00)
        return buf.toBytes()
    }
}
