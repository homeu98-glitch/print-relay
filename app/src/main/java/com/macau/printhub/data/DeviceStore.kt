package com.macau.printhub.data

import android.content.Context
import com.macau.printhub.model.PrinterDevice
import com.macau.printhub.model.PrinterService
import org.json.JSONArray
import org.json.JSONObject

class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): MutableMap<String, PrinterDevice> {
        val raw = prefs.getString(KEY, null) ?: return linkedMapOf()
        return try {
            val arr = JSONArray(raw)
            val map = linkedMapOf<String, PrinterDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("key")
                val ports = mutableListOf<Int>()
                val pArr = o.optJSONArray("openPorts")
                if (pArr != null) {
                    for (j in 0 until pArr.length()) ports += pArr.getInt(j)
                }
                map[key] = PrinterDevice(
                    key = key,
                    name = o.optString("name", key),
                    ip = o.optString("ip", ""),
                    mac = o.optString("mac").takeIf { it.isNotBlank() },
                    openPorts = ports,
                    service = PrinterService.fromId(o.optString("service").ifBlank { null }),
                    lastSeen = o.optLong("lastSeen", System.currentTimeMillis()),
                )
            }
            map
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    fun save(devices: Map<String, PrinterDevice>) {
        val arr = JSONArray()
        devices.values.forEach { d ->
            arr.put(
                JSONObject()
                    .put("key", d.key)
                    .put("name", d.name)
                    .put("ip", d.ip)
                    .put("mac", d.mac ?: "")
                    .put("openPorts", JSONArray(d.openPorts))
                    .put("service", d.service?.id ?: "")
                    .put("lastSeen", d.lastSeen)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "macau_pos_relay_hub"
        private const val KEY = "devices_v1"
    }
}
