package com.macau.printhub.model

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
