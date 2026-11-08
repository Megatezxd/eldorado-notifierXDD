package com.example.eldoradonotifier

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Minimal Eldorado Seller API client (no external deps).
 * Auth: POST /api/authentication/seller/token {clientId, clientSecret} -> {accessToken, expiresIn, tokenType}
 * Tokens live 15 minutes, so we refresh a minute early and once more on a 401.
 */
class EldoradoApi(private val creds: () -> Pair<String, String>) {

    private val bases = listOf("https://www.eldorado.gg", "https://eldorado.gg")
    private var baseIdx = 0

    private var token: String? = null
    private var expiresAtMs = 0L

    @Synchronized
    private fun ensureToken(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        val cached = token
        if (!force && cached != null && now < expiresAtMs - 60_000) return cached

        val (id, secret) = creds()
        if (id.isBlank() || secret.isBlank()) throw ApiException(-1, "Client ID أو Secret فاضي")

        val body = JSONObject().put("clientId", id).put("clientSecret", secret).toString()
        val res = call("POST", "/api/authentication/seller/token", body, null)
        val j = JSONObject(res)
        val t = j.optString("accessToken", "")
        if (t.isEmpty()) throw ApiException(-2, "الرد مفيهوش accessToken")
        token = t
        expiresAtMs = now + j.optLong("expiresIn", 900L) * 1000L
        return t
    }

    private fun authedGet(path: String): String {
        return try {
            call("GET", path, null, ensureToken())
        } catch (e: ApiException) {
            if (e.code == 401) call("GET", path, null, ensureToken(force = true)) else throw e
        }
    }

    /** Unread notifications, newest first (max 20 per poll). */
    fun fetchUnread(): List<Notif> {
        val json = authedGet("/api/notifications/me?notificationReadStatuses=IsUnread&pageSize=20")
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Notif>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            Notif.fromJson(o)?.let { out.add(it) }
        }
        return out
    }

    private fun call(method: String, path: String, body: String?, bearer: String?): String {
        var last: Exception? = null
        for (k in bases.indices) {
            val idx = (baseIdx + k) % bases.size
            try {
                val r = raw(bases[idx] + path, method, body, bearer)
                baseIdx = idx
                return r
            } catch (e: ApiException) {
                // Only try the other host on redirects; real HTTP errors are final.
                if (e.code in 300..399) last = e else throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("مفيش رد من السيرفر")
    }

    private fun raw(url: String, method: String, body: String?, bearer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "EldoradoNotifier/1.0 (Android)")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, "HTTP $code ${text.take(160)}".trim())
            return text
        } finally {
            conn.disconnect()
        }
    }
}
