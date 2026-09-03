package com.macau.printhub.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for the cloud relay contract (same as print-agent-android).
 * POST /claim, POST /result, POST /heartbeat, GET /pair, POST /pair, POST /unpair.
 * All methods are blocking — must be called on Dispatchers.IO.
 */
class RelayApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    data class PairPoll(
        val status: String,
        val storeId: String?,
        val storeName: String?,
        val supabaseUrl: String?,
        val anonKey: String?,
        val error: String?,
    )

    data class ClaimResult(
        val jobs: List<JSONObject>,
        val printers: List<JSONObject>,
        val error: String?,
    )

    /** Result of login + pair: storeId from login, or error. */
    data class LoginPairResult(
        val ok: Boolean,
        val storeId: String?,
        val storeName: String?,
        val error: String?,
    )

    /**
     * Login with phone+PIN (same as macau-pos login), get merchantId from the response,
     * then use it as storeId to self-pair.
     *
     * This replaces the old manual storeId entry — the store ID is the logged-in
     * merchant ID, so the user just enters the same account they use in POS.
     */
    fun loginAndPair(
        baseUrl: String,
        agentId: String,
        token: String,
        phone: String,
        pin: String,
    ): LoginPairResult {
        // Step 1: Login via the same endpoint macau-pos uses
        val loginPayload = JSONObject()
            .put("phone", phone)
            .put("pin", pin)
            .toString()

        val loginResp = try {
            client.newCall(
                Request.Builder()
                    .url("${baseUrl.trim().trimEnd('/')}/api/ledger/login")
                    .addHeader("Content-Type", "application/json")
                    .post(loginPayload.toRequestBody(json))
                    .build()
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return LoginPairResult(false, null, null, parseLoginError(resp.code, text))
                }
                runCatching { JSONObject(text) }.getOrElse {
                    return LoginPairResult(false, null, null, "登入回應格式錯誤")
                }
            }
        } catch (e: Exception) {
            return LoginPairResult(false, null, null, "登入連線失敗：${e.message}")
        }

        if (!loginResp.optBoolean("ok", false)) {
            val err = loginResp.optString("error", "登入失敗")
            return LoginPairResult(false, null, null, err)
        }

        val session = loginResp.optJSONObject("session")
            ?: return LoginPairResult(false, null, null, "登入回應缺少 session")
        val storeId = session.optString("merchantId").takeIf { it.isNotBlank() }
            ?: return LoginPairResult(false, null, null, "登入回應缺少 merchantId")
        val storeName = session.optString("name").takeIf { it.isNotBlank() }

        // Step 2: Self-pair with the storeId from login
        val pairOk = selfPair(baseUrl, agentId, token, storeId, storeName)
        return if (pairOk) {
            LoginPairResult(true, storeId, storeName, null)
        } else {
            LoginPairResult(false, null, null, "配對提交失敗（登入成功但 /pair 請求失敗）")
        }
    }

    private fun parseLoginError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
            ?: body.take(200)
        return when (code) {
            401 -> "帳號或 PIN 不正確"
            403 -> "非本店 Ledger 帳號或商戶已停用"
            429 -> "登入嘗試過於頻繁，請稍後再試"
            503 -> "伺服器暫時無法登入：${msg.ifBlank { "請稍後再試" }}"
            else -> "登入失敗（HTTP $code）：${msg.ifBlank { "未知錯誤" }}"
        }
    }

    fun pollPair(baseUrl: String, agentId: String): PairPoll {
        val url = "${baseUrl.trim().trimEnd('/')}/api/pos/print-agent/pair?agentId=$agentId"
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return PairPoll("error", null, null, null, null, "HTTP ${resp.code} $body")
                val o = JSONObject(body)
                PairPoll(
                    status = o.optString("status", "pending"),
                    storeId = o.optString("storeId").takeIf { it.isNotBlank() },
                    storeName = o.optString("storeName").takeIf { it.isNotBlank() },
                    supabaseUrl = o.optString("supabaseUrl").takeIf { it.isNotBlank() },
                    anonKey = o.optString("anonKey").takeIf { it.isNotBlank() },
                    error = o.optString("error").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            PairPoll("error", null, null, null, null, e.message)
        }
    }

    fun selfPair(baseUrl: String, agentId: String, token: String, storeId: String, name: String?): Boolean {
        val payload = JSONObject()
            .put("agentId", agentId)
            .put("token", token)
            .put("storeId", storeId)
            .put("name", name ?: JSONObject.NULL)
            .toString()
        val resp = post("$baseUrl/api/pos/print-agent/pair", agentId, token, payload)
        return resp?.optBoolean("ok", false) ?: false
    }

    fun revoke(baseUrl: String, agentId: String, storeId: String): Boolean {
        val payload = JSONObject()
            .put("agentId", agentId)
            .put("storeId", storeId)
            .toString()
        val resp = post("$baseUrl/api/pos/print-agent/unpair", agentId, "", payload)
        return resp?.optBoolean("ok", false) ?: false
    }

    fun claim(
        baseUrl: String,
        agentId: String,
        token: String,
        storeId: String,
        limit: Int = 5,
    ): ClaimResult {
        val payload = JSONObject()
            .put("agentId", agentId)
            .put("storeId", storeId)
            .put("limit", limit)
            .toString()
        val resp = post("$baseUrl/api/pos/print-agent/claim", agentId, token, payload)
            ?: return ClaimResult(emptyList(), emptyList(), "claim 連線失敗")
        val err = resp.optString("error").takeIf { it.isNotBlank() }
        if (err != null) return ClaimResult(emptyList(), emptyList(), err)
        val jobs = resp.optJSONArray("jobs")?.toObjectList() ?: emptyList()
        val printers = resp.optJSONArray("printers")?.toObjectList() ?: emptyList()
        return ClaimResult(jobs, printers, null)
    }

    fun report(
        baseUrl: String,
        agentId: String,
        token: String,
        jobId: String,
        status: String,
        error: String?,
    ): Boolean {
        val payload = JSONObject()
            .put("agentId", agentId)
            .put("jobId", jobId)
            .put("status", status)
        if (!error.isNullOrBlank()) payload.put("error", error.take(300))
        return post("$baseUrl/api/pos/print-agent/result", agentId, token, payload.toString()) != null
    }

    fun heartbeat(
        baseUrl: String,
        agentId: String,
        token: String,
        storeId: String?,
        info: JSONObject,
    ): JSONObject? {
        val payload = JSONObject(info.toString())
            .put("agentId", agentId)
        if (!storeId.isNullOrBlank()) payload.put("storeId", storeId)
        return post("$baseUrl/api/pos/print-agent/heartbeat", agentId, token, payload.toString())
    }

    private fun post(url: String, agentId: String, token: String, body: String): JSONObject? {
        val clean = url.trim().trimEnd('/').replace("/api/pos/print-agent/api/", "/api/pos/print-agent/")
        val req = Request.Builder()
            .url(clean)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-agent-id", agentId)
            .addHeader("x-agent-token", token)
            .post(body.toRequestBody(json))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) {
                    if (resp.isSuccessful) JSONObject() else null
                } else {
                    runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
                        .also { if (!resp.isSuccessful) it.put("error", "HTTP ${resp.code}") }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull { runCatching { getJSONObject(it) }.getOrNull() }
}
