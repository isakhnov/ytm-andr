package com.ytmlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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

    private val pollHandler = Handler(Looper.getMainLooper())

    // Bumped at the start of every playAndReport() call; each poll tick
    // captures its own value and no-ops once superseded, so tapping a
    // second favorite while the first poll is still ticking can't leave two
    // chains racing to call setPlaybackState against each other.
    @Volatile
    private var playGeneration = 0

    // Purely for the "load #N" log line — confirmed real-hardware evidence
    // that gearhead calls onLoadChildren(refresh) once, automatically,
    // right after root loads (before any tap), almost certainly an eager
    // pre-fetch of the browsable folder's contents for preview purposes.
    @Volatile
    private var loadCounter = 0

    companion object {
        private const val ROOT_ID = "root"
        // Not a fixed id — see the class doc on onLoadChildren for why a
        // constant id here was itself the bug.
        private const val REFRESH_PREFIX = "refresh_"
        private const val RESUME_KEY = "__resume__"

        private const val POLL_INTERVAL_MS = 1000L
        private const val POLL_TIMEOUT_MS = 10_000L

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
                    val f = lastRows[mediaId]
                    if (f == null) {
                        ProbeLog.w(this@AutoMediaService, "AutoMediaService: unrecognized mediaId, ignoring")
                        return
                    }
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
        // Hints only — gearhead still owns the final layout (column count,
        // exact tile chrome), but without this the root defaults to a plain
        // list instead of the image-forward grid the tile art below is
        // designed for.
        val extras = Bundle().apply {
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    /**
     * Answers ROOT_ID and any REFRESH_PREFIX-tagged id with a fresh shuffle
     * — Refresh is a FLAG_BROWSABLE node, not a FLAG_PLAYABLE one: on real
     * hardware, tapping any playable item unconditionally sent gearhead to
     * its now-playing template first, before onPlayFromMediaId even runs —
     * so a playable Refresh with nothing behind it "failed" exactly like a
     * normal selection tap would. Its result omits a further Refresh tile
     * (only ROOT_ID gets one) — offering one there let real-hardware taps
     * drill an unbounded number of levels deeper, with no way back except
     * repeated Back presses.
     *
     * The id is minted fresh (a timestamp suffix) every time ROOT_ID is
     * built, not a fixed "refresh" constant like the first version of this
     * had. Real-hardware evidence (a log showing onLoadChildren(refresh)
     * firing exactly once, automatically, right after root — before any
     * tap) points to gearhead eagerly pre-fetching a browsable folder's
     * contents for preview purposes, then treating a later tap on that same
     * id as already-answered and never calling onLoadChildren again. A
     * constant id can only ever be genuinely fresh once; a minted-per-load
     * id means gearhead has never subscribed to *this* one before, so the
     * eager pre-fetch (if it still happens) can't go stale by the time it's
     * actually tapped.
     */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT_ID && !parentId.startsWith(REFRESH_PREFIX)) {
            result.sendResult(mutableListOf())
            return
        }
        val loadNum = ++loadCounter
        ProbeLog.w(this, "AutoMediaService: onLoadChildren($parentId) load #$loadNum")

        // Favorites.all() hits SharedPreferences, which can block on first
        // access — don't do that synchronously inside this Binder call.
        result.detach()
        Thread {
            val all = Favorites.all(this).filter { it.videoId.isNotBlank() }
            val seed = Store.loadMixSeed(this)

            // Exclude the Resume track from every genre's candidate pool
            // before shuffling, not after picking — so a genre with other
            // eligible tracks still gets a tile (just a different one)
            // instead of silently rendering the same track twice. A genre
            // only drops out entirely if the Resume track was its sole
            // resolved favorite.
            val excludeKey = seed?.let { Favorites.keyFor(it.title, it.artist) }
            val picks = Favorites.GENRES.mapNotNull { genre ->
                all.filter { it.genre == genre && it.key != excludeKey }
                    .shuffled().firstOrNull()
            }

            val rows = if (seed != null && seed.videoId.isNotBlank()) {
                listOf(
                    Favorites.Fav(
                        key = RESUME_KEY, title = seed.title, artist = seed.artist, album = "",
                        durationMs = 0L, videoId = seed.videoId, addedAt = 0L, lastSeenAt = 0L,
                        playCount = 0, genre = "Resume"
                    )
                ) + picks
            } else picks

            // Merged, not replaced — a confirmed real-hardware bug: gearhead
            // was calling onLoadChildren(refresh) automatically (an eager
            // pre-fetch, see this method's class doc) while the user was
            // still looking at ROOT's already-rendered tiles. Overwriting
            // lastRows here meant a tap on a tile visibly on screen could
            // resolve against a *different* screen's shuffle and silently
            // no-op ("unrecognized mediaId, ignoring" — confirmed in the
            // log for a real, on-screen favorite). Accumulating means any
            // tile ever rendered to the user, from either screen, stays
            // resolvable regardless of what loads in the background after.
            lastRows = lastRows + rows.associateBy { it.key }

            // Every tile's art now gets fetched and composited by this app
            // (a ring for Resume, a genre label for everything else)
            // instead of left to gearhead's own async image loader via a
            // plain setIconUri — fetching sequentially would multiply this
            // screen's load time by roughly the tile count, so run them
            // concurrently and wait for the slowest one instead of the sum
            // of all of them.
            val pool = Executors.newFixedThreadPool(rows.size.coerceIn(1, 12))
            val arts = try {
                rows.map { f ->
                    pool.submit(Callable { if (f.key == RESUME_KEY) ringedArt(f.videoId) else genreTileArt(f.videoId, f.genre) })
                }.map { it.get() }
            } finally {
                pool.shutdown()
            }

            val items = mutableListOf<MediaItem>()

            // First position, not last: the service only controls list
            // order, not which row/column a position lands in on a given
            // head unit's grid — "last" was tried and didn't land at
            // top-right on the GLE63s. "First" is the only corner this app
            // can deterministically target (top-left of the grid), so it
            // replaces that guess. Only offered at the true root — see this
            // method's class doc for why REFRESH_ID's own result doesn't
            // repeat it. Our own drawable, so the gold ring marking it (and
            // Resume, below) as a control rather than a track is just a
            // redesign of ic_refresh.xml — no compositing needed since we
            // already own this bitmap outright.
            if (parentId == ROOT_ID) {
                val refreshDescription = MediaDescriptionCompat.Builder()
                    .setMediaId("$REFRESH_PREFIX${System.currentTimeMillis()}")
                    .setTitle("Refresh")
                    .setSubtitle("Reshuffle favorites")
                    .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_refresh}"))
                    .build()
                items.add(MediaItem(refreshDescription, MediaItem.FLAG_BROWSABLE))
            }

            rows.forEachIndexed { i, f ->
                val artUri = "https://i.ytimg.com/vi/${f.videoId}/hqdefault.jpg"
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(f.key)
                    .setTitle(f.title)
                    .setSubtitle("${f.artist}  ·  ${f.genre}")
                    .apply {
                        val art = arts[i]
                        // Falls back to the plain remote URI (gearhead
                        // fetches it itself) if the fetch/composite failed —
                        // same as before this app started compositing art
                        // itself, just no ring/label baked in that one time.
                        if (art != null) setIconBitmap(art) else setIconUri(Uri.parse(artUri))
                    }
                    .build()
                items.add(MediaItem(description, MediaItem.FLAG_PLAYABLE))
            }

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
     * The network fetch, not the compositing, is what makes tile art slow —
     * a first cut at this refetched the same thumbnail once per tile kind
     * (ring vs. genre label) on every single onLoadChildren call, including
     * Refresh, which recomputes the whole screen. Caching the decoded
     * source bitmap by videoId means only genuinely new tracks pay for a
     * network round trip; ringedArt/genreTileArt still composite fresh
     * every time since the ring/label drawing itself is cheap (~ms) and the
     * two need different pixels. Unbounded for the lifetime of the process
     * — the working set is just however many distinct favorites have been
     * shown, not worth evicting for an app this size.
     */
    private val artCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private fun fetchSourceArt(videoId: String): Bitmap? {
        artCache[videoId]?.let { return it }
        return try {
            val connection = URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            artCache[videoId] = bitmap
            bitmap
        } catch (e: Exception) {
            ProbeLog.w(this, "AutoMediaService: art fetch failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * Burns a gold ring into the shared source art, so Resume reads as a
     * control rather than an ordinary track tile even though (unlike
     * Refresh) its art is a real remote photo, not a bitmap this app
     * already owns. Runs on onLoadChildren's background thread. Best-effort:
     * any failure (network, decode) returns null and the caller falls back
     * to the plain remote URI.
     */
    private fun ringedArt(videoId: String): Bitmap? {
        return try {
            val source = fetchSourceArt(videoId) ?: return null
            val ringed = source.copy(Bitmap.Config.ARGB_8888, true)
            val strokeWidth = ringed.width * 0.05f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                color = Color.parseColor("#E7B455")
            }
            val inset = strokeWidth / 2
            Canvas(ringed).drawRect(inset, inset, ringed.width - inset, ringed.height - inset, paint)
            ringed
        } catch (e: Exception) {
            ProbeLog.w(this, "AutoMediaService: resume art ring failed: ${e.message}")
            null
        }
    }

    /**
     * Same fetch as ringedArt, but bakes a bottom gradient scrim and the
     * genre name directly into the pixels instead of a ring. Requested
     * after real-hardware testing where the genre — carried only in
     * setSubtitle() — either wasn't rendered on the tile at all or wasn't
     * legible against bright art; gearhead owns whatever chrome it puts
     * around a tile's title/subtitle, so the only way to *guarantee*
     * legible genre text is to own those pixels ourselves, the same
     * reasoning as the Resume ring above. Best-effort: any failure returns
     * null and the caller falls back to the plain remote URI, same as
     * before this app started compositing art itself.
     */
    private fun genreTileArt(videoId: String, genre: String): Bitmap? {
        return try {
            val source = fetchSourceArt(videoId) ?: return null
            val out = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)

            val scrimTop = out.height * 0.58f
            canvas.drawRect(
                0f, scrimTop, out.width.toFloat(), out.height.toFloat(),
                Paint().apply {
                    shader = LinearGradient(
                        0f, scrimTop, 0f, out.height.toFloat(),
                        Color.TRANSPARENT, Color.argb(220, 0, 0, 0), Shader.TileMode.CLAMP
                    )
                }
            )

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = out.width * 0.095f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                setShadowLayer(out.width * 0.015f, 0f, 1f, Color.argb(200, 0, 0, 0))
            }
            val padding = out.width * 0.06f
            canvas.drawText(genre.uppercase(), padding, out.height - padding, textPaint)
            out
        } catch (e: Exception) {
            ProbeLog.w(this, "AutoMediaService: genre tile art failed: ${e.message}")
            null
        }
    }

    /**
     * Checks for a live YTM session itself (rather than trusting
     * Probes.playFavorite's silent fallback) so it can report an honest
     * playback state either way: STATE_STOPPED once a live session is
     * confirmed to have actually started playing (matching AA-Test's
     * proven success mechanism), or STATE_ERROR with a clear message when
     * that can't be confirmed. Better than gearhead's generic "Could not
     * load your selection" either way, and makes the failure attributable
     * in the log.
     *
     * Sets real track metadata (title/artist/art) alongside STATE_BUFFERING
     * so gearhead's own now-playing template has something real to render
     * instead of a blank screen while it waits — this app can't draw a
     * custom animation on the head unit itself (see AutoMediaService's
     * class doc / this project's CLAUDE.md), only feed the host's own
     * template real data and honestly-timed state transitions.
     */
    private fun playAndReport(f: Favorites.Fav) {
        val generation = ++playGeneration

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, f.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, f.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, f.album)
                // Public, unauthenticated YouTube thumbnail URL pattern — no
                // new dependency or InnerTube call. Gearhead's own image
                // loader fetches it, not this process, the same division of
                // responsibility the bundled ic_play icon URI already
                // relies on, just remote instead of a local resource this
                // time — first remote URI this codebase has fed a
                // MediaSession, unverified until seen on real hardware.
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, "https://i.ytimg.com/vi/${f.videoId}/hqdefault.jpg")
                .build()
        )
        mediaSession.setPlaybackState(buildState(PlaybackStateCompat.STATE_BUFFERING))

        val live = Probes.ytmController(this) != null
        ProbeLog.w(this, "AutoMediaService: play ${f.label()}  live=$live")

        // Does the actual work (including the Store.saveMixSeed step, so
        // SessionLogger's auto-continue stays consistent with what was
        // actually played from this surface).
        Probes.playFavorite(this, f.title, f.artist, f.videoId)

        if (!live) {
            // Genuinely unverified cold-start path — see ytmprobe/FINDINGS.md
            // and this project's README/CLAUDE.md for why. No point polling
            // here: there was no session to command in the first place.
            mediaSession.setPlaybackState(
                buildState(PlaybackStateCompat.STATE_ERROR, "YouTube Music isn't running — open it once")
            )
            return
        }

        pollHandler.postDelayed(
            { pollForConfirmedPlayback(generation, SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS) },
            POLL_INTERVAL_MS
        )
    }

    /**
     * Ticks every second (per the user's own preference over a longer
     * interval) checking whether YTM's session actually reached
     * STATE_PLAYING, instead of the fixed ~300ms delay this replaced that
     * assumed success the instant a live session merely existed. The very
     * first check happens after the first tick, not at t=0, to reduce the
     * odds of reading YTM's pre-tap state (e.g. still playing a previous
     * track) as a false-positive confirmation — YTM exposes no videoId
     * anywhere (this project's founding constraint, see ytmprobe/FINDINGS.md),
     * so exact-track confirmation past "state == PLAYING" isn't possible;
     * accepted as a known, rare-edge-case limitation rather than solved.
     */
    private fun pollForConfirmedPlayback(generation: Int, deadlineElapsedRealtime: Long) {
        if (generation != playGeneration) return // superseded by a newer tap

        val ytmState = Probes.ytmController(this)?.playbackState?.state
        ProbeLog.w(this, "AutoMediaService: poll gen=$generation ytmState=$ytmState")

        if (ytmState == PlaybackStateCompat.STATE_PLAYING) {
            ProbeLog.w(this, "AutoMediaService: confirmed playing")
            mediaSession.setPlaybackState(buildState(PlaybackStateCompat.STATE_STOPPED))
            // Tried mediaSession.isActive = false here on the theory that
            // relinquishing focus would let gearhead fall back to browse or
            // to YTM's own screen instead of staying parked on this app's
            // dead "stopped" screen. Real-hardware testing showed it broke
            // something more important instead: the next tap on a different
            // favorite stopped changing the track at all. Reverted — a
            // known, unresolved cosmetic issue (the dead screen) beats a
            // broken core feature (playback control). If this gets
            // revisited, it needs to be verified against exactly this
            // regression, not just against the screen it was meant to fix.
            return
        }

        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) {
            ProbeLog.w(this, "AutoMediaService: poll timed out without confirming playback")
            mediaSession.setPlaybackState(
                buildState(PlaybackStateCompat.STATE_ERROR, "Didn't confirm playback — check YouTube Music")
            )
            return
        }

        pollHandler.postDelayed(
            { pollForConfirmedPlayback(generation, deadlineElapsedRealtime) },
            POLL_INTERVAL_MS
        )
    }

    private fun buildState(state: Int, errorMessage: String? = null): PlaybackStateCompat {
        val builder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY)
            .setState(state, 0, 1f)
        if (errorMessage != null) builder.setErrorMessage(errorMessage)
        return builder.build()
    }

    override fun onDestroy() {
        ProbeLog.w(this, "AutoMediaService: onDestroy")
        // The service can be torn down mid-poll (user backs out of the app)
        // — drop any pending tick so it can't fire against a released session.
        pollHandler.removeCallbacksAndMessages(null)
        mediaSession.release()
        super.onDestroy()
    }
}
