package com.example.eldoradonotifier

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var api: EldoradoApi? = null
    private val items = ArrayList<Notif>()
    private var adapter: NotifAdapter? = null
    private var tvStatus: TextView? = null
    private var tvEmpty: TextView? = null
    private var listShown = false
    private var loading = false
    private var lastError: String? = null
    private var lastOkAt = ""

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (listShown) {
                load()
                handler.postDelayed(this, 30_000)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
        route()
    }

    override fun onResume() {
        super.onResume()
        if (listShown) {
            handler.removeCallbacks(autoRefresh)
            handler.post(autoRefresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(autoRefresh)
        super.onPause()
    }

    private fun route() {
        handler.removeCallbacks(autoRefresh)
        if (prefs.clientId.isNotBlank() && prefs.clientSecret.isNotBlank()) showList() else showLogin()
    }

    // ------------------------------------------------------------------ login screen

    private fun showLogin() {
        listShown = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
            addView(root)
        }
        setContentView(scroll)

        root.addView(label("مراقب الدورادو", 26f, bold = true))
        root.addView(label("سجّل دخول بالـ Credentials من Dashboard > Seller API في الدورادو.", 13f, MUTED))

        root.addView(label("Client ID", 14f, topMargin = 24))
        val etId = field("الصق الـ Client ID", false)
        root.addView(etId)

        root.addView(label("Client Secret", 14f, topMargin = 12))
        val etSecret = field("الصق الـ Client Secret", true)
        root.addView(etSecret)

        val tvError = label("", 13f, "#EF9A9A", topMargin = 12)
        root.addView(tvError)

        val btn = button("دخول", "#2E7D32") { }
        root.addView(btn, btnParams(16))

        btn.setOnClickListener {
            val id = etId.text.toString().trim()
            val sec = etSecret.text.toString().trim()
            if (id.isEmpty() || sec.isEmpty()) {
                tvError.text = "ادخل الـ Client ID والـ Secret"
                return@setOnClickListener
            }
            tvError.text = ""
            btn.isEnabled = false
            btn.text = "بيتأكد..."
            Thread {
                val err: String? = try {
                    EldoradoApi { id to sec }.fetchRecent(5)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (err == null) {
                        saveAndStart(id, sec)
                        route()
                    } else {
                        btn.isEnabled = true
                        btn.text = "دخول"
                        tvError.text = "فشل الدخول: $err"
                    }
                }
            }.start()
        }
    }

    private fun saveAndStart(id: String, sec: String) {
        prefs.clientId = id
        prefs.clientSecret = sec
        prefs.initialized = false
        prefs.saveSeen(emptyList())
        prefs.enabled = true
        prefs.status = "بيبدأ..."
        requestNotifPermission()
        startWatcher()
    }

    // ------------------------------------------------------------------ list screen

    private fun showList() {
        listShown = true
        api = EldoradoApi { prefs.clientId to prefs.clientSecret }
        items.clear()
        lastError = null
        lastOkAt = ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            setPadding(dp(16), dp(20), dp(16), 0)
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("الإشعارات", 22f, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("حسابي") { openEldorado() })
        val refreshBtn = smallButton("تحديث") { load() }
        header.addView(refreshBtn)
        val menuBtn = smallButton("⋮") { }
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        header.addView(menuBtn)
        root.addView(header)

        tvStatus = label("", 12f, "#80CBC4", topMargin = 4)
        root.addView(tvStatus)

        root.addView(
            button("إدارة العروض: إيقاف / تشغيل (توب أب وغيره)", "#0F6E56") {
                startActivity(Intent(this, TopUpActivity::class.java))
            },
            btnParams(8),
        )

        tvEmpty = label("بيحمّل...", 14f, MUTED, topMargin = 24).apply { gravity = Gravity.CENTER }
        root.addView(tvEmpty)

        val lv = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
        }
        val ad = NotifAdapter(items)
        adapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, position, _ ->
            val n = items.getOrNull(position) ?: return@setOnItemClickListener
            val url = Notifier.safeUrl(n.redirectUrl) ?: "https://www.eldorado.gg/"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                toast("مش قادر أفتح الرابط")
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        renderStatus()
        handler.removeCallbacks(autoRefresh)
        handler.post(autoRefresh)
    }

    private fun load() {
        if (loading || !listShown) return
        val client = api ?: return
        loading = true
        tvStatus?.text = "بيحدّث..."
        Thread {
            val res = runCatching { client.fetchRecent(30) }
            runOnUiThread {
                loading = false
                if (isFinishing || !listShown) return@runOnUiThread
                res.onSuccess { list ->
                    items.clear()
                    items.addAll(list)
                    adapter?.notifyDataSetChanged()
                    lastError = null
                    lastOkAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                }.onFailure { e ->
                    lastError = e.message ?: e.javaClass.simpleName
                }
                tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty?.text = if (lastError != null && items.isEmpty()) "مش قادر أحمّل الإشعارات" else "مفيش إشعارات لسه"
                renderStatus()
            }
        }.start()
    }

    private fun renderStatus() {
        val svc = if (prefs.enabled) "المراقبة شغالة ✅" else "المراقبة واقفة ⏸"
        val upd = if (lastOkAt.isNotEmpty()) " • آخر تحديث $lastOkAt" else ""
        val err = lastError?.let { "\nفشل: $it" } ?: ""
        tvStatus?.text = "$svc$upd$err"
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "إشعار تجريبي")
        m.menu.add(0, 2, 1, if (prefs.enabled) "إيقاف المراقبة" else "تشغيل المراقبة")
        m.menu.add(0, 3, 2, "استثناء من توفير البطارية")
        m.menu.add(0, 4, 3, "تسجيل خروج")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> testNotification()
                2 -> toggleWatcher()
                3 -> openBatterySettings()
                4 -> confirmLogout()
            }
            true
        }
        m.show()
    }

    private fun toggleWatcher() {
        if (prefs.enabled) {
            prefs.enabled = false
            prefs.status = "واقف"
            stopService(Intent(this, WatcherService::class.java))
            toast("وقفت المراقبة")
        } else {
            prefs.enabled = true
            requestNotifPermission()
            startWatcher()
            toast("اشتغلت المراقبة")
        }
        renderStatus()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("تسجيل خروج")
            .setMessage("هيتمسح الـ Client ID والـ Secret من الموبايل وتقف المراقبة.")
            .setPositiveButton("خروج") { _, _ ->
                stopService(Intent(this, WatcherService::class.java))
                prefs.enabled = false
                prefs.clientId = ""
                prefs.clientSecret = ""
                prefs.initialized = false
                prefs.saveSeen(emptyList())
                prefs.status = "واقف"
                route()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private inner class NotifAdapter(private val data: List<Notif>) :
        ArrayAdapter<Notif>(this@MainActivity, 0, data) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val n = data[position]
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            val head = (if (n.unread) "● " else "") + n.title
            row.addView(TextView(context).apply {
                text = head
                textSize = 15f
                setTextColor(if (n.unread) Color.WHITE else Color.parseColor(MUTED))
                setTypeface(typeface, if (n.unread) Typeface.BOLD else Typeface.NORMAL)
            })
            row.addView(TextView(context).apply {
                text = n.body
                textSize = 13f
                setTextColor(Color.parseColor(MUTED))
            })
            val t = fmtTime(n.dateIso)
            if (t.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = t
                    textSize = 11f
                    setTextColor(Color.parseColor("#777777"))
                })
            }
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(row)
            wrap.addView(View(context).apply { setBackgroundColor(Color.parseColor("#2A2A2A")) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            return wrap
        }
    }

    private fun fmtTime(iso: String): String {
        if (iso.length < 19) return ""
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            inFmt.timeZone = TimeZone.getTimeZone("UTC")
            val d = inFmt.parse(iso.substring(0, 19)) ?: return ""
            SimpleDateFormat("d/M  HH:mm", Locale.getDefault()).format(d)
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun startWatcher() {
        val svc = Intent(this, WatcherService::class.java)
        stopService(svc)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun testNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            toast("اسمح بالإشعارات وجرب تاني")
            return
        }
        Notifier.notifyPlain(this, 99, "🛒 إشعار تجريبي", "لو شفت ده يبقى الإشعارات شغالة.")
    }

    private fun openEldorado() {
        val uri = Uri.parse("https://www.eldorado.gg/")
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, uri)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                toast("مش قادر أفتح المتصفح")
            }
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false, topMargin: Int = 0) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(topMargin), 0, dp(4)) }
        }

    private fun field(hint: String, secret: Boolean) = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#777777"))
        isSingleLine = true
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_LTR
    }

    private fun button(text: String, color: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2A2A2A"))
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40),
        ).apply { setMargins(dp(6), 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun btnParams(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(topMargin), 0, 0) }

    companion object {
        private const val BG = "#121212"
        private const val MUTED = "#B0B0B0"
    }
}
