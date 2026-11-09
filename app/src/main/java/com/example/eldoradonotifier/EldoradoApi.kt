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

    private fun authedGet(path: String): String = authed("GET", path, null)

    private fun authed(method: String, path: String, body: String?): String {
        return try {
            call(method, path, body, ensureToken())
        } catch (e: ApiException) {
            if (e.code == 401) call(method, path, body, ensureToken(force = true)) else throw e
        }
    }

    /**
     * Offers of one category (TopUp, GiftCard, Account, CustomItem) in the given state ("Active" or "Paused").
     * Top-ups may live in the predefined or the flexible offer family, so both are queried.
     */
    fun listMyOffers(category: String, state: String): List<Offer> {
        val out = ArrayList<Offer>()
        var okSources = 0
        var lastErr: Exception? = null
        for (flex in listOf(false, true)) {
            try {
                out.addAll(listOffers(flex, category, state))
                okSources++
            } catch (e: ApiException) {
                lastErr = e
            }
        }
        if (okSources == 0) throw lastErr ?: IOException("مفيش رد من السيرفر")
        return out
    }

    private fun listOffers(flex: Boolean, category: String, state: String): List<Offer> {
        val base = if (flex) "/api/flexibleOffers/me/search" else "/api/predefinedOffers/me"
        val res = ArrayList<Offer>()
        var page = 1
        while (page <= 10) {
            val json = JSONObject(authedGet("$base?category=$category&offerState=$state&pageIndex=$page&pageSize=50"))
            val arr = json.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cat = o.optString("category", "")
                    if (cat.isNotEmpty() && cat != category && !cat[0].isDigit()) continue
                    val st = o.optString("offerState", "")
                    if (st.isNotEmpty() && st != state && !st[0].isDigit()) continue
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    val title = clean(o.optString("offerTitle", "")) ?: clean(o.optString("gameCategoryTitle", "")) ?: "عرض"
                    val game = clean(o.optString("gameCategoryTitle", "")) ?: ""
                    val price = o.optJSONObject("pricePerUnit")?.let { if (it.has("amount")) it.optDouble("amount") else null }
                    res.add(Offer(id, flex, title, game, state, price))
                }
            }
            if (page >= json.optInt("totalPages", 1)) break
            page++
        }
        return res
    }

    private fun clean(s: String): String? = s.takeIf { it.isNotBlank() && it != "null" }

    /** Pause or resume one offer. Empty body, success = HTTP 200. */
    fun setPaused(offer: Offer, pause: Boolean) {
        val family = if (offer.flexible) "flexibleOffersUser" else "predefinedOffersUser"
        val action = if (pause) "pause" else "resume"
        authed("PUT", "/api/$family/me/${offer.id}/$action", "")
    }

    /** Unread notifications, newest first (max 20 per poll). */
    fun fetchUnread(): List<Notif> =
        parse(authedGet("/api/notifications/me?notificationReadStatuses=IsUnread&pageSize=20"))

    /** Latest notifications (read and unread) for the in-app list. */
    fun fetchRecent(count: Int): List<Notif> =
        parse(authedGet("/api/notifications/me?pageSize=${count.coerceIn(1, 50)}"))

    private fun parse(json: String): List<Notif> {
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
