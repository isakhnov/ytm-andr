package com.nightstand

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One app's worth of pending notifications, as Nightstand shows them. */
data class NotifSummary(
    val pkg: String,
    val appLabel: String,
    val count: Int,
    /** Latest title; null for VISIBILITY_SECRET. Shown only if the user opts in. */
    val title: String?,
    /** What the posting app allows on a lock screen (public / publicVersion). */
    val publicTitle: String?,
    val postTime: Long,
)

/**
 * Notification access — the same permission YTMLauncher already uses. Keeps a
 * grouped snapshot of what a person would care about on a nightstand: no
 * ongoing (music, navigation, our own watcher), no silent, no group summaries.
 */
class NotificationFeed : NotificationListenerService() {

    override fun onListenerConnected() { instance = this; rebuild() }
    override fun onListenerDisconnected() { instance = null; snapshot = emptyList(); StandByState.notifyChanged() }
    override fun onNotificationPosted(sbn: StatusBarNotification) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = rebuild()

    private fun rebuild() {
        val all = try { activeNotifications ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        val ranking = currentRanking
        val tmp = Ranking()
        val pm = packageManager
        snapshot = all.asSequence()
            .filter { it.packageName != packageName }
            .filter { !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter {
                // Silent / minimized notifications don't belong on a bedside display.
                ranking == null || !ranking.getRanking(it.key, tmp) ||
                    tmp.importance >= NotificationManager.IMPORTANCE_DEFAULT
            }
            .groupBy { it.packageName }
            .map { (pkg, list) ->
                val latest = list.maxBy { it.postTime }
                NotifSummary(
                    pkg = pkg,
                    appLabel = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Throwable) { pkg },
                    count = list.size,
                    title = latest.notification.let { n ->
                        if (n.visibility == Notification.VISIBILITY_SECRET) null else titleOf(n)
                    },
                    publicTitle = latest.notification.let { n ->
                        when (n.visibility) {
                            Notification.VISIBILITY_PUBLIC -> titleOf(n)
                            Notification.VISIBILITY_PRIVATE -> n.publicVersion?.let { titleOf(it) }
                            else -> null
                        }
                    },
                    postTime = latest.postTime,
                )
            }
            .sortedByDescending { it.postTime }
        StandByState.notifyChanged()
    }

    private fun titleOf(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    companion object {
        @Volatile var snapshot: List<NotifSummary> = emptyList()
            private set
        @Volatile private var instance: NotificationFeed? = null

        fun isGranted(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotificationFeed::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
