package com.ytmlauncher

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Fired by a tap on one of FavoritesNotifier's lock-screen rows.
 *
 * This has to be a real Activity, not a BroadcastReceiver: since targetSdk
 * 31, Android blocks "notification trampolines" — a notification's
 * PendingIntent.getBroadcast() firing a receiver that itself calls
 * startActivity() — silently, with no exception and a log line under
 * ActivityTaskManager, not our own tag. Only the cold-start path (when YTM
 * is dead and Probes.playFavorite falls back to a deep-link launch) needs to
 * start an activity at all; routing every tap through a real Activity here
 * avoids the trampoline block for that case.
 *
 * FLAG_SHOW_WHEN_LOCKED lets this invisible activity actually run on top of
 * the lock screen without forcing an unlock prompt itself — when YTM
 * already has a live session, playFavorite only commands it via the
 * MediaController, no further activity launch, so the whole thing completes
 * with no unlock needed. If YTM is dead, the cold-start fallback starts
 * YTM's own activity next — launching a foreign app's real UI from a
 * securely locked device is what actually triggers Android's own keyguard
 * prompt at that point, not anything this activity does or could avoid.
 *
 * Theme.NoDisplay (manifest) means nothing is ever drawn; finish() right
 * after the work is done so it never lingers as a back-stack entry or shows
 * in Recents (also excludeFromRecents/noHistory, manifest).
 */
class PlayFavoriteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { it.isNotBlank() }
        if (videoId != null) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""

            ProbeLog.w(this, "notification play: $title — $artist  $videoId")
            Probes.playFavorite(this, title, artist, videoId)
            FavoritesNotifier.show(this)
        }
        finish()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
    }
}
