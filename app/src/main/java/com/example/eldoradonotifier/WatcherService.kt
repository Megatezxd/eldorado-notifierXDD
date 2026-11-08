package com.example.eldoradonotifier

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = Notifier.serviceNotification(this, "بيفحص الإشعارات...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.SERVICE_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.SERVICE_NOTIF_ID, n)
        }
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val prefs = Prefs(this)
        val api = EldoradoApi { prefs.clientId to prefs.clientSecret }
        var failures = 0
        var warned = false
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        while (scope.isActive) {
            try {
                val unread = api.fetchUnread()
                handle(prefs, unread)
                failures = 0
                warned = false
                val msg = "آخر فحص ${clock.format(Date())} • غير مقروء: ${unread.size}"
                prefs.status = msg
                Notifier.updateServiceNotification(this, msg)
            } catch (e: Exception) {
                failures++
                val why = e.message ?: e.javaClass.simpleName
                prefs.status = "فشل الفحص ($failures): $why"
                Notifier.updateServiceNotification(this, "في مشكلة في الاتصال")
                val authProblem = e is ApiException && (e.code == 400 || e.code == 401 || e.code == 403 || e.code == -1)
                if (!warned && (failures >= 5 || (authProblem && failures >= 2))) {
                    warned = true
                    Notifier.notifyPlain(
                        this, 2,
                        "⚠️ مراقبة الدورادو واقفة",
                        if (authProblem) "الـ Credentials غلط أو انتهت. جددها من Dashboard > Seller API. ($why)"
                        else "مش قادر أتصل بالدورادو. ($why)",
                    )
                }
            }
            val base = prefs.intervalSec.coerceAtLeast(30)
            val wait = base * (1 + minOf(failures, 5))
            delay(wait * 1000L)
        }
    }

    private fun handle(prefs: Prefs, unread: List<Notif>) {
        val seen = prefs.seenIds()

        // First successful poll after saving credentials: don't spam old unread items.
        if (!prefs.initialized) {
            seen.addAll(unread.map { it.id })
            prefs.saveSeen(seen)
            prefs.initialized = true
            if (unread.isNotEmpty()) {
                Notifier.notifyPlain(
                    this, 3, "🔔 عندك ${unread.size} إشعار غير مقروء",
                    "من الآن هيوصلك تنبيه لكل إشعار جديد.",
                )
            }
            return
        }

        val fresh = unread.filter { it.id !in seen }.sortedBy { it.dateIso }
        for (n in fresh) Notifier.notifyEvent(this, n)
        if (fresh.isNotEmpty()) {
            seen.addAll(fresh.map { it.id })
            prefs.saveSeen(seen)
        }
    }
}
