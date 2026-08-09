package com.ytmprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by a tap on one of FavoritesNotifier's lock-screen rows. Plays
 * directly — no Activity launch — so a tap works from the lock screen
 * without unlocking or opening the app, same as the notification's own
 * setContentIntent firing a broadcast rather than starting anything visible.
 */
class PlayFavoriteReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY = "com.ytmprobe.action.PLAY_FAVORITE"
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PLAY) return
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { it.isNotBlank() } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""

        ProbeLog.w(context, "notification play: $title — $artist  $videoId")
        Store.saveMixSeed(context, title, artist, videoId)
        Probes.playOrLaunch(context, videoId)
    }
}
