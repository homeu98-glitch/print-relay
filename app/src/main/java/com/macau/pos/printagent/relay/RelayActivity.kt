package com.macau.pos.printagent.relay

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macau.pos.printagent.BuildConfig
import com.macau.pos.printagent.R
import com.macau.pos.printagent.hub.PrinterHub
import com.macau.pos.printagent.model.PrintJobDto
import com.macau.pos.printagent.model.PrinterCfgDto
import com.macau.pos.printagent.net.LanScanner
import com.macau.pos.printagent.net.EscPosRenderer
import com.macau.pos.printagent.net.SdkPrinter
import com.macau.pos.printagent.net.SunmiPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 雲端列印中繼嘅設定／狀態畫面（docs/96 §7）。
 *
 * 兩個用途：
 *  1. **配對**：顯示 `MPA1|<agentId>|<token>` 二維碼，由 iPad（web POS）掃。
 *     Sunmi V2 冇鏡頭，所以係「部機出 code、iPad 掃」而唔係「部機掃」。
 *  2. **維運**：睇連線狀態、已印張數、Sunmi 內置打印機係咪就緒、測試打印。
 */
class RelayActivity : ComponentActivity() {

    private lateinit var prefs: RelayPrefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    /** 最近一次列印失敗嘅原因。紅字、獨立一行：淨係靠「失敗 N 張」係追唔到嘢嘅。 */
    private lateinit var tvLastError: TextView
    private lateinit var pairingHint: TextView
    private lateinit var etStoreId: EditText
    private lateinit var btnPair: Button
    private lateinit var btnTestSunmi: Button
    private lateinit var btnTestLan: Button
    private lateinit var btnRelayHome: Button
    private lateinit var btnBattery: Button
    private lateinit var btnUnpair: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = RelayPrefs(this)
        RelayService.start(this)
        maybeRequestNotifyPermission()
        // 手動自註冊配對：確保 agentId + token 存在（配對嗰陣 APK 自己 POST /pair）。
        prefs.ensureAgentId()
        prefs.ensureToken()
        setContentView(buildUi())
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

        root.addView(TextView(this).apply {
            text = "雲端列印中繼"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "POS：${BuildConfig.POS_URL}　v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.6f
            setPadding(0, 0, 0, dp(12))
        })

        tvStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
            setLineSpacing(0f, 1.25f)
        }
        root.addView(tvStatus)

        tvLastError = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
            setLineSpacing(0f, 1.25f)
            setTextColor(Color.parseColor("#FF6B6B"))
            visibility = android.view.View.GONE
        }
        root.addView(tvLastError)

        pairingHint = TextView(this).apply {
            text = "手動配對：喺 web POS「設置 → 打印機」複製本店店舖 ID，貼落下面，撳「配對」。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(pairingHint)

        etStoreId = EditText(this).apply {
            hint = "店舖 ID（例如 macau-store-a）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(etStoreId)

        btnPair = button("配對") {
            val sid = etStoreId.text.toString().trim()
            if (sid.isBlank()) {
                Toast.makeText(this@RelayActivity, "請輸入店舖 ID", Toast.LENGTH_SHORT).show()
                return@button
            }
            doPair(sid)
        }
        root.addView(btnPair)

        btnTestSunmi = button("測試打印（Sunmi 內置）") { testSunmi() }
        root.addView(btnTestSunmi)

        btnTestLan = button("測試打印（LAN 第一台）") { testLan() }
        root.addView(btnTestLan)

        btnRelayHome = button("") { toggleRelayHome() }
        root.addView(btnRelayHome)

        btnBattery = button("電池最佳化設定") { openBatterySettings() }
        root.addView(btnBattery)

        btnUnpair = button("解除配對") {
            val aid = prefs.agentId
            val sid = prefs.storeId
            lifecycleScope.launch(Dispatchers.IO) {
                if (!aid.isNullOrBlank() && !sid.isNullOrBlank()) {
                    RelayApi().revoke(BuildConfig.POS_URL, aid, sid)
                }
            }
            prefs.clearPairing()
            Toast.makeText(this, "已解除配對", Toast.LENGTH_SHORT).show()
            refresh()
        }
        root.addView(btnUnpair)

        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            lp.bottomMargin = dp(8)
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
        val ip = RelayState.localIp ?: runCatching { LanScanner(this).detectLocalIpv4() }.getOrNull()
        RelayState.localIp = ip
        val ago = { t: Long ->
            if (t == 0L) "—" else "${(System.currentTimeMillis() - t) / 1000}s 前"
        }
        val sunmi = if (RelayState.sunmiReady) {
            "就緒${RelayState.sunmiModal?.let { "（$it）" } ?: ""}"
        } else {
            "未就緒${SunmiPrinter.lastError?.let { "：$it" } ?: ""}"
        }

        tvStatus.text = buildString {
            append("狀態：$phaseText\n")
            append("店舖：${if (paired) "${prefs.storeName ?: prefs.storeId}（${prefs.storeId}）" else "未綁定"}\n")
            append("本機 IP：$ip　Android ${Build.VERSION.SDK_INT}\n")
            append("Sunmi 內置打印機：$sunmi\n")
            append("紙寬：${prefs.defaultPaperSize}mm（冇 printer 快照時用）\n")
            append("已印 ${RelayState.printedCount} 張 · 失敗 ${RelayState.failedCount} 張\n")
            append("上次認領：${ago(RelayState.lastClaimAt)}　上次心跳：${ago(RelayState.lastHeartbeatAt)}\n")
            append("上次叫醒：${ago(RelayState.lastWakeAt)}\n")
            if (RelayState.lastMessage.isNotBlank()) append("訊息：${RelayState.lastMessage}")
        }

        // 失敗原因一定要睇得到：否則用戶淨係見到「失敗 1 張」，完全無從追查。
        val printErr = RelayState.lastPrintError
        if (printErr.isNotBlank()) {
            val agoErr = if (RelayState.lastPrintErrorAt == 0L) "" else "（${ago(RelayState.lastPrintErrorAt)}）"
            tvLastError.text = "⚠ 上次列印失敗$agoErr\n$printErr"
            tvLastError.visibility = android.view.View.VISIBLE
        } else {
            tvLastError.visibility = android.view.View.GONE
        }

        pairingHint.visibility = if (paired) android.view.View.GONE else android.view.View.VISIBLE
        etStoreId.visibility = pairingHint.visibility
        btnPair.visibility = pairingHint.visibility
        btnUnpair.visibility = if (paired) android.view.View.VISIBLE else android.view.View.GONE
        btnRelayHome.text =
            if (prefs.relayHome) "取消「開機直接入中繼」" else "設為開機首頁（中繼專用機）"
        btnBattery.visibility =
            if (isIgnoringBatteryOptimizations(this)) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ─────────────────────────── 動作 ───────────────────────────

    /** 手動自註冊配對：APK 自己 POST /pair，RelayService 嘅 pollPair loop 會學到 paired 並拎 supabaseUrl/anonKey。 */
    private fun doPair(storeId: String) {
        val api = RelayApi()
        val agentId = prefs.ensureAgentId()
        val token = prefs.ensureToken()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                api.selfPair(BuildConfig.POS_URL, agentId, token, storeId, null)
            }
            if (ok) {
                Toast.makeText(this@RelayActivity, "已提交配對，等待雲端確認…", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(
                    this@RelayActivity,
                    "配對失敗，請檢查網絡或店舖 ID 是否正確",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun toggleRelayHome() {
        prefs.relayHome = !prefs.relayHome
        Toast.makeText(
            this,
            if (prefs.relayHome) "下次開機會直接入中繼畫面" else "已還原：開機入 POS",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }.onFailure {
                Toast.makeText(this, "呢部機唔支援直接開設定，請人手去設定→電池加入白名單", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Sunmi 內置打印機測試頁：行同一個 `EscPosRenderer`，順便驗證 58mm 紙寬 + 中文編碼。
     * 印兩段 —— 測試頁（連線/字型/紙寬）+ 一張假廚房單（兩欄對齊）。
     */
    private fun testSunmi() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { printSunmiTest() }
            Toast.makeText(
                this@RelayActivity,
                if (r.isSuccess) "已送出測試頁" else "測試失敗：${r.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
            RelayState.note(if (r.isSuccess) "測試頁已印（Sunmi 內置）" else "測試失敗：${r.exceptionOrNull()?.message}")
        }
    }

    private fun printSunmiTest(): Result<Unit> {
        if (!SunmiPrinter.ensureReady(this)) {
            return Result.failure(IllegalStateException("未就緒：${SunmiPrinter.lastError ?: "未綁定"}"))
        }
        val store = prefs.storeName ?: "Macau POS"
        val cfg = PrinterCfgDto(
            id = "sunmi-builtin",
            name = "Sunmi 內置打印機",
            connectionType = "sunmi",
            ipAddress = null,
            lanPort = 9100,
            paperSize = prefs.defaultPaperSize,
            usbLabel = null,
            charset = null,
        )
        val page = SunmiPrinter.sendRaw(EscPosRenderer.renderTestPage(cfg, store))
        if (page.isFailure) return page

        val job = PrintJobDto.fromRow(
            org.json.JSONObject()
                .put("id", "test-${System.currentTimeMillis()}")
                .put("order_no", "TEST0001")
                .put("table_name", "A1")
                .put("ticket_type", "normal")
                .put("printer_name", "Sunmi 內置打印機")
                .put(
                    "items",
                    org.json.JSONArray()
                        .put(
                            org.json.JSONObject()
                                .put("name", "珍珠奶茶（大杯）")
                                .put("quantity", 2)
                                .put("price", 44.0)
                                .put("specs", org.json.JSONArray().put("加購:加珍珠 $5"))
                                .put("note", "少冰、走甜")
                        )
                        .put(
                            org.json.JSONObject()
                                .put("name", "炸雞脾")
                                .put("quantity", 1)
                                .put("price", 30.0)
                                .put("discountRate", 85.0)
                                .put("originalUnitPrice", 30.0)
                                .put("discountedUnitPrice", 25.5)
                                .put("savingAmount", 4.5)
                        )
                )
        )
        return SunmiPrinter.sendRaw(EscPosRenderer.renderKitchenTicket(job, cfg, store))
    }

    /** 打印去 LAN 第一台已綁定設備（用 SdkPrinter 既有連線路）。 */
    private fun testLan() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val first = runCatching { PrinterHub.get(this@RelayActivity).snapshot() }
                    .getOrNull()?.firstOrNull()
                    ?: return@withContext Result.failure<Unit>(IllegalStateException("未有已綁定嘅 LAN 打印機"))
                val cfg = PrinterCfgDto(
                    id = first.key,
                    name = first.name,
                    connectionType = "lan",
                    ipAddress = first.ip,
                    lanPort = 9100,
                    paperSize = prefs.defaultPaperSize,
                    usbLabel = null,
                    charset = null,
                )
                val bytes = EscPosRenderer.renderTestPage(cfg, prefs.storeName ?: "Macau POS")
                SdkPrinter.printBytes(this@RelayActivity, cfg, bytes)
            }
            Toast.makeText(
                this@RelayActivity,
                if (r.isSuccess) "已送出測試頁" else "測試失敗：${r.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun maybeRequestNotifyPermission() {
        // 用 Context#checkSelfPermission（API 23+）而唔係 ContextCompat：
        // androidx.core 只係 activity-ktx 嘅 transitive dep，直接引用有機會 compile 唔到。
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}
