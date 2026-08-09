package com.ytmprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Single tracking service. Three responsibilities:
 *
 *   1. Session lifecycle — logs YTM sessions appearing/disappearing, which
 *      answers the car-connect timing question in FINDINGS.
 *   2. Track observation — polls every 10s and applies the favorites capture
 *      rules from SPEC-favorites.md.
 *   3. Auto-continue — if YTM was genuinely playing (not paused/silent)
 *      within ~60s of its session disappearing (car/AA shutting down
 *      mid-song, not a deliberate stop), replay the mix seed — the favorite
 *      that was tapped to start the current mix, not the exact last song —
 *      the next time a YTM session appears. One-shot: consumed the moment
 *      it fires, doesn't refire on every subsequent session appearance.
 *
 * There is no separate "poller". This service IS tracking.
 */
class SessionLogger : Service() {

    companion object {
        const val POLL_MS = 10_000L
        const val AUTO_CONTINUE_WINDOW_MS = 60_000L

        /** Reflects real service state, not the last button press. */
        @Volatile
        var running: Boolean = false
            private set

        /** Set by the UI so it can refresh its indicator on state change. */
        @Volatile
        var onStateChange: (() -> Unit)? = null
    }

    private lateinit var msm: MediaSessionManager
    private var lastYtm = false
    private var lastCount = -1

    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    /** Key of the track currently observed, for once-per-play counting. */
    private var currentKey: String? = null

    /** Wall-clock time of the last poll that saw YTM actually STATE_PLAYING. */
    private var lastPlayingAt: Long = 0L

    private val listener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            reportSessions(controllers ?: emptyList())
        }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())

        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            msm.addOnActiveSessionsChangedListener(listener, Probes.listenerComponent(this))
            ProbeLog.section(this, "TRACKING started")
            ProbeLog.w(this, "  poll every ${POLL_MS / 1000}s")
            ProbeLog.w(this, "  ${Favorites.count(this)} favorite(s) stored")
            reportSessions(msm.getActiveSessions(Probes.listenerComponent(this)))
        } catch (e: SecurityException) {
            ProbeLog.w(this, "TRACKING: no Notification Access — cannot observe")
            stopSelf()
            return
        }

        FavoritesNotifier.show(this)

        val t = HandlerThread("ytm-poll").also { it.start() }
        pollThread = t
        pollHandler = Handler(t.looper).also { it.post(pollTask) }

        running = true
        onStateChange?.invoke()
    }

    override fun onDestroy() {
        running = false
        onStateChange?.invoke()
        runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
        runCatching { pollHandler?.removeCallbacks(pollTask) }
        runCatching { pollThread?.quitSafely() }
        FavoritesNotifier.cancelAll(this)
        ProbeLog.w(this, "TRACKING stopped")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    // ----------------------------------------------------------- session log

    private fun reportSessions(controllers: List<MediaController>) {
        val ytm = controllers.any { it.packageName == Probes.YTM }

        if (controllers.size != lastCount) {
            ProbeLog.w(this, "sessions=${controllers.size} " +
                    "[${controllers.joinToString { it.packageName.substringAfterLast('.') }}]")
            lastCount = controllers.size
        }

        if (ytm != lastYtm) {
            if (ytm) {
                val c = controllers.first { it.packageName == Probes.YTM }
                ProbeLog.w(this, ">>> YTM SESSION APPEARED  state=${c.playbackState?.state} " +
                        "title=${c.metadata?.description?.title}")
                maybeAutoContinue(c)
            } else {
                ProbeLog.w(this, "<<< YTM session gone")
                currentKey = null
                val playedRecently = lastPlayingAt > 0 &&
                        System.currentTimeMillis() - lastPlayingAt <= AUTO_CONTINUE_WINDOW_MS
                Store.setAutoContinueEligible(this, playedRecently)
                if (playedRecently) {
                    ProbeLog.w(this, "  (was playing recently — auto-continue armed for next session)")
                }
            }
            lastYtm = ytm
        }
    }

    /**
     * Fires at most once per eligible disappearance — cleared immediately so
     * a session that appears, drops, and reappears in quick succession
     * doesn't replay the seed on every reappearance.
     */
    private fun maybeAutoContinue(c: MediaController) {
        if (!Store.isAutoContinueEligible(this)) return
        Store.setAutoContinueEligible(this, false)
        val seed = Store.loadMixSeed(this)
        if (seed == null || seed.videoId.isBlank()) {
            ProbeLog.w(this, "  auto-continue: armed but no mix seed stored — skipping")
            return
        }
        ProbeLog.w(this, "  >>> auto-continue: replaying seed ${seed.title} — ${seed.artist}")
        val uri = Uri.parse("https://music.youtube.com/watch?v=${seed.videoId}")
        runCatching { c.transportControls.playFromUri(uri, Bundle()) }
            .onFailure { ProbeLog.w(this, "  auto-continue failed: $it") }
    }

    // ---------------------------------------------------------------- poll

    private val pollTask = object : Runnable {
        override fun run() {
            runCatching { pollOnce() }
                .onFailure { ProbeLog.w(this@SessionLogger, "poll error: $it") }
            pollHandler?.postDelayed(this, POLL_MS)
        }
    }

    private fun pollOnce() {
        // Any early return here means "no observation" — never "unliked".
        val c = Probes.ytmController(this) ?: return

        // Tracked independently of everything below — auto-continue only
        // cares whether YTM was actively playing, not whether this poll also
        // had a resolvable title/rating.
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            lastPlayingAt = System.currentTimeMillis()
        }

        val md = c.metadata ?: return

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        if (title.isBlank()) return

        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val rating = md.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return

        // isRated=false is inert: a never-rated track and a cleared rating are
        // indistinguishable, so neither may delete a favorite.
        val liked: Boolean? =
            if (!rating.isRated) null
            else runCatching { rating.isThumbUp }.getOrNull()

        val key = Favorites.keyFor(title, artist)
        val isNewPlay = key != currentKey
        if (isNewPlay) currentKey = key

        when (Favorites.observe(this, title, artist, album, durationMs, liked, isNewPlay)) {
            Favorites.Outcome.ADDED ->
                ProbeLog.w(this, "  ++ FAVORITE added: $title — $artist " +
                        "(${Favorites.count(this)} total)")
            Favorites.Outcome.REMOVED ->
                ProbeLog.w(this, "  -- favorite removed: $title — $artist " +
                        "(${Favorites.count(this)} total)")
            Favorites.Outcome.REFRESHED ->
                if (isNewPlay) ProbeLog.w(this, "  .. favorite played: $title")
            Favorites.Outcome.NONE ->
                if (isNewPlay) ProbeLog.w(this, "  observing: $title — $artist")
        }
    }

    // -------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val id = "ytmprobe"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(id, "YTM Launcher", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, id)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("YTM Launcher — tracking")
            .setContentText("watching for likes")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            // A foreground service must have a notification, but nothing
            // requires it on the lock screen — VISIBILITY_SECRET keeps it out
            // of that surface entirely (unlike PRIVATE, which still shows a
            // redacted placeholder), leaving only FavoritesNotifier's picker
            // there, the one row of notifications actually meant to be seen.
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }
}
