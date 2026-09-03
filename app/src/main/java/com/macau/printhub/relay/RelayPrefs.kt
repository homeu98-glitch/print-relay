package com.macau.printhub.relay

import android.content.Context
import java.security.SecureRandom

/**
 * Persistent relay settings (SharedPreferences "macau_pos_relay").
 * Ported from print-agent-android, identical contract.
 * agentToken is a long-term credential stored in plaintext in the app sandbox.
 */
class RelayPrefs(context: Context) {

    private val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var agentId: String?
        get() = sp.getString(K_AGENT_ID, null)
        set(v) = sp.edit().putString(K_AGENT_ID, v).apply()

    var agentToken: String?
        get() = sp.getString(K_AGENT_TOKEN, null)
        set(v) = sp.edit().putString(K_AGENT_TOKEN, v).apply()

    var storeId: String?
        get() = sp.getString(K_STORE_ID, null)
        set(v) = sp.edit().putString(K_STORE_ID, v).apply()

    var storeName: String?
        get() = sp.getString(K_STORE_NAME, null)
        set(v) = sp.edit().putString(K_STORE_NAME, v).apply()

    var supabaseUrl: String?
        get() = sp.getString(K_SUPABASE_URL, null)
        set(v) = sp.edit().putString(K_SUPABASE_URL, v).apply()

    var anonKey: String?
        get() = sp.getString(K_ANON_KEY, null)
        set(v) = sp.edit().putString(K_ANON_KEY, v).apply()

    var pairedAt: Long
        get() = sp.getLong(K_PAIRED_AT, 0L)
        set(v) = sp.edit().putLong(K_PAIRED_AT, v).apply()

    var defaultPaperSize: String
        get() = sp.getString(K_DEFAULT_PAPER, "80") ?: "80"
        set(v) = sp.edit().putString(K_DEFAULT_PAPER, v).apply()

    /** Login phone number (8-digit Macau phone). Persisted for auto re-pair on reboot. */
    var loginPhone: String?
        get() = sp.getString(K_LOGIN_PHONE, null)
        set(v) = sp.edit().putString(K_LOGIN_PHONE, v).apply()

    /** Login PIN (4-digit). Persisted so the hub can re-auth on reboot without user input. */
    var loginPin: String?
        get() = sp.getString(K_LOGIN_PIN, null)
        set(v) = sp.edit().putString(K_LOGIN_PIN, v).apply()

    fun isPaired(): Boolean =
        !agentId.isNullOrBlank() && !agentToken.isNullOrBlank() && !storeId.isNullOrBlank()

    fun clearPairing() {
        sp.edit()
            .remove(K_AGENT_TOKEN)
            .remove(K_STORE_ID)
            .remove(K_STORE_NAME)
            .remove(K_SUPABASE_URL)
            .remove(K_ANON_KEY)
            .remove(K_PAIRED_AT)
            .remove(K_LOGIN_PHONE)
            .remove(K_LOGIN_PIN)
            .apply()
    }

    fun ensureAgentId(): String {
        agentId?.let { if (it.isNotBlank()) return it }
        val id = "ag-" + randomHex(16)
        agentId = id
        return id
    }

    fun generateToken(): String {
        val t = randomHex(32)
        agentToken = t
        return t
    }

    fun ensureToken(): String {
        agentToken?.let { if (it.isNotBlank()) return it }
        return generateToken()
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREF = "macau_pos_relay"
        private const val K_AGENT_ID = "agent_id"
        private const val K_AGENT_TOKEN = "agent_token"
        private const val K_STORE_ID = "store_id"
        private const val K_STORE_NAME = "store_name"
        private const val K_SUPABASE_URL = "supabase_url"
        private const val K_ANON_KEY = "anon_key"
        private const val K_PAIRED_AT = "paired_at"
        private const val K_DEFAULT_PAPER = "default_paper"
        private const val K_LOGIN_PHONE = "login_phone"
        private const val K_LOGIN_PIN = "login_pin"
    }
}
