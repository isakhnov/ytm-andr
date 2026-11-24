package com.nightstand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watcher after reboot or app update. specialUse FGS may start from BOOT_COMPLETED. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.enabled(c)) WatcherService.start(c)
    }
}
