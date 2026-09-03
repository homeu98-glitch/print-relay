package com.macau.pos.printagent.model

/**
 * 藍牙打印機列舉結果，對應 web 端 PrinterCandidate（connectionType="bluetooth"）。
 * 穩定鍵 = MAC address（藍牙冇 vid/pid，靠 address 唯一辨識）。
 */
data class BtPrinterCandidate(
    val address: String,
    val name: String?,
    val bonded: Boolean,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("connectionType", "bluetooth")
        .put("key", address)
        .put("bluetoothAddress", address)
        .put("bluetoothName", name ?: address)
        .put("name", name ?: address)
        .put("label", name ?: address)
        .put("address", address)
        .put("bonded", bonded)

    companion object {
        fun fromDevice(device: android.bluetooth.BluetoothDevice, bonded: Boolean): BtPrinterCandidate =
            BtPrinterCandidate(
                address = device.address,
                name = device.name,
                bonded = bonded,
            )
    }
}
