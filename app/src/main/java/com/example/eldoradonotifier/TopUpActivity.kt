package com.example.eldoradonotifier

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Pause / resume all of the seller's Top-up offers. */
class TopUpActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: EldoradoApi
    private lateinit var tvInfo: TextView
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnRefresh: Button

    private val shown = ArrayList<Offer>()
    private var adapter: OfferAdapter? = null
    private var active: List<Offer> = emptyList()
    private var paused: List<Offer> = emptyList()
    private var busy = false
    private var category = "TopUp"
    private lateinit var btnCategory: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = EldoradoApi { prefs.clientId to prefs.clientSecret }
        category = prefs.offerCategory

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            setPadding(dp(16), dp(20), dp(16), dp(8))
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            text("إدارة العروض", 22f, "#FFFFFF", bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        btnRefresh = Button(this).apply {
            text = "تحديث"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener { refresh() }
        }
        header.addView(btnRefresh)
        root.addView(header)

        tvInfo = text("بيحمّل...", 13f, "#80CBC4")
        root.addView(tvInfo)

        btnCategory = bigButton("", "#455A64") { nextCategory() }
        root.addView(btnCategory, params(12))

        btnPause = bigButton("إيقاف كل التوب أب", "#B71C1C") { askPause() }
        root.addView(btnPause, params(8))
        btnResume = bigButton("تشغيل التوب أب", "#2E7D32") { askResume() }
        root.addView(btnResume, params(8))

        val lv = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
        }
        val ad = OfferAdapter(shown)
        adapter = ad
        lv.adapter = ad
        root.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })

        updateLabels()
        refresh()
    }

    private val categories = listOf("TopUp", "GiftCard", "Account", "CustomItem")

    private fun catName(): String = when (category) {
        "TopUp" -> "توب أب"
        "GiftCard" -> "جيفت كارد"
        "Account" -> "حسابات"
        "CustomItem" -> "آيتمز"
        else -> category
    }

    private fun updateLabels() {
        btnCategory.text = "الفئة: ${catName()} (دوس للتغيير)"
        btnPause.text = "إيقاف كل عروض ${catName()}"
        btnResume.text = "تشغيل عروض ${catName()}"
    }

    private fun nextCategory() {
        if (busy) return
        category = categories[(categories.indexOf(category) + 1) % categories.size]
        prefs.offerCategory = category
        updateLabels()
        refresh()
    }

    // ------------------------------------------------------------------ loading

    private fun refresh() {
        if (busy) return
        setBusy(true, "بيحمّل العروض...")
        Thread {
            val res = runCatching { api.listMyOffers(category, "Active") to api.listMyOffers(category, "Paused") }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { (a, p) ->
                    active = a
                    paused = p
                    shown.clear()
                    shown.addAll(a)
                    shown.addAll(p)
                    adapter?.notifyDataSetChanged()
                    setBusy(false, "نشط: ${a.size} • موقوف: ${p.size}")
                }.onFailure { e ->
                    setBusy(false, "فشل التحميل: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun setBusy(b: Boolean, info: String) {
        busy = b
        tvInfo.text = info
        btnPause.isEnabled = !b
        btnResume.isEnabled = !b
        btnRefresh.isEnabled = !b
        btnCategory.isEnabled = !b
    }

    // ------------------------------------------------------------------ actions

    private fun askPause() {
        if (active.isEmpty()) {
            toast("مفيش عروض ${catName()} شغالة")
            return
        }
        val list = active
        AlertDialog.Builder(this)
            .setTitle("إيقاف عروض ${catName()}")
            .setMessage("هيتوقف ${list.size} عرض (${catName()}) شغال. تكمل؟")
            .setPositiveButton("إيقاف") { _, _ -> execute(list, pause = true) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun askResume() {
        if (paused.isEmpty()) {
            toast("مفيش عروض ${catName()} موقوفة")
            return
        }
        val trackedIds = prefs.pausedIds()
        val mine = paused.filter { it.id in trackedIds }
        val all = paused
        val b = AlertDialog.Builder(this).setTitle("تشغيل عروض ${catName()}").setNegativeButton("إلغاء", null)
        if (mine.isNotEmpty()) {
            b.setMessage("هيتشغّل ${mine.size} عرض إنت وقفته من التطبيق.")
            b.setPositiveButton("تشغيل (${mine.size})") { _, _ -> execute(mine, pause = false) }
            if (all.size > mine.size) {
                b.setNeutralButton("كل الموقوف (${all.size})") { _, _ -> execute(all, pause = false) }
            }
        } else {
            b.setMessage("مفيش عروض إنت وقفتها من التطبيق. تشغّل كل عروض ${catName()} الموقوفة (${all.size})؟")
            b.setPositiveButton("تشغيل (${all.size})") { _, _ -> execute(all, pause = false) }
        }
        b.show()
    }

    private fun execute(list: List<Offer>, pause: Boolean) {
        if (busy) return
        val verb = if (pause) "بيوقف" else "بيشغّل"
        setBusy(true, "$verb 0 / ${list.size}")
        Thread {
            var ok = 0
            var fail = 0
            var firstErr: String? = null
            val tracked = prefs.pausedIds()
            for ((i, o) in list.withIndex()) {
                try {
                    api.setPaused(o, pause)
                    ok++
                    if (pause) tracked.add(o.id) else tracked.remove(o.id)
                } catch (e: Exception) {
                    fail++
                    if (firstErr == null) firstErr = e.message ?: e.javaClass.simpleName
                    if (e is ApiException && e.code == 429) break
                }
                val done = i + 1
                runOnUiThread { tvInfo.text = "$verb $done / ${list.size}" }
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    break
                }
            }
            prefs.savePausedIds(tracked)
            val doneWord = if (pause) "اتوقف" else "اتشغّل"
            val msg = buildString {
                append("$doneWord $ok من ${list.size}")
                if (fail > 0) append("\nفشل $fail" + (firstErr?.let { ": $it" } ?: ""))
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setBusy(false, msg)
                AlertDialog.Builder(this).setTitle("النتيجة").setMessage(msg)
                    .setPositiveButton("تمام", null).show()
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------------------------ list

    private inner class OfferAdapter(private val data: List<Offer>) :
        ArrayAdapter<Offer>(this@TopUpActivity, 0, data) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val o = data[position]
            val isActive = o.state == "Active"
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            row.addView(TextView(context).apply {
                text = (if (isActive) "▶ " else "⏸ ") + o.title
                textSize = 14f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor(MUTED))
            })
            val sub = listOfNotNull(
                o.game.takeIf { it.isNotBlank() && it != o.title },
                o.price?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.2f", it) },
                if (isActive) "شغال" else "موقوف",
            ).joinToString(" • ")
            row.addView(TextView(context).apply {
                text = sub
                textSize = 12f
                setTextColor(Color.parseColor("#777777"))
            })
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(row)
            wrap.addView(
                View(context).apply { setBackgroundColor(Color.parseColor("#2A2A2A")) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
            )
            return wrap
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun text(t: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun bigButton(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        setOnClickListener { onClick() }
    }

    private fun params(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    companion object {
        private const val BG = "#121212"
        private const val MUTED = "#B0B0B0"
    }
}
