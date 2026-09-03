package com.macau.pos.printagent.model

import com.macau.pos.printagent.net.UsbKey

/**
 * USB 打印機列舉結果，對應 web 端 PrinterCandidate（connectionType="usb"）。
 * 名單 label 優先 productName → 已知廠牌 VID 對照 → deviceName → 兜底 "USB 打印機"。
 */
data class UsbPrinterCandidate(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String?,
    val productName: String?,
    val serialNumber: String?,
    val key: String,            // UsbKey.stableKey()
    val brandLabel: String,
    val hasPermission: Boolean,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("connectionType", "usb")
        .put("key", key)
        .put("vendorId", vendorId)
        .put("productId", productId)
        .put("deviceName", deviceName ?: "")
        .put("productName", productName ?: "")
        .put("serialNumber", serialNumber ?: "")
        .put("label", brandLabel)
        .put("name", brandLabel)
        .put("usbLabel", brandLabel)
        .put("hasPermission", hasPermission)

    companion object {
        /** 常見 ESC/POS USB 打印機廠牌 VID → 名稱（只作 label，匹配靠 class 7）。 */
        val VENDOR_NAMES = mapOf(
            0x04B8 to "Epson",
            0x0519 to "Star Micronics",
            0x04C8 to "Citizen",
            0x1504 to "Bixolon",
            0x042A to "Seiko (SII)",
            0x0483 to "Xprinter / ST",     // 芯烨等常用 ST chip
            0x0416 to "Gprinter / Winbond", // 佳博等
            0x0403 to "FTDI 芯片",
            0x067B to "Prolific 芯片",
            0x1A86 to "QinHeng (CH340/CH341)",
        )

        fun brandLabelFor(
            vendorId: Int,
            productName: String?,
            deviceName: String?,
        ): String {
            val byName = productName?.takeIf { it.isNotBlank() }
            if (byName != null) return byName
            VENDOR_NAMES[vendorId]?.let { return "$it (VID ${vendorId.toString(16).uppercase()})" }
            val byDev = deviceName?.takeIf { it.isNotBlank() }
            if (byDev != null) return byDev
            return "USB 打印機 (VID ${vendorId.toString(16).uppercase()})"
        }

        fun fromDevice(
            vendorId: Int,
            productId: Int,
            deviceName: String?,
            productName: String?,
            serialNumber: String?,
            hasPermission: Boolean,
        ): UsbPrinterCandidate {
            val key = UsbKey(vendorId, productId, serialNumber).stableKey()
            return UsbPrinterCandidate(
                vendorId = vendorId,
                productId = productId,
                deviceName = deviceName,
                productName = productName,
                serialNumber = serialNumber,
                key = key,
                brandLabel = brandLabelFor(vendorId, productName, deviceName),
                hasPermission = hasPermission,
            )
        }
    }
}
