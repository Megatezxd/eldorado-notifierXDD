package com.example.eldoradonotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_SERVICE = "service"
    const val SERVICE_NOTIF_ID = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alerts = NotificationChannel(CH_ALERTS, "تنبيهات الدورادو", NotificationManager.IMPORTANCE_HIGH)
        alerts.enableVibration(true)
        nm.createNotificationChannel(alerts)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "حالة المراقبة", NotificationManager.IMPORTANCE_MIN)
        )
    }

    fun serviceNotification(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("مراقبة الدورادو شغالة")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    fun updateServiceNotification(ctx: Context, text: String) {
        post(ctx, SERVICE_NOTIF_ID, serviceNotification(ctx, text))
    }

    fun notifyEvent(ctx: Context, n: Notif) {
        post(ctx, n.id.hashCode(), buildAlert(ctx, n.id.hashCode(), n.title, n.body, safeUrl(n.redirectUrl)))
    }

    fun notifyPlain(ctx: Context, id: Int, title: String, body: String) {
        post(ctx, id, buildAlert(ctx, id, title, body, null))
    }

    private fun buildAlert(ctx: Context, id: Int, title: String, body: String, url: String?): Notification {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url ?: "https://www.eldorado.gg/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    /** Only open links that point at eldorado.gg (relative links are resolved against it). */
    private fun safeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val full = if (raw.startsWith("/")) "https://www.eldorado.gg$raw" else raw
        val u = Uri.parse(full)
        val host = u.host ?: return null
        return if (u.scheme == "https" && (host == "eldorado.gg" || host.endsWith(".eldorado.gg"))) full else null
    }
}
