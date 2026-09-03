package com.macau.printhub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macau.printhub.BuildConfig
import com.macau.printhub.R
import com.macau.printhub.data.DeviceStore
import com.macau.printhub.model.LogEntry
import com.macau.printhub.model.PrinterDevice
import com.macau.printhub.net.LanScanner
import com.macau.printhub.net.EscPosRenderer
import com.macau.printhub.net.SdkPrinter
import com.macau.printhub.model.PrinterCfgDto
import com.macau.printhub.relay.HubService
import com.macau.printhub.relay.LogEntryLog
import com.macau.printhub.relay.RelayApi
import com.macau.printhub.relay.RelayPrefs
import com.macau.printhub.relay.RelayState
import com.macau.printhub.relay.isIgnoringBatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main activity — pure native Android UI (no WebView).
 * Shows: pairing status + pairing UI, LAN scanner + discovered printers, print log.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: RelayPrefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scanner: LanScanner

    // Status views
    private lateinit var tvStatus: TextView
    private lateinit var tvHubIp: TextView

    // Pairing
    private lateinit var etPhone: EditText
    private lateinit var etPin: EditText
    private lateinit var btnPair: Button
    private lateinit var btnUnpair: Button
    private lateinit var btnBattery: Button

    // Scanner
    private lateinit var tvScanStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var printersContainer: LinearLayout

    // Log
    private lateinit var logAdapter: LogAdapter
    private lateinit var etLogFilter: EditText
    private lateinit var btnLogAll: Button
    private lateinit var btnLogSuccess: Button
    private lateinit var btnLogFailed: Button
    private lateinit var btnClearLog: Button
    private lateinit var rvLog: RecyclerView

    private var logFilterSuccess: Boolean? = null
    private var logSearchText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = RelayPrefs(this)
        scanner = LanScanner(this)
        prefs.ensureAgentId()
        prefs.ensureToken()
        HubService.start(this)
        maybeRequestNotifyPermission()
        setContentView(buildUi())
        // 開 app 就要見到上次掃到嘅機（之前淨係 scan 完先 refresh，重開 app 會變「尚未發現」）
        refreshPrinters()
        startTicking()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ─────────────────────────── UI ───────────────────────────

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Macau Print Hub"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "POS：${BuildConfig.POS_URL}　v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.6f
            setPadding(0, 0, 0, dp(12))
        })

        // Hub IP (for LAN direct calls)
        tvHubIp = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvHubIp)

        // Status
        tvStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
            setLineSpacing(0f, 1.25f)
        }
        root.addView(tvStatus)

        // Pairing section
        root.addView(sectionHeader("配對"))
        root.addView(TextView(this).apply {
            text = "用 POS 登入號碼配對（同 macau-pos 登入嘅帳號一樣）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        etPhone = EditText(this).apply {
            hint = "8 位電話號碼"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_NUMBER
            // Pre-fill if previously paired
            prefs.loginPhone?.let { setText(it) }
        }
        root.addView(etPhone)
        etPin = EditText(this).apply {
            hint = "4 位 PIN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            // Pre-fill if previously paired
            prefs.loginPin?.let { setText(it) }
        }
        root.addView(etPin)
        btnPair = button("配對") { doPair() }
        root.addView(btnPair)
        btnUnpair = button("解除配對") { doUnpair() }
        root.addView(btnUnpair)
        btnBattery = button("電池最佳化設定") { openBatterySettings() }
        root.addView(btnBattery)

        // Scanner section
        root.addView(sectionHeader("區網打印機掃描"))
        tvScanStatus = TextView(this).apply {
            text = "就緒"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(tvScanStatus)
        btnScan = button("掃描區網") { doScan() }
        root.addView(btnScan)
        printersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(printersContainer)

        // Log section
        root.addView(sectionHeader("列印日誌"))
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        etLogFilter = EditText(this).apply {
            hint = "搜尋…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    logSearchText = s?.toString() ?: ""
                    refreshLog()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        filterRow.addView(etLogFilter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnLogAll = buttonSmall("全部") { logFilterSuccess = null; updateFilterButtons(); refreshLog() }
        btnLogSuccess = buttonSmall("成功") { logFilterSuccess = true; updateFilterButtons(); refreshLog() }
        btnLogFailed = buttonSmall("失敗") { logFilterSuccess = false; updateFilterButtons(); refreshLog() }
        btnClearLog = buttonSmall("清空") { LogEntryLog.clear(); refreshLog() }
        filterRow.addView(btnLogAll)
        filterRow.addView(btnLogSuccess)
        filterRow.addView(btnLogFailed)
        filterRow.addView(btnClearLog)
        root.addView(filterRow)

        rvLog = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            logAdapter = LogAdapter()
            adapter = logAdapter
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
        root.addView(rvLog)

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(16), 0, dp(4))
        setOnClickListener { refreshLog() }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }

    private fun buttonSmall(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.bottomMargin = 0
            lp.rightMargin = dp(4)
            layoutParams = lp
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startTicking() {
        val r = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(r)
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val paired = prefs.isPaired()
        val phaseText = when (RelayState.phase) {
            "pairing" -> "等待配對"
            "connecting" -> "連線中…"
            "online" -> "已連線（Realtime）"
            "offline" -> "雲端斷線（30s 輪詢兜底）"
            "error" -> "錯誤"
            else -> "待機"
        }
        val ip = RelayState.localIp ?: runCatching { scanner.detectLocalIpv4() }.getOrNull()
        RelayState.localIp = ip
        val ago = { t: Long ->
            if (t == 0L) "—" else "${(System.currentTimeMillis() - t) / 1000}s 前"
        }

        tvHubIp.text = "本機 HTTP：${if (ip.isNullOrBlank()) "(未連線)" else "http://$ip:8787"}"

        tvStatus.text = buildString {
            append("狀態：$phaseText\n")
            append("店舖：${if (paired) "${prefs.storeName ?: prefs.storeId}（${prefs.storeId}）" else "未綁定"}\n")
            append("本機 IP：$ip　Android ${Build.VERSION.SDK_INT}\n")
            append("紙寬：${prefs.defaultPaperSize}mm\n")
            append("已印 ${RelayState.printedCount} 張 · 失敗 ${RelayState.failedCount} 張\n")
            append("上次認領：${ago(RelayState.lastClaimAt)}　上次心跳：${ago(RelayState.lastHeartbeatAt)}\n")
            append("上次叫醒：${ago(RelayState.lastWakeAt)}\n")
            if (RelayState.lastMessage.isNotBlank()) append("訊息：${RelayState.lastMessage}")
        }

        // Pairing visibility
        val pairVisible = if (paired) View.GONE else View.VISIBLE
        etPhone.visibility = pairVisible
        etPin.visibility = pairVisible
        btnPair.visibility = pairVisible
        btnUnpair.visibility = if (paired) View.VISIBLE else View.GONE
        btnBattery.visibility =
            if (isIgnoringBatteryOptimizations(this)) View.GONE else View.VISIBLE

        // Refresh log every tick
        refreshLog()
    }

    private fun refreshLog() {
        val logs = LogEntryLog.filtered(logFilterSuccess, logSearchText)
        logAdapter.submitList(logs)
    }

    private fun updateFilterButtons() {
        btnLogAll.isEnabled = logFilterSuccess != null
        btnLogSuccess.isEnabled = logFilterSuccess != true
        btnLogFailed.isEnabled = logFilterSuccess != false
    }

    // ─────────────────────────── Actions ───────────────────────────

    private fun doPair() {
        val phone = etPhone.text.toString().trim()
        val pin = etPin.text.toString().trim()
        if (phone.isBlank() || phone.length != 8 || !phone.all { it.isDigit() }) {
            Toast.makeText(this, "請輸入 8 位電話號碼", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            Toast.makeText(this, "請輸入 4 位 PIN", Toast.LENGTH_SHORT).show()
            return
        }

        val api = RelayApi()
        val agentId = prefs.ensureAgentId()
        val token = prefs.ensureToken()
        // Disable button while pairing
        btnPair.isEnabled = false
        btnPair.text = "配對中…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.loginAndPair(BuildConfig.POS_URL, agentId, token, phone, pin)
            }
            btnPair.isEnabled = true
            btnPair.text = "配對"
            if (result.ok) {
                // Save credentials for auto re-pair on reboot
                prefs.loginPhone = phone
                prefs.loginPin = pin
                // 店名由 /api/ledger/login 嘅 session.name（= merchants.name）嚟。
                // pos_print_agents 冇 store_name 欄，所以 pollPair 只會返 null —— 唔好靠佢。
                if (!result.storeName.isNullOrBlank()) prefs.storeName = result.storeName
                Toast.makeText(this@MainActivity, "配對成功：${result.storeName ?: result.storeId}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, result.error ?: "配對失敗", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doUnpair() {
        val aid = prefs.agentId
        val sid = prefs.storeId
        lifecycleScope.launch(Dispatchers.IO) {
            if (!aid.isNullOrBlank() && !sid.isNullOrBlank()) {
                RelayApi().revoke(BuildConfig.POS_URL, aid, sid)
            }
        }
        prefs.clearPairing()
        Toast.makeText(this, "已解除配對", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }.onFailure {
                Toast.makeText(this, "請人手去設定→電池加入白名單", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doScan() {
        val prefix = scanner.detectSubnetPrefix()
        if (prefix.isNullOrBlank()) {
            Toast.makeText(this, "偵測不到本機 IP，請確認 Wi-Fi 已連線", Toast.LENGTH_LONG).show()
            return
        }
        btnScan.isEnabled = false
        tvScanStatus.text = "掃描中…"
        lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                scanner.scanSubnet(prefix) { prog ->
                    tvScanStatus.text = "掃描中 ${prog.checked}/${prog.total}｜發現 ${prog.found}"
                }
            }
            // Save hits to device store
            val store = DeviceStore(this@MainActivity)
            val devices = store.load()
            hits.forEach { hit ->
                val mac = hit.mac?.uppercase()
                val key = mac?.let { "mac:$it" } ?: "ip:${hit.ip}"
                val oldByIp = devices["ip:${hit.ip}"]
                val existing = devices[key] ?: oldByIp
                if (oldByIp != null && key.startsWith("mac:")) devices.remove("ip:${hit.ip}")
                val name = existing?.name
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
                    service = existing?.service,
                    lastSeen = System.currentTimeMillis(),
                )
            }
            store.save(devices)

            tvScanStatus.text = "掃描完成：發現 ${hits.size} 台"
            btnScan.isEnabled = true
            refreshPrinters()
        }
    }

    private fun refreshPrinters() {
        val store = DeviceStore(this)
        val devices = store.load().values.sortedByDescending { it.lastSeen }
        printersContainer.removeAllViews()
        if (devices.isEmpty()) {
            printersContainer.addView(TextView(this).apply {
                text = "尚未發現打印機，請先掃描"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.5f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            // 實際用嘅 port：優先用 9100；冇就退而求其次用掃到嘅其他 port
            // （之前硬碼 9100，對住只開咗 80/631 嘅路由器撳測試，永遠冇反應又冇提示）
            val port = if (d.canRawPrint) 9100
                else d.openPorts.firstOrNull() ?: 9100
            val info = TextView(this).apply {
                text = buildString {
                    append(d.name)
                    append("  ${d.ip}")
                    append("  [${d.openPorts.joinToString("/")}]")
                    if (!d.canRawPrint) append("\n⚠ 冇開 9100，可能唔係打印機")
                    d.mac?.let { append("\nMAC: $it") }
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val btnTest = Button(this).apply {
                text = "測試"
                setOnClickListener {
                    val cfg = PrinterCfgDto(
                        id = d.key,
                        name = d.name,
                        connectionType = "lan",
                        ipAddress = d.ip,
                        lanPort = port,
                        paperSize = prefs.defaultPaperSize,
                        usbLabel = null,
                        charset = null,
                    )
                    testPrint(this, cfg)
                }
                val lp = LinearLayout.LayoutParams(dp(72), dp(36))
                lp.gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp
            }
            row.addView(btnTest)
            printersContainer.addView(row)

            // Divider
            printersContainer.addView(View(this).apply {
                setBackgroundColor(0x33FFFFFF.toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                layoutParams = lp
            })
        }
    }

    /**
     * 測試列印。
     * 必要改動：
     *  1. 一定要寫 LogEntryLog（以前淨係彈 Toast，日誌永遠空白，無從追查）
     *  2. 掣要 disable + 顯示「測試中…」，等用戶知道有嘢喺度行
     *  3. 顯示耗時，分得清「即時失敗」定「等 timeout 等到傻」
     */
    private fun testPrint(btn: Button, cfg: PrinterCfgDto) {
        val originalLabel = btn.text.toString()
        btn.isEnabled = false
        btn.text = "測試中…"
        val startedAt = System.currentTimeMillis()
        val target = "${cfg.name} (${cfg.ipAddress}:${cfg.lanPort})"

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val bytes = EscPosRenderer.renderTestPage(cfg, prefs.storeName ?: "Macau POS")
                SdkPrinter.printBytes(this@MainActivity, cfg, bytes)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val err = r.exceptionOrNull()?.message

            LogEntryLog.add(
                LogEntry(
                    id = LogEntry.nextId(),
                    timestamp = System.currentTimeMillis(),
                    source = "測試列印",
                    targetPrinter = target,
                    summary = "測試頁（${elapsed}ms）",
                    success = r.isSuccess,
                    error = err,
                )
            )
            if (r.isSuccess) RelayState.printedCount++ else RelayState.failedCount++

            btn.isEnabled = true
            btn.text = originalLabel
            Toast.makeText(
                this@MainActivity,
                if (r.isSuccess) "測試頁已送出（${elapsed}ms）→ $target"
                else "測試失敗（${elapsed}ms）：$err",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}
