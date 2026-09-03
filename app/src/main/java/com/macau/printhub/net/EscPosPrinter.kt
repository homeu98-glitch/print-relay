package com.macau.printhub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

/**
 * Raw TCP socket printer — direct ESC/POS bytes to LAN printer at port 9100.
 *
 * 呢條係 Print Hub 嘅首選通道：純 LAN 打印機其實唔使經廠商 SDK，
 * 直出 raw socket 有硬性 timeout，永遠唔會「靜默卡死」。
 * SDK（SdkPrinter）只作為 raw 失敗時嘅 fallback。
 */
object EscPosPrinter {

    const val DEFAULT_TIMEOUT_MS = 5000

    suspend fun printRaw(
        ip: String,
        port: Int = 9100,
        payload: ByteArray,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
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
            // 注意：呢度唔好用 runCatching，佢做咗 lambda 最後一個 expression 會令返回型變 Result<Result<Unit>>
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.onFailure { e ->
            // 只係記低原文，實際拋出嘅會由外面處理（唔好用 recoverCatching，會變成 Result<Result<Unit>>）
            android.util.Log.w("EscPosPrinter", "直連 $ip:$port 失敗：${e.message}")
        }
    }

    /**
     * 快速探測：只連唔印。用嚟判斷「部機到底有冇聽緊呢個 port」。
     * 比起撳測試頁等成 5 秒，呢個快好多，適合掃描後自動體檢。
     */
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

    suspend fun printTextTicket(
        ip: String,
        title: String,
        body: String,
        footer: String = "Macau Print Hub",
    ): Result<Unit> {
        val bytes = buildTicket(title, body, footer)
        return printRaw(ip, 9100, bytes)
    }

    fun buildTicket(title: String, body: String, footer: String): ByteArray {
        val out = ArrayList<Byte>()
        fun add(vararg b: Int) = b.forEach { out.add(it.toByte()) }
        fun addStr(s: String) {
            out.addAll(s.toByteArray(Charset.forName("UTF-8")).toList())
        }

        add(0x1B, 0x40)
        add(0x1B, 0x61, 0x01)
        add(0x1D, 0x21, 0x11)
        addStr(title)
        add(0x0A)
        add(0x1D, 0x21, 0x00)
        add(0x1B, 0x61, 0x00)
        addStr("--------------------------------\n")
        addStr(body)
        if (!body.endsWith("\n")) add(0x0A)
        addStr("--------------------------------\n")
        add(0x1B, 0x61, 0x01)
        addStr(footer)
        add(0x0A, 0x0A, 0x0A)
        add(0x1D, 0x56, 0x00)
        return out.toByteArray()
    }
}
