package com.macau.pos.printagent.model

enum class PrinterService(val id: String, val label: String) {
    FRONT("front", "前台"),
    BAR("bar", "水吧"),
    KITCHEN("kitchen", "廚房");

    companion object {
        fun fromId(id: String?): PrinterService? =
            entries.firstOrNull { it.id == id }
    }
}

data class PrinterDevice(
    /** 穩定鍵：優先 MAC，否則 ip:<address> */
    val key: String,
    val name: String,
    val ip: String,
    val mac: String?,
    val openPorts: List<Int>,
    val service: PrinterService?,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val canRawPrint: Boolean get() = 9100 in openPorts
}
