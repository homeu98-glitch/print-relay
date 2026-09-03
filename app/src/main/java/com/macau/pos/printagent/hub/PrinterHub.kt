package com.macau.pos.printagent.hub

import android.content.Context
import com.macau.pos.printagent.data.DeviceStore
import com.macau.pos.printagent.model.PrinterDevice
import com.macau.pos.printagent.model.PrinterService
import com.macau.pos.printagent.net.EscPosPrinter
import com.macau.pos.printagent.net.LanScanner
import com.macau.pos.printagent.net.ScanHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PrintLog(val text: String, val warn: Boolean)

data class PrintJobResult(
    val printed: Int,
    val total: Int,
    val logs: List<PrintLog>,
) {
    val ok: Boolean get() = printed > 0 && printed == total
}

class PrinterHub private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = DeviceStore(app)
    private val scanner = LanScanner(app)
    private val printer = EscPosPrinter()
    private val devices = LinkedHashMap<String, PrinterDevice>()
    private val lock = Any()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var listening: Boolean = false

    @Volatile
    var scanRunning: Boolean = false
        private set

    @Volatile
    var scanMessage: String = "就緒"
        private set

    @Volatile
    var webCommandListener: WebCommandListener? = null

    fun interface WebCommandListener {
        fun onWebCommand(serviceId: String, serviceLabel: String, message: String, printed: Int)
    }

    init {
        synchronized(lock) { devices.putAll(store.load()) }
    }

    fun localIp(): String = scanner.detectLocalIpv4().orEmpty()

    fun subnetPrefix(): String = scanner.detectSubnetPrefix().orEmpty()

    fun requestScan(prefix: String, identify: Boolean): Boolean {
        val p = prefix.trim()
        if (!p.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}"""))) return false
        if (scanRunning) return false
        scanRunning = true
        scanMessage = "準備掃描…"
        scanScope.launch {
            try {
                val hits = scanner.scanSubnet(p) { prog ->
                    scanMessage = "掃描中 ${prog.checked}/${prog.total}｜發現 ${prog.found}"
                }
                var identifyOk = 0
                hits.forEach { hit ->
                    mergeHit(hit)
                    if (identify && 9100 in hit.openPorts) {
                        val body = "IP: ${hit.ip}\nMAC: ${hit.mac ?: "(n/a)"}\nPorts: ${hit.openPorts}"
                        if (printIdentify(hit.ip, body)) identifyOk++
                    }
                }
                save()
                scanMessage = "掃描完成：發現 ${hits.size} 台｜識別列印 $identifyOk"
            } catch (e: Exception) {
                scanMessage = "掃描失敗：${e.message ?: "error"}"
            } finally {
                scanRunning = false
            }
        }
        return true
    }

    fun addManualBlocking(ip: String, name: String, serviceId: String) {
        val trimmed = ip.trim()
        val service = PrinterService.fromId(serviceId.ifBlank { null })
        val hit = scanner.probeIp(trimmed)
        if (hit == null) {
            putManual(trimmed, name, service)
        } else {
            mergeHit(hit, name.ifBlank { null }, service)
            save()
        }
    }

    fun hubUrl(): String {
        val ip = localIp()
        return if (ip.isBlank()) "" else "http://$ip:$PORT"
    }

    fun snapshot(): List<PrinterDevice> = synchronized(lock) { devices.values.toList() }

    fun devicesJson(): JSONArray {
        val arr = JSONArray()
        snapshot().sortedByDescending { it.lastSeen }.forEach { d ->
            arr.put(deviceJson(d))
        }
        return arr
    }

    fun save() {
        synchronized(lock) { store.save(devices) }
    }

    fun clearAll() {
        synchronized(lock) {
            devices.clear()
            store.clear()
        }
    }

    fun removeDevice(key: String) {
        synchronized(lock) { devices.remove(key) }
        save()
    }

    fun assignService(key: String, serviceId: String) {
        synchronized(lock) {
            val d = devices[key] ?: return
            devices[key] = d.copy(
                service = PrinterService.fromId(serviceId.ifBlank { null }),
                lastSeen = System.currentTimeMillis(),
            )
        }
        save()
    }

    fun putManual(ip: String, name: String, service: PrinterService?) {
        val key = "ip:$ip"
        synchronized(lock) {
            devices[key] = PrinterDevice(
                key = key,
                name = name.ifBlank { "手動-$ip" },
                ip = ip,
                mac = null,
                openPorts = emptyList(),
                service = service,
            )
        }
        save()
    }

    fun mergeHit(
        hit: ScanHit,
        preferredName: String? = null,
        serviceOverride: PrinterService? = null,
    ) {
        val mac = hit.mac?.uppercase()
        val key = mac?.let { "mac:$it" } ?: "ip:${hit.ip}"
        synchronized(lock) {
            val oldByIp = devices["ip:${hit.ip}"]
            val existing = devices[key] ?: oldByIp
            if (oldByIp != null && key.startsWith("mac:")) devices.remove("ip:${hit.ip}")
            val name = preferredName
                ?: existing?.name
                ?: hit.hostname
                ?: when {
                    9100 in hit.openPorts -> "Raw9100-${hit.ip.substringAfterLast('.')}"
                    631 in hit.openPorts -> "IPP-${hit.ip.substringAfterLast('.')}"
                    else -> "HTTP-${hit.ip.substringAfterLast('.')}"
                }
            devices[key] = PrinterDevice(
                key = key,
                name = name,
                ip = hit.ip,
                mac = mac ?: existing?.mac,
                openPorts = hit.openPorts,
                service = serviceOverride ?: existing?.service,
                lastSeen = System.currentTimeMillis(),
            )
        }
    }

    suspend fun printIdentify(ip: String, body: String): Boolean =
        printer.printTextTicket(ip, "DEVICE ID", body).isSuccess

    suspend fun printService(serviceId: String, message: String): PrintJobResult {
        val service = PrinterService.fromId(serviceId)
            ?: return PrintJobResult(0, 0, listOf(PrintLog("未知服務 $serviceId", true)))
        val targets = snapshot().filter { it.service == service }
        if (targets.isEmpty()) {
            return PrintJobResult(
                0, 0,
                listOf(PrintLog("沒有綁定「${service.label}」的設備", true)),
            )
        }
        return printTargets(targets, service.label, message)
    }

    suspend fun printToIp(ip: String, title: String, message: String): PrintJobResult {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            return PrintJobResult(0, 0, listOf(PrintLog("缺少 ip", true)))
        }
        val fake = PrinterDevice(
            key = "ip:$trimmed",
            name = title.ifBlank { trimmed },
            ip = trimmed,
            mac = null,
            openPorts = listOf(9100),
            service = null,
        )
        return printTargets(listOf(fake), title.ifBlank { "POS" }, message)
    }

    private suspend fun printTargets(
        targets: List<PrinterDevice>,
        title: String,
        message: String,
    ): PrintJobResult = withContext(Dispatchers.IO) {
        val logs = ArrayList<PrintLog>()
        var ok = 0
        targets.forEach { d ->
            if (!d.canRawPrint) {
                logs += PrintLog("略過 ${d.ip}：無 9100", true)
                return@forEach
            }
            val body = "Service: $title\nIP: ${d.ip}\nMAC: ${d.mac ?: "-"}\n\n$message\n"
            val r = printer.printTextTicket(d.ip, title, body)
            if (r.isSuccess) {
                ok++
                logs += PrintLog("已列印 → $title ${d.ip}", false)
            } else {
                val err = r.exceptionOrNull()?.message ?: "error"
                logs += PrintLog("列印失敗 ${d.ip}: $err", true)
            }
        }
        logs += PrintLog("「$title」成功 $ok / ${targets.size}", false)
        PrintJobResult(ok, targets.size, logs)
    }

    companion object {
        const val PORT = 8787

        @Volatile
        private var instance: PrinterHub? = null

        fun get(context: Context): PrinterHub {
            return instance ?: synchronized(this) {
                instance ?: PrinterHub(context.applicationContext).also { instance = it }
            }
        }

        fun deviceJson(d: PrinterDevice): JSONObject = JSONObject()
            .put("key", d.key)
            .put("name", d.name)
            .put("ip", d.ip)
            .put("mac", d.mac ?: "")
            .put("openPorts", JSONArray(d.openPorts))
            .put("service", d.service?.id ?: "")
            .put("canRawPrint", d.canRawPrint)
    }
}
