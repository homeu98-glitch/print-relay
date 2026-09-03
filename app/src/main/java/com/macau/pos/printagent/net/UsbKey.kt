package com.macau.pos.printagent.net

/**
 * 穩定識別一臺 USB 打印機。優先用 vendorId:productId，必要時加 serial 區分同型號多機。
 * stableKey() 同 web 端 DevicePrinterConfig.usbVendorId/usbProductId 對齊。
 */
data class UsbKey(
    val vendorId: Int,
    val productId: Int,
    val serial: String? = null,
) {
    fun stableKey(): String = buildString {
        append("usb:").append(vendorId).append(":").append(productId)
        serial?.takeIf { it.isNotBlank() }?.let { append(":").append(it) }
    }

    companion object {
        /** 由 stableKey() 嘅字串還原；失敗返 null。 */
        fun parse(key: String): UsbKey? {
            val body = key.removePrefix("usb:")
            val parts = body.split(":")
            if (parts.size < 2) return null
            val v = parts[0].toIntOrNull() ?: return null
            val p = parts[1].toIntOrNull() ?: return null
            val s = if (parts.size >= 3) parts[2] else null
            return UsbKey(v, p, s)
        }
    }
}
