package com.macau.pos.printagent.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScanHit(
    val ip: String,
    val mac: String?,
    val openPorts: List<Int>,
    val hostname: String?,
)

data class ScanProgress(
    val checked: Int,
    val total: Int,
    val found: Int,
    val subnetPrefix: String,
)

class LanScanner(private val context: Context) {

    fun detectLocalIpv4(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val lp: LinkProperties? = network?.let { cm.getLinkProperties(it) }
            val ipv4 = lp?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ipv4?.hostAddress?.takeIf { it.split(".").size == 4 }?.let { return it }
        } catch (_: Exception) {
        }

        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wm.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) null
            else {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                "${bytes[0].toInt() and 0xff}.${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}.${bytes[3].toInt() and 0xff}"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun detectSubnetPrefix(): String? {
        val ip = detectLocalIpv4() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    suspend fun scanSubnet(
        prefix: String,
        ports: List<Int> = DEFAULT_PORTS,
        timeoutMs: Int = 250,
        onProgress: (ScanProgress) -> Unit = {},
    ): List<ScanHit> = withContext(Dispatchers.IO) {
        val hostIds = (1..254).toList()
        val total = hostIds.size
        var checked = 0
        val hits = mutableListOf<ScanHit>()
        val lock = Any()
        val semaphore = Semaphore(64)

        coroutineScope {
            hostIds.map { host ->
                async {
                    semaphore.withPermit {
                        val ip = "$prefix.$host"
                        val open = ports.filter { isPortOpen(ip, it, timeoutMs) }
                        val result = if (open.isNotEmpty()) {
                            val mac = lookupMac(ip)
                            val hostName = runCatching {
                                java.net.InetAddress.getByName(ip).canonicalHostName
                                    ?.takeIf { it != ip }
                            }.getOrNull()
                            ScanHit(ip, mac, open, hostName)
                        } else null

                        synchronized(lock) {
                            checked++
                            if (result != null) hits += result
                            onProgress(ScanProgress(checked, total, hits.size, prefix))
                        }
                        result
                    }
                }
            }.awaitAll()
        }

        // ARP 表在連線後可能才有 MAC，再補一次
        hits.map { hit ->
            if (hit.mac.isNullOrBlank()) hit.copy(mac = lookupMac(hit.ip)) else hit
        }.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
    }

    fun probeIp(ip: String, ports: List<Int> = DEFAULT_PORTS, timeoutMs: Int = 800): ScanHit? {
        val open = ports.filter { isPortOpen(ip, it, timeoutMs) }
        if (open.isEmpty()) return null
        return ScanHit(ip, lookupMac(ip), open, null)
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 讀系統 ARP；Android 10+ 常為空，有就用。 */
    fun lookupMac(ip: String): String? {
        return try {
            val file = File("/proc/net/arp")
            if (!file.canRead()) return null
            BufferedReader(FileReader(file)).use { reader ->
                reader.readLine() // header
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[0] == ip) {
                        val mac = parts[3].uppercase()
                        if (mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) &&
                            mac != "00:00:00:00:00:00"
                        ) {
                            return mac
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 9100=raw ESC/POS, 631=IPP, 80=許多印表機管理頁 */
        val DEFAULT_PORTS = listOf(9100, 631, 80)
    }
}
