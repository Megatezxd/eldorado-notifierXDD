package com.example.eldoradonotifier

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

class Prefs(context: Context) {
    private val app = context.applicationContext
    private val plain: SharedPreferences = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    // Credentials are kept in EncryptedSharedPreferences (Android Keystore).
    // If the Keystore misbehaves on some phone we fall back to app-private storage so the app still works.
    private val secure: SharedPreferences = try {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        app.getSharedPreferences("secure_fallback", Context.MODE_PRIVATE)
    }

    var clientId: String
        get() = secure.getString("cid", "") ?: ""
        set(v) { secure.edit().putString("cid", v).apply() }

    var clientSecret: String
        get() = secure.getString("csec", "") ?: ""
        set(v) { secure.edit().putString("csec", v).apply() }

    var enabled: Boolean
        get() = plain.getBoolean("enabled", false)
        set(v) { plain.edit().putBoolean("enabled", v).apply() }

    var intervalSec: Int
        get() = plain.getInt("interval", 60)
        set(v) { plain.edit().putInt("interval", v.coerceIn(30, 600)).apply() }

    var offerCategory: String
        get() = plain.getString("offer_cat", "TopUp") ?: "TopUp"
        set(v) { plain.edit().putString("offer_cat", v).apply() }

    var status: String
        get() = plain.getString("status", "لسه متشغلش") ?: ""
        set(v) { plain.edit().putString("status", v).apply() }

    /** False until the first successful poll after (re)saving credentials. */
    var initialized: Boolean
        get() = plain.getBoolean("initialized", false)
        set(v) { plain.edit().putBoolean("initialized", v).apply() }

    fun seenIds(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val a = JSONArray(plain.getString("seen", "[]") ?: "[]")
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    /** Offer ids that this app paused (so "resume" only touches those by default). */
    fun pausedIds(): MutableSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val a = JSONArray(plain.getString("paused_by_app", "[]") ?: "[]")
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    fun savePausedIds(ids: Collection<String>) {
        plain.edit().putString("paused_by_app", JSONArray(ids.toList()).toString()).apply()
    }

    fun saveSeen(ids: Collection<String>) {
        val list = ids.toList().takeLast(300)
        plain.edit().putString("seen", JSONArray(list).toString()).apply()
    }
}
