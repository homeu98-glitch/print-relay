package com.macau.pos.printagent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

class EscPosPrinter {

    suspend fun printRaw(
        ip: String,
        port: Int = 9100,
        payload: ByteArray,
        timeoutMs: Int = RAW_TIMEOUT_MS,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ip.isBlank()) throw IllegalArgumentException("打印機 IP 為空")
            if (port <= 0 || port > 65535) throw IllegalArgumentException("打印機 port 無效：$port")

            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                // connect timeout：對方 drop SYN（冇 RST）時，呢個 timeout 係唯一嘅保命符
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val out: OutputStream = socket.getOutputStream()
                out.write(payload)
                out.flush()
            }
            // 寫完稍等一陣，畀對端有機會 RST（寫完即刻 close 有機會吞咗錯誤）
            // 注意：唔好用 runCatching，佢做咗 lambda 最後一個 expression 會令返回型變 Result<Result<Unit>>
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** 快速探測：只連唔印，用嚟判斷「部機到底有冇聽緊呢個 port」。 */
    suspend fun probe(
        ip: String,
        port: Int = 9100,
        timeoutMs: Int = 1200,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
        }
    }

    companion object {
        const val RAW_TIMEOUT_MS = 5000
    }

    suspend fun printTextTicket(
        ip: String,
        title: String,
        body: String,
        footer: String = "POS Printer Demo",
    ): Result<Unit> {
        val bytes = buildTicket(title, body, footer)
        return printRaw(ip, 9100, bytes)
    }

    fun buildTicket(title: String, body: String, footer: String): ByteArray {
        val out = ArrayList<Byte>()
        fun add(vararg b: Int) = b.forEach { out.add(it.toByte()) }
        fun addStr(s: String) {
            // 多數收據機預設碼頁對中文支援不一；同時送 UTF-8 與可讀 ASCII
            out.addAll(s.toByteArray(Charset.forName("UTF-8")).toList())
        }

        add(0x1B, 0x40) // init
        add(0x1B, 0x61, 0x01) // center
        add(0x1D, 0x21, 0x11) // double size
        addStr(title)
        add(0x0A)
        add(0x1D, 0x21, 0x00)
        add(0x1B, 0x61, 0x00) // left
        addStr("--------------------------------\n")
        addStr(body)
        if (!body.endsWith("\n")) add(0x0A)
        addStr("--------------------------------\n")
        add(0x1B, 0x61, 0x01)
        addStr(footer)
        add(0x0A, 0x0A, 0x0A)
        add(0x1D, 0x56, 0x00) // partial cut
        return out.toByteArray()
    }
}
