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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Single tracking service. Two responsibilities:
 *
 *   1. Session lifecycle — logs YTM sessions appearing/disappearing, which
 *      answers the car-connect timing question in FINDINGS.
 *   2. Track observation — polls every 10s and applies the favorites capture
 *      rules from SPEC-favorites.md.
 *
 * There is no separate "poller". This service IS tracking.
 */
class SessionLogger : Service() {

    companion object {
        const val POLL_MS = 10_000L

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
            } else {
                ProbeLog.w(this, "<<< YTM session gone")
                currentKey = null
            }
            lastYtm = ytm
        }
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
                NotificationChannel(id, "YTM Probe", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, id)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("YTM Probe — tracking")
            .setContentText("watching for likes")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
