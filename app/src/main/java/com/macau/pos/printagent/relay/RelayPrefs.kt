package com.macau.pos.printagent.relay

import android.content.Context
import java.security.SecureRandom

/**
 * 雲端中繼嘅持久設定（SharedPreferences `macau_pos_relay`）。
 *
 * ⚠️ `agentToken` 係長期憑證（用家明確要求「長期」），明文放 SharedPreferences。
 * Android app 嘅私有目錄本身受 sandbox 保護（其他 app 讀唔到），而呢部機係店內
 * 專用中繼機、唔會裝不明 app，風險可接受。Server 端淨存 `sha256(token)`，
 * 所以 DB 被讀都還原唔到 token（docs/96 §6）。
 */
class RelayPrefs(context: Context) {

    private val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 本機身份（首次啟動時生成，之後永久不變）。 */
    var agentId: String?
        get() = sp.getString(K_AGENT_ID, null)
        set(v) = sp.edit().putString(K_AGENT_ID, v).apply()

    /** 長期 token（原始值，server 只存 hash）。配對時產生。 */
    var agentToken: String?
        get() = sp.getString(K_AGENT_TOKEN, null)
        set(v) = sp.edit().putString(K_AGENT_TOKEN, v).apply()

    var storeId: String?
        get() = sp.getString(K_STORE_ID, null)
        set(v) = sp.edit().putString(K_STORE_ID, v).apply()

    var storeName: String?
        get() = sp.getString(K_STORE_NAME, null)
        set(v) = sp.edit().putString(K_STORE_NAME, v).apply()

    /** Supabase project URL，配對成功時由 server 落（唔 hardcode，方便換環境）。 */
    var supabaseUrl: String?
        get() = sp.getString(K_SUPABASE_URL, null)
        set(v) = sp.edit().putString(K_SUPABASE_URL, v).apply()

    /** Supabase anon key：Realtime 訂閱用。**只係**用嚟訂閱，唔可以寫。 */
    var anonKey: String?
        get() = sp.getString(K_ANON_KEY, null)
        set(v) = sp.edit().putString(K_ANON_KEY, v).apply()

    var pairedAt: Long
        get() = sp.getLong(K_PAIRED_AT, 0L)
        set(v) = sp.edit().putLong(K_PAIRED_AT, v).apply()

    /** 冇 printer 快照時嘅兜底目標：true = Sunmi 內置打印機。 */
    var defaultToSunmi: Boolean
        get() = sp.getBoolean(K_DEFAULT_SUNMI, true)
        set(v) = sp.edit().putBoolean(K_DEFAULT_SUNMI, v).apply()

    /**
     * 呢部機係唔係「專做中繼」（開機直接入 RelayActivity 而唔開 POS WebView）。
     *
     * 預設 false —— 現有當 POS 終端機用嘅 Android 機行為完全唔變（無 regression）。
     * Sunmi 嗰部裝好之後喺中繼畫面撳一次「設為開機首頁」就得。
     */
    var relayHome: Boolean
        get() = sp.getBoolean(K_RELAY_HOME, false)
        set(v) = sp.edit().putBoolean(K_RELAY_HOME, v).apply()

    /** 58mm / 80mm 兜底紙寬（printer 快照冇 paperSize 時用）。 */
    var defaultPaperSize: String
        get() = sp.getString(K_DEFAULT_PAPER, "58") ?: "58"
        set(v) = sp.edit().putString(K_DEFAULT_PAPER, v).apply()

    fun isPaired(): Boolean =
        !agentId.isNullOrBlank() && !agentToken.isNullOrBlank() && !storeId.isNullOrBlank()

    /** 只清配對（保留 agentId，等重新配對嗰陣唔使重新生成 QR 內容混亂）。 */
    fun clearPairing() {
        sp.edit()
            .remove(K_AGENT_TOKEN)
            .remove(K_STORE_ID)
            .remove(K_STORE_NAME)
            .remove(K_SUPABASE_URL)
            .remove(K_ANON_KEY)
            .remove(K_PAIRED_AT)
            .apply()
    }

    /** 確保有 agentId；冇就生成一個（UUID-ish，冇需要真 UUID 格式）。 */
    fun ensureAgentId(): String {
        agentId?.let { if (it.isNotBlank()) return it }
        val id = "ag-" + randomHex(16)
        agentId = id
        return id
    }

    /** 生成配對用嘅長期 token（32 bytes ≈ 256 bit）。 */
    fun generateToken(): String {
        val t = randomHex(32)
        agentToken = t
        return t
    }

    /** 確保有 token；冇就生成一個並存落去（手動自註冊配對用）。 */
    fun ensureToken(): String {
        agentToken?.let { if (it.isNotBlank()) return it }
        return generateToken()
    }

    /** 配對二維碼內容：`MPA1|<agentId>|<token>` —— Sunmi 冇鏡頭，係 iPad 掃呢部機。 */
    fun pairPayload(): String = "MPA1|${ensureAgentId()}|${generateToken()}"

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
        private const val K_DEFAULT_SUNMI = "default_sunmi"
        private const val K_DEFAULT_PAPER = "default_paper"
        private const val K_RELAY_HOME = "relay_home"

        /** 配對二維碼前綴（web 端掃描器用嚟識別）。 */
        const val PAIR_PREFIX = "MPA1|"
    }
}
