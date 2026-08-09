package com.ytmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Lock-screen song picker: up to ROWS individually-tappable notifications
 * (one per random resolved favorite), bundled under one summary via
 * setGroup — the standard Android mechanism for "several independently
 * actionable items," the same one Gmail/messaging apps use for a stack of
 * tappable items. Not MediaStyle (built for one now-playing track's
 * controls, not a picker) and not a single InboxStyle notification (its
 * extra lines are just text, never individually tappable).
 *
 * Each row's tap fires PlayFavoriteReceiver directly via a broadcast
 * PendingIntent — no Activity launch, so it plays straight from the lock
 * screen without unlocking or opening the app.
 */
object FavoritesNotifier {

    const val ROWS = 8
    private const val CHANNEL_ID = "ytmprobe_favorites"
    private const val GROUP_KEY = "ytmprobe_favorites_group"
    private const val SUMMARY_ID = 199
    private const val ROW_ID_BASE = 200 // 200..207

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YTM Launcher — favorites", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** (Re)posts the picker. Safe to call repeatedly — same IDs, so it replaces rather than stacking. */
    fun show(ctx: Context) {
        channel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val sample = Favorites.all(ctx)
            .filter { it.videoId.isNotBlank() }
            .shuffled()
            .take(ROWS)

        if (sample.isEmpty()) {
            ProbeLog.w(ctx, "favorites notification: no resolved favorites to show")
            return
        }

        sample.forEachIndexed { i, f ->
            val playIntent = Intent(ctx, PlayFavoriteReceiver::class.java).apply {
                action = PlayFavoriteReceiver.ACTION_PLAY
                putExtra(PlayFavoriteReceiver.EXTRA_VIDEO_ID, f.videoId)
                putExtra(PlayFavoriteReceiver.EXTRA_TITLE, f.title)
                putExtra(PlayFavoriteReceiver.EXTRA_ARTIST, f.artist)
            }
            // Distinct request code per row so each PendingIntent is unique —
            // otherwise FLAG_UPDATE_CURRENT would collapse them onto one extras set.
            val pi = PendingIntent.getBroadcast(
                ctx, ROW_ID_BASE + i, playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(f.title)
                .setContentText(f.artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setGroup(GROUP_KEY)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            nm.notify(ROW_ID_BASE + i, n)
        }

        val summary = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle("YTM Launcher")
            .setContentText("${sample.size} favorites ready to play")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        nm.notify(SUMMARY_ID, summary)

        ProbeLog.w(ctx, "favorites notification: posted ${sample.size} row(s)")
    }

    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (i in 0 until ROWS) nm.cancel(ROW_ID_BASE + i)
        nm.cancel(SUMMARY_ID)
    }
}
