package com.example.eldoradonotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).enabled) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))
        } catch (e: Exception) {
            // Some phones block background starts at boot; the user can open the app once to resume.
        }
    }
}
