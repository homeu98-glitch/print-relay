package com.macau.pos.printagent.relay

/**
 * 中繼嘅 in-memory 狀態，淨係畀 UI（RelayActivity）讀。
 *
 * 刻意唔用 LiveData / Flow：呢個 app 得一個 UI 畫面，加 lifecycle 依賴唔抵；
 * Activity 用 1s handler 輪詢就得（見 RelayActivity.refresh()）。
 */
object RelayState {

    /** idle / pairing / connecting / online / offline / error */
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
     * 最近一次「列印失敗」嘅原因。
     * 冇咗呢個，用戶淨係見到「失敗 1 張」但完全唔知衰邊度（同 Print Hub 當初嘅 bug 一樣）。
     * 同 lastMessage 分開：lastMessage 係連線/配對狀態用，唔好互相蓋住。
     */
    @Volatile
    var lastPrintError: String = ""

    @Volatile
    var lastPrintErrorAt: Long = 0L

    @Volatile
    var sunmiReady: Boolean = false

    @Volatile
    var sunmiModal: String? = null

    @Volatile
    var localIp: String? = null

    fun note(msg: String) {
        lastMessage = msg
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
