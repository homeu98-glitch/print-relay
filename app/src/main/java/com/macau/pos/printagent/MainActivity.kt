package com.macau.pos.printagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macau.pos.printagent.BuildConfig
import com.macau.pos.printagent.hub.PairQr
import com.macau.pos.printagent.hub.PrintHubService
import com.macau.pos.printagent.hub.PrinterHub
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import com.macau.pos.printagent.net.SdkPrinter
import com.macau.pos.printagent.net.LanScanner
import com.macau.pos.printagent.net.UsbKey
import com.macau.pos.printagent.relay.RelayActivity
import com.macau.pos.printagent.relay.RelayPrefs
import com.macau.pos.printagent.relay.RelayService
import com.macau.pos.printagent.relay.RelayState
import com.macau.pos.printagent.usb.UsbController
import com.macau.pos.printagent.bt.BtController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * POS 外殼：WebView 載入 Vercel POS（BuildConfig.POS_URL），注入 PosNative
 * JS bridge 俾 POS 直接 LAN 打印（無 mixed content、無 Tunnel、斷網照印）。
 *
 * 保留 app 內「打印機設定」畫面（assets/index.html）—— openPrinterSettings() 切過去。
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var scanner: LanScanner
    private lateinit var hub: PrinterHub
    private lateinit var usbController: UsbController
    private lateinit var btController: BtController

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 整個 launch 路徑用 try/catch 包住：任何非預期錯誤（WebView 初始化、
        // 非 Sunmi 機 class-load LinkageError 等）都唔可以令成個 app 彈出——
        // 改為顯示錯誤文字 + 記低 stack（tag PRINTAGENT_CRASH）等排查。
        try {
            setup()
        } catch (e: Throwable) {
            Log.e("PRINTAGENT_CRASH", "MainActivity.onCreate 失敗", e)
            CrashLog.write(this, "MainActivity.onCreate", e)
            showFatalError(e)
        }
    }

    private fun setup() {
        // docs/96：「呢部機專做中繼」→ 開機直接入中繼畫面，唔開 POS WebView。
        // 預設 false，現有當 POS 終端用嘅機行為完全唔變。
        val relayPrefs = RelayPrefs(this)
        if (relayPrefs.relayHome) {
            startActivity(Intent(this, RelayActivity::class.java))
            finish()
            return
        }
        // 已配對（即係做過中繼設定）先至起中繼服務，避免 POS 終端機白白食網絡同電。
        // runCatching：中繼服務起身失敗（例如非 Sunmi 機初始化擲錯）唔可以連累 MainActivity 彈出。
        if (relayPrefs.isPaired()) runCatching { RelayService.start(this) }

        hub = PrinterHub.get(this)
        scanner = LanScanner(this)
        usbController = UsbController(this) { evalJs(it) }
        btController = BtController(this) { evalJs(it) }
        usbController.register()
        // runCatching：LAN hub foreground service 起身失敗唔可以令 app 彈出。
        runCatching { PrintHubService.start(this) }

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // POS 喺 HTTPS；native 允許佢打 LAN HTTP（如需 HTTP hub 做 fallback）。
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "PosNative")
        loadPos()

        // 權限 request 一定要擺喺 WebView 建好**之後**：requestPermissions 有可能
        // **同步**回呼 onRequestPermissionsResult → callJs → evalJs，嗰陣 webView
        // 仲係 lateinit 未初始化 → UninitializedPropertyAccessException 彈 app
        // （見 crash.txt 17:23:46）。擺喺後面個 JS callback 先收得到。
        maybeRequestNotifyPermission()
        maybeRequestBtPermissions()

        hub.webCommandListener = PrinterHub.WebCommandListener { serviceId, label, message, printed ->
            evalJs(
                "onWebCommand(${JSONObject.quote(serviceId)}, ${JSONObject.quote(label)}, " +
                    "${JSONObject.quote(message)}, $printed)"
            )
        }
    }

    /** launch 失敗嘅兜底 UI：顯示 exception 訊息 + stack，等用家睇得到、logcat 都執得到。 */
    private fun showFatalError(e: Throwable) {
        val msg = buildString {
            append("啟動失敗（app 冇彈出，但 POS 未開到）\n\n")
            append(e.message ?: e.javaClass.name)
            append("\n\n完整 stack 已寫入：Android/data/com.macau.pos.printagent/files/crash.txt")
            append("\n（用手機連電腦跑：adb pull /sdcard/Android/data/com.macau.pos.printagent/files/crash.txt .）")
            append("\n\n")
            append(Log.getStackTraceString(e))
        }
        val tv = TextView(this)
        tv.text = msg
        tv.setTextIsSelectable(true)
        tv.setPadding(32, 32, 32, 32)
        tv.textSize = 12f
        setContentView(tv)
    }

    private fun loadPos() {
        val url = BuildConfig.POS_URL.trim().ifBlank { DEFAULT_POS_URL }
        webView.loadUrl(url)
    }

    /** 載 app 內打印機設定畫面（舊 index.html）。 */
    private fun loadPrinterSettings() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    /** 返回 POS：等設定 UI 透過 PosNative.backToPos() 呼叫。 */
    private fun backToPos() = loadPos()

    override fun onDestroy() {
        // setup() 有機會喺 lateinit 全部 assign 之前就掟嘢，呢度逐個 guard，
        // 唔可以 uninitialized access 再彈多次（掩蓋咗原本嘅 crash）。
        if (::hub.isInitialized) hub.webCommandListener = null
        if (::usbController.isInitialized) usbController.unregister()
        if (::btController.isInitialized) btController.unregister()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /** Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT runtime 權限先可以用藍牙打印。 */
    private fun maybeRequestBtPermissions() {
        if (Build.VERSION.SDK_INT < 31) return
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_BT_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            callJs("onBtPermissionResult", allGranted.toString())
        }
    }

    /**
     * PosNative bridge —— POS 透過 window.PosNative.* 呼叫。
     * 所有 @JavascriptInterface method 必須喺主綫程註冊嘅 class 入面。
     */
    private inner class Bridge {

        /** POS 落單後直接印。payload = { job, printer?, meta?, kind? }
         *  kind: "kitchen"（預設）/ "receipt" / "test"
         *  printer: DevicePrinterConfig 子集（id/name/connectionType/ipAddress/lanPort/paperSize/charset）
         *  呢個係主路：POS 帶齊 printer 資料，native 唔使靠 PrinterHub binding。 */
        @JavascriptInterface
        fun printJob(payloadJson: String): String {
            return try {
                val root = JSONObject(payloadJson)
                val jobObj = root.getJSONObject("job")
                val job = PrintJobDto.fromJson(jobObj)
                val printer = root.optJSONObject("printer")?.let { PrinterCfgDto.fromJson(it) }
                val kind = root.optString("kind", "kitchen")
                val storeName = root.optString("storeName").takeIf { it.isNotBlank() }
                val paymentMethod = root.optString("paymentMethod").takeIf { it.isNotBlank() }
                val total = root.opt("total") as? Double
                    ?: root.optString("total").toDoubleOrNull()

                val target = printer ?: resolvePrinterFromHub(job)
                val t = target ?: return err("找不到打印機設定（payload 冇 printer，PrinterHub 又冇匹配 ${job.printerName ?: job.printerId ?: ""}）")

                // 預檢：畀清晰錯誤，唔使等 SDK 連線 timeout（SdkPrinter 內部會再做一次）
                when (t.connectionType) {
                    "usb" -> if (t.usbVendorId == 0 || t.usbProductId == 0) {
                        return err("USB 打印機缺少 VID/PID（請先用 listUsbPrinters 揀機並儲存）")
                    }
                    "bluetooth" -> if (t.bluetoothAddress.isNullOrBlank()) {
                        return err("藍牙打印機缺少 address（請先用 listBtPrinters / scanBtPrinters 揀機並儲存）")
                    }
                    else -> if (t.ipAddress.isNullOrBlank() || t.lanPort <= 0) {
                        return err("找不到打印機 IP（payload 冇 printer，PrinterHub 又冇匹配 ${job.printerName ?: job.printerId ?: ""}）")
                    }
                }

                // 主路：經 net.posprinter AAR 渲染 + 連線（含大字體行距修正，見 SdkPrinter）
                lifecycleScope.launch {
                    val r = SdkPrinter.print(this@MainActivity, job, t, kind, storeName, paymentMethod, total)
                    withContext(Dispatchers.Main) {
                        val m = if (r.isSuccess) ok(job.id) else err(r.exceptionOrNull()?.message ?: "打印失敗")
                        evalJs("window.__posNativePrintResult && __posNativePrintResult(${JSONObject.quote(m)})")
                    }
                }
                return okQueued(job.id, t.connectionType, t.lanPort)
            } catch (e: Exception) {
                err(e.message ?: "printJob 解析失敗")
            }
        }

        /** 測試打印：payload = { printer } */
        @JavascriptInterface
        fun testPrint(payloadJson: String): String {
            return try {
                val root = JSONObject(payloadJson)
                val printer = root.optJSONObject("printer")?.let { PrinterCfgDto.fromJson(it) }
                    ?: return err("缺 printer")

                // 預檢
                when (printer.connectionType) {
                    "usb" -> if (printer.usbVendorId == 0 || printer.usbProductId == 0) return err("USB 打印機缺少 VID/PID")
                    "bluetooth" -> if (printer.bluetoothAddress.isNullOrBlank()) return err("藍牙打印機缺少 address")
                    else -> if (printer.ipAddress.isNullOrBlank()) return err("缺 ipAddress")
                }

                // 主路：AAR 渲染 + 連線
                lifecycleScope.launch {
                    val r = SdkPrinter.testPrint(
                        this@MainActivity,
                        printer,
                        root.optString("storeName").takeIf { it.isNotBlank() },
                    )
                    withContext(Dispatchers.Main) {
                        val m = if (r.isSuccess) ok(printer.id) else err(r.exceptionOrNull()?.message ?: "測試打印失敗")
                        evalJs("window.__posNativePrintResult && __posNativePrintResult(${JSONObject.quote(m)})")
                    }
                }
                return okQueued(printer.id, printer.connectionType, printer.lanPort)
            } catch (e: Exception) {
                err(e.message ?: "testPrint 失敗")
            }
        }

        /** POS 健康檢查：{ available: true, localIp, devices } */
        @JavascriptInterface
        fun getStatus(): String {
            val localIp = scanner.detectLocalIpv4().orEmpty()
            return JSONObject()
                .put("ok", true)
                .put("available", true)
                .put("service", "macau-pos-print-agent")
                .put("localIp", localIp)
                .put("hasConfig", hub.snapshot().isNotEmpty())
                .put("printerCount", hub.snapshot().size)
                .toString()
        }

        /** docs/96：開「雲端列印中繼」設定／配對畫面。POS 設定頁可以加個掣 call 呢個。 */
        @JavascriptInterface
        fun openRelay() {
            runCatching { RelayService.start(this@MainActivity) }
            webView.post { startActivity(Intent(this@MainActivity, RelayActivity::class.java)) }
        }

        /**
         * docs/96：中繼狀態，POS 用嚟顯示「中繼機在線與否」。
         * `{ enabled, paired, phase, realtimeConnected, sunmiReady, printedCount, failedCount, storeId, localIp }`
         */
        @JavascriptInterface
        fun getRelayStatus(): String {
            val p = RelayPrefs(this@MainActivity)
            return JSONObject()
                .put("ok", true)
                .put("paired", p.isPaired())
                .put("phase", RelayState.phase)
                .put("realtimeConnected", RelayState.realtimeConnected)
                .put("sunmiReady", RelayState.sunmiReady)
                .put("printedCount", RelayState.printedCount)
                .put("failedCount", RelayState.failedCount)
                .put("storeId", p.storeId)
                .put("storeName", p.storeName)
                .put("localIp", RelayState.localIp ?: scanner.detectLocalIpv4().orEmpty())
                .put("lastMessage", RelayState.lastMessage)
                .toString()
        }

        /** POS 列出已綁定打印機（PrinterHub 嗰套，可選用）。 */
        @JavascriptInterface
        fun listDevices(): String = JSONObject()
            .put("ok", true)
            .put("devices", hub.devicesJson())
            .toString()

        /** USB：列出目前插著嘅 USB 打印機（VID/PID/label/權限）。 */
        @JavascriptInterface
        fun listUsbPrinters(): String = try {
            JSONObject().put("ok", true).put("printers", JSONArray(usbController.candidatesJson())).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "listUsbPrinters 失敗").toString()
        }

        /** USB：請求授權某部機（vid/pid）。已授權返 granted=true。 */
        @JavascriptInterface
        fun requestUsbPermission(vid: Int, pid: Int): String {
            val granted = usbController.requestPermission(UsbKey(vid, pid, null))
            return JSONObject().put("ok", true).put("granted", granted)
                .put("vendorId", vid).put("productId", pid).toString()
        }

        /** 藍牙：列出已配對設備。 */
        @JavascriptInterface
        fun listBtPrinters(): String = try {
            JSONObject().put("ok", true).put("printers", JSONArray(btController.bondedJson())).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "listBtPrinters 失敗").toString()
        }

        /** 藍牙：開始探索（結果經 onBtPrinterFound 回調）。需要 BLUETOOTH_SCAN（Android 12+）。 */
        @JavascriptInterface
        fun scanBtPrinters(): String {
            btController.startDiscovery()
            return JSONObject().put("ok", true).put("scanning", true).toString()
        }

        /** 藍牙：停止探索。 */
        @JavascriptInterface
        fun stopBtScan(): String {
            btController.stopDiscovery()
            return JSONObject().put("ok", true).put("scanning", false).toString()
        }

        /** 藍牙：觸發 runtime 授權（Android 12+）。結果經 onBtPermissionResult 回調。 */
        @JavascriptInterface
        fun requestBtPermission(): String {
            maybeRequestBtPermissions()
            return JSONObject().put("ok", true).put("requested", true).toString()
        }

        /** POS 設定頁「開啟打印機設定」按鈕 → 跳 app 內掃描/綁定 UI。 */
        @JavascriptInterface
        fun openPrinterSettings() {
            webView.post { loadPrinterSettings() }
        }

        /** 設定 UI「返回 POS」按鈕。 */
        @JavascriptInterface
        fun backToPos() {
            webView.post { backToPos() }
        }

        // ---- 以下保留畀 app 內設定 UI（index.html）用，沿用 demo 原有合約 ----
        @JavascriptInterface
        fun getBootstrapJson(): String {
            val localIp = scanner.detectLocalIpv4().orEmpty()
            val prefix = scanner.detectSubnetPrefix().orEmpty()
            val hubUrl = if (localIp.isBlank()) "" else "http://$localIp:${PrinterHub.PORT}"
            return JSONObject()
                .put("localIp", localIp)
                .put("subnetPrefix", prefix.ifEmpty { "192.168.1" })
                .put("hubPort", PrinterHub.PORT)
                .put("hubUrl", hubUrl)
                .put("hubListening", hub.listening)
                .put("posUrl", BuildConfig.POS_URL)
                .put("devices", hub.devicesJson())
                .toString()
        }

        @JavascriptInterface
        fun getPairQrDataUrl(): String {
            val ip = scanner.detectLocalIpv4().orEmpty()
            if (ip.isBlank()) return ""
            return PairQr.dataUrl("http://$ip:${PrinterHub.PORT}")
        }

        @JavascriptInterface
        fun detectSubnet(): String = scanner.detectSubnetPrefix().orEmpty()

        @JavascriptInterface
        fun detectLocalIp(): String = scanner.detectLocalIpv4().orEmpty()

        @JavascriptInterface
        fun startScan(prefix: String, identify: Boolean) {
            hub.requestScan(prefix, identify)
        }

        @JavascriptInterface
        fun addManual(ip: String, name: String, serviceId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                hub.addManualBlocking(ip, name, serviceId)
                withContext(Dispatchers.Main) {
                    evalJs("onDevicesUpdated(${hub.devicesJson()}, '已保存')")
                }
            }
        }

        @JavascriptInterface
        fun assignService(key: String, serviceId: String) {
            hub.assignService(key, serviceId)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已綁定服務')")
            }
        }

        @JavascriptInterface
        fun removeDevice(key: String) {
            hub.removeDevice(key)
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已移除')")
            }
        }

        @JavascriptInterface
        fun clearAll() {
            hub.clearAll()
            lifecycleScope.launch {
                evalJs("onDevicesUpdated(${hub.devicesJson()}, '已清除全部')")
            }
        }

        @JavascriptInterface
        fun printService(serviceId: String, message: String) {
            lifecycleScope.launch {
                val result = hub.printService(serviceId, message)
                result.logs.forEach { log ->
                    evalJs("onPrintLog('${escapeJs(log.text)}', ${log.warn})")
                }
            }
        }
    }

    /** 由 PrinterHub 已綁定設備搵匹配（fallback，當 POS 冇帶 printer 時）。 */
    private fun resolvePrinterFromHub(job: PrintJobDto): PrinterCfgDto? {
        val snap = hub.snapshot()
        val match = snap.firstOrNull { it.name == job.printerName }
            ?: snap.firstOrNull { it.key == job.printerId }
            ?: return null
        return PrinterCfgDto(
            id = match.key,
            name = match.name,
            connectionType = if (match.canRawPrint) "lan" else "unknown",
            ipAddress = match.ip,
            lanPort = 9100,
            paperSize = null,
            usbLabel = null,
            charset = null,
        )
    }

    private fun ok(jobId: String) = JSONObject().put("ok", true).put("jobId", jobId).toString()
    private fun okQueued(jobId: String, ip: String, port: Int) =
        JSONObject().put("ok", true).put("queued", true).put("jobId", jobId)
            .put("ip", ip).put("port", port).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    private fun evalJs(script: String) {
        // webView 係 lateinit：任何時機（例如權限結果喺 WebView 建好前同步回呼、
        // UsbController/BtController callback）都有可能未初始化。一定要 guard，
        // 唔可以 UninitializedPropertyAccessException 彈 app（見 crash.txt）。
        if (!::webView.isInitialized) return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    /** 安全呼叫 WebView 全局函數（未定義唔會掟 ReferenceError）。 */
    private fun callJs(fn: String, jsonArgs: String) {
        evalJs("if (typeof window.$fn === 'function') { window.$fn($jsonArgs); }")
    }

    companion object {
        private const val DEFAULT_POS_URL = "https://macau-pos-system.vercel.app"
        private const val REQ_BT_PERMS = 0x4242
    }
}
