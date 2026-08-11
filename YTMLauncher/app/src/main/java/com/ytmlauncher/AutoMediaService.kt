package com.ytmlauncher

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * The Android Auto surface — a legacy MediaBrowserServiceCompat app (category
 * MEDIA), the mechanism AA-Test proved reaches Android Auto's Customize
 * Launcher on sideloaded/"Unknown sources" installs, unlike ytmprobe's
 * androidx.car.app-based ResumeCarAppService (category TEMPLATE, confirmed
 * blocked — see ytmprobe/FINDINGS.md).
 *
 * Browse tree deliberately mirrors LaunchActivity.reload() exactly (one
 * random resolved favorite per genre, reshuffled per load, plus a pinned
 * Resume row) rather than inventing a separate driving-specific scheme —
 * kept simple by explicit choice.
 */
class AutoMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    // Snapshot of whatever was last handed to onLoadChildren's result, so
    // onPlayFromMediaId resolves against exactly what's on screen rather
    // than a fresh (and differently shuffled) read of Favorites. Written on
    // onLoadChildren's background thread, read on the session callback's
    // (main) thread — @Volatile for cross-thread visibility.
    @Volatile
    private var lastRows: Map<String, Favorites.Fav> = emptyMap()

    companion object {
        private const val ROOT_ID = "root"
        private const val RESUME_KEY = "__resume__"

        // Confirmed exact caller from AA-Test's own logs.
        private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    }

    override fun onCreate() {
        super.onCreate()
        ProbeLog.w(this, "AutoMediaService: onCreate")

        mediaSession = MediaSessionCompat(this, "YTMLauncher").apply {
            // FLAG_HANDLES_MEDIA_BUTTONS deliberately dropped (present in
            // AA-Test, harmless only because that was throwaway) — as a
            // permanently-installed, always-active session this flag risks
            // becoming the system's media-button target and stealing
            // steering-wheel/AVRCP play/pause commands from YTM mid-drive.
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    ProbeLog.w(this@AutoMediaService, "AutoMediaService: onPlayFromMediaId($mediaId)")
                    val f = lastRows[mediaId] ?: return
                    playAndReport(f)
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    /**
     * Favor visibility over strictness — a too-strict check fails silently
     * (the app just doesn't appear, mid-drive, no error). Allowlist known
     * callers; anyone else still gets a root, just logged, so an
     * unrecognized caller is diagnosable from the log instead of invisible.
     */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        if (clientPackageName != GEARHEAD_PACKAGE && clientPackageName != packageName) {
            ProbeLog.w(this, "AutoMediaService: onGetRoot from unrecognized caller: $clientPackageName")
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }

        // Favorites.all() hits SharedPreferences, which can block on first
        // access — don't do that synchronously inside this Binder call.
        result.detach()
        Thread {
            val all = Favorites.all(this).filter { it.videoId.isNotBlank() }
            val picks = Favorites.GENRES.mapNotNull { genre ->
                all.filter { it.genre == genre }.shuffled().firstOrNull()
            }

            val seed = Store.loadMixSeed(this)
            val rows = if (seed != null && seed.videoId.isNotBlank()) {
                listOf(
                    Favorites.Fav(
                        key = RESUME_KEY, title = seed.title, artist = seed.artist, album = "",
                        durationMs = 0L, videoId = seed.videoId, addedAt = 0L, lastSeenAt = 0L,
                        playCount = 0, genre = "Resume"
                    )
                ) + picks
            } else picks

            lastRows = rows.associateBy { it.key }

            val items = rows.map { f ->
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(f.key)
                    .setTitle(f.title)
                    .setSubtitle("${f.artist}  ·  ${f.genre}")
                    .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_play}"))
                    .build()
                MediaItem(description, MediaItem.FLAG_PLAYABLE)
            }.toMutableList()

            // No notifyChildrenChanged(ROOT_ID) here — calling it right after
            // answering the very load it would re-trigger caused an infinite
            // gearhead resubscribe loop on real hardware (a fresh Thread per
            // iteration, CPU pegged, browse UI stuck on a spinner until the
            // user backed out of the app entirely). sendResult() alone is
            // the complete answer to a single load request.
            result.sendResult(items)
        }.start()
    }

    /**
     * Checks for a live YTM session itself (rather than trusting
     * Probes.playFavorite's silent fallback) so it can report an honest
     * playback state either way: STATE_STOPPED on the proven-working live-
     * session path (matching AA-Test's confirmed success), or STATE_ERROR
     * with a clear message on the cold-start path, which is genuinely
     * unverified — see ytmprobe's FINDINGS.md and this project's README for
     * why. Better than gearhead's generic "Could not load your selection"
     * either way, and makes the failure attributable in the log.
     */
    private fun playAndReport(f: Favorites.Fav) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f)
                .build()
        )

        val live = Probes.ytmController(this) != null
        ProbeLog.w(this, "AutoMediaService: play ${f.label()}  live=$live")

        // Does the actual work (including the Store.saveMixSeed step, so
        // SessionLogger's auto-continue stays consistent with what was
        // actually played from this surface).
        Probes.playFavorite(this, f.title, f.artist, f.videoId)

        val state = if (live) {
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                .build()
        } else {
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 1f)
                .setErrorMessage("YouTube Music isn't running — open it once")
                .build()
        }
        // A tiny delay before dropping out of BUFFERING — matches AA-Test's
        // proven-working transition shape (gearhead's browse UI appeared to
        // require the session's own state to visibly move, not just the
        // launched app doing something).
        Handler(Looper.getMainLooper()).postDelayed({
            mediaSession.setPlaybackState(state)
        }, 300)
    }

    override fun onDestroy() {
        ProbeLog.w(this, "AutoMediaService: onDestroy")
        mediaSession.release()
        super.onDestroy()
    }
}
