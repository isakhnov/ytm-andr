package com.ytmlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Without this, SessionLogger only ever starts from LaunchActivity.onCreate
 * — after a phone reboot, favorites capture is silently off until the app
 * is next opened manually. START_STICKY doesn't help across reboots, only
 * across the service's own process being killed while the phone stays on.
 *
 * BOOT_COMPLETED is one of the recognized exemptions for starting a
 * foreground service from a background broadcast receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Probes.hasNotificationAccess(context)) return

        val i = Intent(context, SessionLogger::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
    }
}
