package com.example.eldoradonotifier

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var etId: EditText
    private lateinit var etSecret: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            tvStatus.text = buildStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(root)
        }
        setContentView(scroll)

        root.addView(label("مراقب الدورادو", 24f, bold = true))
        root.addView(label("تطبيق غير رسمي. بيفحص إشعارات حسابك كل فترة ويبعتلك تنبيه على الموبايل.", 13f, "#B0B0B0"))

        root.addView(label("Client ID", 14f, topMargin = 20))
        etId = field("الصق الـ Client ID", false).also { root.addView(it) }
        etId.setText(prefs.clientId)

        root.addView(label("Client Secret", 14f, topMargin = 12))
        etSecret = field(
            if (prefs.clientSecret.isNotEmpty()) "محفوظ (سيبه فاضي لو مش عايز تغيره)" else "الصق الـ Secret",
            true,
        ).also { root.addView(it) }

        root.addView(label("الفحص كل كام ثانية؟ (30 - 600)", 14f, topMargin = 12))
        etInterval = field("60", false).also {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setText(prefs.intervalSec.toString())
            root.addView(it)
        }

        root.addView(button("حفظ وتشغيل المراقبة", "#2E7D32") { saveAndStart() }, btnParams(20))
        root.addView(button("إيقاف", "#B71C1C") { stopWatcher() }, btnParams(8))
        root.addView(button("تجربة الاتصال", "#1565C0") { testConnection() }, btnParams(8))
        root.addView(button("إشعار تجريبي", "#455A64") { testNotification() }, btnParams(8))
        root.addView(button("استثناء التطبيق من توفير البطارية", "#6A1B9A") { openBatterySettings() }, btnParams(8))

        tvStatus = label("", 14f, "#80CBC4", topMargin = 20)
        root.addView(tvStatus)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildStatus(): String =
        "الحالة: " + (if (prefs.enabled) "شغال ✅" else "واقف ⏸") + "\n" + prefs.status

    private fun saveAndStart() {
        val id = etId.text.toString().trim()
        val sec = etSecret.text.toString().trim()
        if (id.isEmpty() || (sec.isEmpty() && prefs.clientSecret.isEmpty())) {
            toast("ادخل الـ Client ID والـ Secret الأول")
            return
        }
        if (id != prefs.clientId || sec.isNotEmpty()) {
            prefs.clientId = id
            if (sec.isNotEmpty()) prefs.clientSecret = sec
            prefs.initialized = false
            prefs.saveSeen(emptyList())
        }
        etSecret.setText("")
        etSecret.hint = "محفوظ (سيبه فاضي لو مش عايز تغيره)"
        prefs.intervalSec = etInterval.text.toString().toIntOrNull() ?: 60
        prefs.enabled = true
        prefs.status = "بيبدأ..."

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val svc = Intent(this, WatcherService::class.java)
        stopService(svc)
        ContextCompat.startForegroundService(this, svc)
        toast("اشتغلت المراقبة")
    }

    private fun stopWatcher() {
        prefs.enabled = false
        prefs.status = "واقف"
        stopService(Intent(this, WatcherService::class.java))
        toast("وقفت المراقبة")
    }

    private fun testConnection() {
        val id = etId.text.toString().trim().ifEmpty { prefs.clientId }
        val sec = etSecret.text.toString().trim().ifEmpty { prefs.clientSecret }
        tvStatus.text = "بيجرب الاتصال..."
        Thread {
            val msg = try {
                val list = EldoradoApi { id to sec }.fetchUnread()
                "✅ الاتصال شغال. إشعارات غير مقروءة: ${list.size}" +
                    (list.firstOrNull()?.let { "\nآخر واحد: ${it.title}" } ?: "")
            } catch (e: Exception) {
                "❌ فشل: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("نتيجة التجربة").setMessage(msg)
                    .setPositiveButton("تمام", null).show()
            }
        }.start()
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

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---- tiny UI helpers ----
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false, topMargin: Int = 0) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
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

    private fun btnParams(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(topMargin), 0, 0) }
}
