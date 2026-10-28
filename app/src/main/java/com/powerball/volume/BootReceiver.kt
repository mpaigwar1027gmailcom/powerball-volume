package com.powerball.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Settings.canDrawOverlays(context)
        ) {
            try {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
