package com.macau.printhub.relay

/**
 * In-memory relay state for UI polling.
 * Ported from print-agent-android.
 */
object RelayState {

    @Volatile
    var phase: String = "idle"

    @Volatile
    var realtimeConnected: Boolean = false

    @Volatile
    var lastWakeAt: Long = 0L

    @Volatile
    var lastClaimAt: Long = 0L

    @Volatile
    var lastHeartbeatAt: Long = 0L

    @Volatile
    var printedCount: Int = 0

    @Volatile
    var failedCount: Int = 0

    @Volatile
    var lastMessage: String = ""

    /**
     * 最近一次「列印失敗」嘅原因（同 print-agent-android 嘅 RelayState 對齊）。
     * 冇咗呢個，用戶淨係見到「失敗 N 張」但完全唔知衰邊度 —— 就係最初報嗰個 bug。
     * 同 lastMessage 分開：後者係連線／配對狀態用，唔好互相蓋住。
     */
    @Volatile
    var lastPrintError: String = ""

    @Volatile
    var lastPrintErrorAt: Long = 0L

    @Volatile
    var localIp: String? = null

    fun note(msg: String) {
        lastMessage = msg
    }

    /**
     * 記錄一次列印失敗。統一格式：`<單號> → <打印機>：<原因>`。
     * LogEntryLog 得 app 內 UI 睇到；通知欄（headless 中繼專用機唯一會俾人望到嘅嘢）
     * 要靠呢兩個欄位。
     */
    fun notePrintError(orderNo: String, printer: String, error: String?) {
        lastPrintError = "$orderNo → $printer：${error ?: "未知錯誤"}"
        lastPrintErrorAt = System.currentTimeMillis()
    }

    fun reset() {
        phase = "idle"
        realtimeConnected = false
        lastWakeAt = 0L
        lastClaimAt = 0L
        lastHeartbeatAt = 0L
        printedCount = 0
        failedCount = 0
        lastMessage = ""
        lastPrintError = ""
        lastPrintErrorAt = 0L
    }
}
