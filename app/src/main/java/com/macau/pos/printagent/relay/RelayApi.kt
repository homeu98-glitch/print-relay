package com.macau.pos.printagent.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 雲端中繼嘅 REST 客戶端（docs/96 §7）。
 *
 * ## 同 server 嘅合約（web 端要跟呢份 implement）
 *
 * | 方法 | 路徑 | 說明 |
 * |---|---|---|
 * | GET  | `/api/pos/print-agent/pair?agentId=` | 配對輪詢。未配對 → `{status:"pending"}`；已配對 → `{status:"paired", storeId, storeName, supabaseUrl, anonKey}` |
 * | POST | `/api/pos/print-agent/claim` | 認領工作。回 `{ok, jobs:[<pos_print_jobs row>], printers:[...]}` |
 * | POST | `/api/pos/print-agent/result` | 回報結果 `{agentId, jobId, status:"sent"\|"failed", error?}` |
 * | POST | `/api/pos/print-agent/heartbeat` | 心跳 + 狀態上報 |
 *
 * 認領用 `for update skip locked`（server 端 RPC `pos_claim_print_jobs`）→
 * 就算兩部中繼機同時認領都唔會印兩次。
 *
 * ⚠️ 所有 method 都係**阻塞**，必須喺 `Dispatchers.IO` call。
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

    /** 配對輪詢（未配對時每 3s call 一次，配對完就停）。 */
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

    /** 手動自註冊配對：APK 自己攞 agentId+token+storeId 去雲端 /pair 註冊（替代 iPad 掃 QR）。 */
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

    /** 解除配對：revoke 雲端 agent（web 同 APK 解除都要 call，否則會繼續 claim 單）。 */
    fun revoke(baseUrl: String, agentId: String, storeId: String): Boolean {
        val payload = JSONObject()
            .put("agentId", agentId)
            .put("storeId", storeId)
            .toString()
        val resp = post("$baseUrl/api/pos/print-agent/unpair", agentId, "", payload)
        return resp?.optBoolean("ok", false) ?: false
    }

    /** 認領一批待印工作。server 會順便標 status='printing' + 寫 claimed_by。 */
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

    /** 回報單張結果；失敗時帶 error 文字（server 會寫 last_error，attempts<5 會返 pending 重試）。 */
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

    /** 心跳：server 更新 pos_print_agents.last_seen_at，並順便回 serverTime 對時。 */
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
