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
 * Browse tree deliberately mirrors LaunchActivity.reload() (one random
 * resolved favorite per genre, reshuffled per load) rather than inventing a
 * separate driving-specific scheme — kept simple by explicit choice. There
 * is no separate "Resume" tile: whichever genre the mix seed's track
 * belongs to has *that* track forced into its own genre's slot (instead of
 * a random pick) and moved first, with a gold ring baked into its art —
 * same title/subtitle/mediaId shape as every other tile, just relocated and
 * bordered, since a distinct "Resume" tile with its own text was
 * real-hardware-confirmed confusing when it landed as a second full-size
 * tile rather than reading as "this is where you left off."
 *
 * Refresh has two selectable designs (Store.RefreshModel, chosen from
 * Settings, IN_PLACE by default) rather than one fixed one — see
 * onLoadChildren's doc for both and the real-hardware history behind them,
 * including a third design (EXTRA_BUTTON) that was built, tested, and
 * removed outright as structurally unfixable.
 *
 * Reclassify (genre reassignment) lives on the now-playing template, not
 * the browse grid — see handleReclassifyTap's doc for why that placement
 * was chosen deliberately over a dedicated picker screen: the track that
 * needs a new genre is already the one on screen the instant a tile is
 * tapped, so there's nothing to re-select. Unverified until tested on real
 * hardware — the underlying custom-action mechanism is the same one
 * EXTRA_BUTTON already confirmed *renders and is tappable* on this head
 * unit, just applied to an effect (metadata on the same screen) instead of
 * the one (repainting a different screen) that made EXTRA_BUTTON fail.
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

    // Whichever track is actually on the now-playing screen right now —
    // what handleReclassifyTap acts on. Set at the top of playAndReport
    // (the instant a tile is tapped, not gated on playback confirmation, so
    // reclassify is available the whole time the buffering/confirming
    // screen is showing), cleared by handleRefreshTap since that screen
    // shows "Refreshed" rather than any specific track's info — no track on
    // screen, no reclassify action offered. Deliberately not lastRows
    // (every tile ever shown, keyed by mediaId): this is the one currently
    // in focus, not a lookup table.
    @Volatile
    private var currentFav: Favorites.Fav? = null

    private val pollHandler = Handler(Looper.getMainLooper())

    // Bumped at the start of every playAndReport() call; each poll tick
    // captures its own value and no-ops once superseded, so tapping a
    // second favorite while the first poll is still ticking can't leave two
    // chains racing to call setPlaybackState against each other.
    @Volatile
    private var playGeneration = 0

    // Purely for the "load #N" log line.
    @Volatile
    private var loadCounter = 0

    companion object {
        private const val ROOT_ID = "root"

        // Model LEGACY's Refresh tile id — a single FLAG_PLAYABLE tile,
        // fixed id is fine here since (unlike IN_PLACE's browsable node)
        // gearhead never treats a playable tap as an already-answered
        // subscription.
        private const val REFRESH_KEY = "__refresh__"

        // Model IN_PLACE's Refresh tile id prefix — minted fresh per load
        // (see onLoadChildren) so gearhead can't treat a repeat tap as an
        // already-answered subscription, the same reasoning a fixed id
        // failed on the first time this design was tried (before LEGACY
        // existed).
        private const val REFRESH_PREFIX = "refresh_"

        // Reclassify's trigger — a PlaybackStateCompat custom action on the
        // now-playing template, the same mechanism EXTRA_BUTTON used (and
        // confirmed renders/is tappable on real hardware) before it was
        // removed for a different reason. See handleReclassifyTap.
        private const val ACTION_RECLASSIFY = "com.ytmlauncher.action.RECLASSIFY"

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
                    .withReclassifyAction()
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    ProbeLog.w(this@AutoMediaService, "AutoMediaService: onPlayFromMediaId($mediaId)")
                    if (mediaId == REFRESH_KEY) {
                        handleRefreshTap()
                        return
                    }
                    val f = lastRows[mediaId]
                    if (f == null) {
                        ProbeLog.w(this@AutoMediaService, "AutoMediaService: unrecognized mediaId, ignoring")
                        return
                    }
                    playAndReport(f)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    ProbeLog.w(this@AutoMediaService, "AutoMediaService: onCustomAction($action)")
                    if (action == ACTION_RECLASSIFY) {
                        handleReclassifyTap()
                    }
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
        // designed for. CATEGORY_GRID_ITEM, not the plain GRID_ITEM this had
        // before: on the CX-60 (a smaller/older head unit than the GLE63s
        // this was first tuned on) GRID_ITEM rendered only two oversized
        // tiles per row. CATEGORY_GRID_ITEM is the only other density this
        // API exposes — there is no numeric tile-size hint, this is a style
        // enum, not a dimension — and is documented for denser tile shelfs.
        // Unverified on real hardware yet; if it reflows into a horizontal
        // scroller instead of a denser vertical grid, that's the tradeoff
        // of the only lever available (see this file's other "platform
        // boundary" notes on what gearhead does vs. doesn't let this app
        // control).
        val extras = Bundle().apply {
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
            )
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    /**
     * Refresh went through two failed designs before LEGACY (the first
     * design that actually worked end to end on real hardware), both worth
     * recording so a future session doesn't retry them as-is:
     *
     * 1. A single fixed FLAG_PLAYABLE tile, doing nothing to playback
     *    state. Tapping any playable item unconditionally sends gearhead to
     *    its now-playing template first, before onPlayFromMediaId even
     *    runs — a playable Refresh with nothing behind it "failed" exactly
     *    like a normal selection tap, indistinguishable from a bug.
     * 2. FLAG_BROWSABLE with a fixed id ("refresh"). Real-hardware testing
     *    showed pressing it did nothing visible at all — no screen change,
     *    repeat taps never re-triggered onLoadChildren. Suspected cause:
     *    gearhead treating a fixed, previously-seen id as an
     *    already-answered subscription and never re-querying it — which is
     *    why IN_PLACE below mints a fresh id every load instead.
     *
     * IMPORTANT — a claim that used to live here was WRONG and cost a real
     * regression: it asserted that Back-from-the-playback-template
     * automatically forces gearhead to re-call onLoadChildren(ROOT_ID) on
     * its own, with no explicit notifyChildrenChanged needed. Real-device
     * log evidence (probe.log, 2026-08-30 12:59-13:01) disproves this:
     * across 4 Refresh taps and 2 ordinary track taps in one session,
     * onLoadChildren(root) was never invoked again after gearhead's own two
     * automatic calls at startup. Conclusion: gearhead never re-queries a
     * subscribed browse node on its own. notifyChildrenChanged(parentId) —
     * called explicitly by this app — is the only thing that does it, for
     * any parentId, at any time. All three models below, and playAndReport
     * for ordinary track taps, rely on that.
     *
     * The two live designs, chosen via Store.getRefreshModel() (see its
     * doc for the full picture, including why a third design —
     * EXTRA_BUTTON, a custom now-playing-template action — was built,
     * tested, and then removed rather than kept as an option — this is the
     * onLoadChildren-specific half):
     *
     *  LEGACY — a fixed FLAG_PLAYABLE tile (REFRESH_KEY). Real-hardware
     *  verified working: see handleRefreshTap.
     *
     *  IN_PLACE (default) — a FLAG_BROWSABLE tile, id minted fresh every load
     *  (REFRESH_PREFIX + a timestamp) so a repeat tap can never look like an
     *  already-answered subscription to gearhead. This method answers any
     *  REFRESH_PREFIX-prefixed parentId with the exact same freshly-shuffled
     *  content as ROOT_ID (same code path below, just a different id on the
     *  Refresh tile it emits) — so the pushed child screen looks identical
     *  to root, just reshuffled. Known tradeoff, kept deliberately rather
     *  than solved: each tap pushes one more browse-stack frame, so
     *  refreshing N times from inside a child screen costs N extra Back
     *  presses to reach Home. Root itself stays stale until the user backs
     *  all the way out to it — notifyChildrenChanged below only targets
     *  ROOT_ID, not whichever child id is currently on screen.
     */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val isRefreshChild = parentId.startsWith(REFRESH_PREFIX)
        if (parentId != ROOT_ID && !isRefreshChild) {
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

            // The currently-playing (or last-played) track, if it's itself
            // a favorite with a genre tagged — used below to relocate that
            // genre's tile instead of adding a separate "Resume" tile. Null
            // whenever there's no seed, or the seed's track isn't a tagged
            // favorite; both leave the grid in plain GENRES order with no
            // highlighted tile, same as before there was any seed at all.
            val seedKey = seed?.let { Favorites.keyFor(it.title, it.artist) }
            val seedFav = seedKey?.let { k -> all.firstOrNull { it.key == k } }
            val seedGenre = seedFav?.genre?.takeIf { it.isNotBlank() }

            // seedGenre's slot is forced to seedFav rather than shuffled —
            // that's the "now playing" tile. Every other genre still
            // excludes seedKey from its own candidate pool before shuffling
            // (not after picking), so a genre with other eligible tracks
            // still gets a tile instead of silently duplicating the seed's
            // track, and only drops out entirely if the seed was its sole
            // resolved favorite.
            val orderedGenres = if (seedGenre != null) {
                listOf(seedGenre) + Favorites.GENRES.filter { it != seedGenre }
            } else Favorites.GENRES
            val rows = orderedGenres.mapNotNull { genre ->
                if (genre == seedGenre) seedFav
                else all.filter { it.genre == genre && it.key != seedKey }.shuffled().firstOrNull()
            }

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
            // (a genre label always, plus a gold ring on whichever tile is
            // seedFav) instead of left to gearhead's own async image loader
            // via a plain setIconUri — fetching sequentially would multiply
            // this screen's load time by roughly the tile count, so run
            // them concurrently and wait for the slowest one instead of the
            // sum of all of them.
            val pool = Executors.newFixedThreadPool(rows.size.coerceIn(1, 12))
            val arts = try {
                rows.map { f ->
                    pool.submit(Callable { genreTileArt(f.videoId, f.genre, ringed = f.key == seedFav?.key) })
                }.map { it.get() }
            } finally {
                pool.shutdown()
            }

            val items = mutableListOf<MediaItem>()

            // First position: the service only controls list order, not
            // which row/column a position lands in on a given head unit's
            // grid — "first" is the only corner this app can deterministically
            // target (top-left). LEGACY uses a fixed id and FLAG_PLAYABLE;
            // IN_PLACE uses a freshly-minted id and FLAG_BROWSABLE — see
            // this method's class doc for both. Our own drawable either
            // way, so the gold ring marking it as a control rather than a
            // track is just a redesign of ic_refresh.xml — no compositing
            // needed since we already own this bitmap outright. The
            // now-playing tile (which follows it, when seedGenre is
            // non-null) gets its ring composited onto real fetched art
            // instead, since unlike Refresh it's a real track.
            val refreshModel = Store.getRefreshModel(this)
            val refreshId = if (refreshModel == Store.RefreshModel.IN_PLACE)
                "$REFRESH_PREFIX${System.currentTimeMillis()}" else REFRESH_KEY
            val refreshFlag = if (refreshModel == Store.RefreshModel.IN_PLACE)
                MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE
            val refreshDescription = MediaDescriptionCompat.Builder()
                .setMediaId(refreshId)
                .setTitle("Refresh")
                .setSubtitle("Reshuffle favorites")
                .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_refresh}"))
                .build()
            items.add(MediaItem(refreshDescription, refreshFlag))

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
     * network round trip; genreTileArt still composites fresh every time
     * since the ring/label drawing itself is cheap (~ms). Unbounded for the
     * lifetime of the process
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
     * Bakes a bottom gradient scrim and the genre name directly into the
     * pixels. Requested after real-hardware testing where the genre —
     * carried only in setSubtitle() — either wasn't rendered on the tile at
     * all or wasn't legible against bright art; gearhead owns whatever
     * chrome it puts around a tile's title/subtitle, so the only way to
     * *guarantee* legible genre text is to own those pixels ourselves.
     * `ringed` additionally burns the same gold stroke Refresh's drawable
     * carries onto this tile, marking it as "now playing" — used for
     * exactly one tile per load (whichever one is seedFav in
     * onLoadChildren), never as a second separate tile with different text,
     * so the now-playing tile stays visually identical to every other genre
     * tile aside from the border. Best-effort: any failure returns null and
     * the caller falls back to the plain remote URI, same as before this
     * app started compositing art itself.
     */
    private fun genreTileArt(videoId: String, genre: String, ringed: Boolean = false): Bitmap? {
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

            if (ringed) {
                val strokeWidth = out.width * 0.05f
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    color = Color.parseColor("#E7B455")
                }
                val inset = strokeWidth / 2
                canvas.drawRect(inset, inset, out.width - inset, out.height - inset, ringPaint)
            }

            out
        } catch (e: Exception) {
            ProbeLog.w(this, "AutoMediaService: genre tile art failed: ${e.message}")
            null
        }
    }

    /**
     * Model LEGACY only. Real-hardware verified working end to end (this is
     * the design actually shipped and confirmed by the user before IN_PLACE
     * was built and made the default instead). Forces gearhead's
     * now-playing template (unavoidable for any FLAG_PLAYABLE tap) with
     * "Refreshed" metadata rather than leaving whatever the previous
     * track's title was sitting there — the confused-looking "frozen
     * 0:00/0:00" screen this replaced was a real track's stale metadata
     * paired with STATE_STOPPED, not a truly blank state; labeling it
     * removes the ambiguity even though the screen transition itself isn't
     * avoidable in this model (that's exactly what IN_PLACE trades a new
     * browse-stack frame plus an unremovable back arrow to avoid — see
     * Store.RefreshModel). notifyChildrenChanged(ROOT_ID) is the only thing
     * that makes gearhead
     * re-query root at all (see onLoadChildren's class doc) — safe to call
     * here since it's one real tap driving one call, not a self-triggering
     * loop.
     */
    private fun handleRefreshTap() {
        ProbeLog.w(this, "AutoMediaService: refresh tapped")
        notifyChildrenChanged(ROOT_ID)
        // No specific track is on screen after this — clears the Reclassify
        // action along with it (see withReclassifyAction/currentFav's doc).
        currentFav = null
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Refreshed")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Tap ← to browse the new picks")
                .build()
        )
        mediaSession.setPlaybackState(buildState(PlaybackStateCompat.STATE_STOPPED))
    }

    /**
     * Shared by playAndReport (initial tap) and handleReclassifyTap (after
     * changing the genre) so both build the exact same shape of metadata —
     * genre is folded into the artist line ("Artist  ·  Genre"), the same
     * "subtitle" convention onLoadChildren already uses for browse tiles,
     * rather than a separate field this template has no slot for. This
     * doubles as the "current genre" indicator for Reclassify: since there's
     * no picker list on this screen (see handleReclassifyTap's doc for why),
     * seeing it update here on every tap is the closest equivalent to a
     * highlighted selection this template supports.
     */
    private fun nowPlayingMetadata(f: Favorites.Fav): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, f.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "${f.artist}  ·  ${f.genre.ifBlank { "Uncategorized" }}")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, f.album)
            // Public, unauthenticated YouTube thumbnail URL pattern — no new
            // dependency or InnerTube call. Gearhead's own image loader
            // fetches it, not this process, the same division of
            // responsibility the bundled ic_play icon URI already relies
            // on, just remote instead of a local resource — unverified on
            // real hardware as of this writing.
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, "https://i.ytimg.com/vi/${f.videoId}/hqdefault.jpg")
            .build()

    /**
     * Reclassify — cycles currentFav's genre forward through
     * Favorites.GENRES (wrapping) on every tap, persists it immediately via
     * Favorites.setGenre, and repaints this same now-playing screen with
     * the result. Deliberately not a separate picker screen the way
     * LaunchActivity's long-press dialog works on the phone, for two
     * reasons:
     *
     * 1. Android Auto's browse tiles don't expose a long-press gesture at
     *    all — nothing in androidx.media/media3/Car App Library gives a
     *    MediaItem a secondary tap, and one would cut against the
     *    driving-distraction-minimized design this whole surface already
     *    defers to (see CLAUDE.md's "Platform boundary" section).
     * 2. The track that needs reclassifying is already the one on screen
     *    the instant its tile is tapped — routing through a separate "pick
     *    a track" screen first would make the user re-find something they
     *    just selected, the opposite of the goal.
     *
     * One tap per step through the fixed list (GENRES.size - 1 taps worst
     * case) rather than a scrollable picker, the same toggle idiom Settings
     * already uses for RefreshModel — and this reuses an established,
     * real-hardware-confirmed-tappable mechanism (the exact
     * PlaybackStateCompat custom-action machinery EXTRA_BUTTON proved
     * renders and responds to taps on this head unit) rather than betting
     * on an unverified per-item browse action or an unverified multi-button
     * layout. Reads mediaSession.controller.playbackState back rather than
     * assuming STATE_STOPPED, so a reclassify tap mid-buffering or
     * mid-error doesn't silently overwrite that state.
     */
    private fun handleReclassifyTap() {
        val f = currentFav ?: return
        val next = Favorites.nextGenre(f.genre)
        Favorites.setGenre(this, f.key, next)
        ProbeLog.w(this, "AutoMediaService: reclassified ${f.label()} -> $next")

        val updated = f.copy(genre = next)
        currentFav = updated
        mediaSession.setMetadata(nowPlayingMetadata(updated))
        mediaSession.setPlaybackState(
            buildState(mediaSession.controller.playbackState?.state ?: PlaybackStateCompat.STATE_STOPPED)
        )
        // So the tile's new genre slot is reflected once the user backs out
        // — same pattern playAndReport/handleRefreshTap already use.
        notifyChildrenChanged(ROOT_ID)
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

        // The track now on screen — what Reclassify (see currentFav's doc)
        // acts on. Set before anything else here so it's available for the
        // very first buildState() call below.
        currentFav = f
        mediaSession.setMetadata(nowPlayingMetadata(f))
        mediaSession.setPlaybackState(buildState(PlaybackStateCompat.STATE_BUFFERING))

        val live = Probes.ytmController(this) != null
        ProbeLog.w(this, "AutoMediaService: play ${f.label()}  live=$live")

        // Does the actual work (including the Store.saveMixSeed step, so
        // SessionLogger's auto-continue stays consistent with what was
        // actually played from this surface).
        Probes.playFavorite(this, f.title, f.artist, f.videoId)

        // The seed is saved (synchronously, above) the instant a tile is
        // tapped, regardless of whether playback ever gets confirmed — so
        // root's now-playing tile (whichever genre f belongs to) is already
        // knowable now, not just after the poll below confirms. Triggering
        // this immediately, not gated on confirmation, means the reshuffle
        // runs in the background while the user is looking at the
        // now-playing template and is ready by the time they press Back —
        // same pattern handleRefreshTap uses, and for the same reason: this
        // was previously missing entirely (never present, not just
        // regressed), which is why a plain track tap + Back left root
        // showing the pre-tap shuffle with the old track still ringed.
        notifyChildrenChanged(ROOT_ID)

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
            .withReclassifyAction()
        if (errorMessage != null) builder.setErrorMessage(errorMessage)
        return builder.build()
    }

    /**
     * Adds the Reclassify custom action whenever a specific track is
     * actually on screen (currentFav != null) — a no-op otherwise (the
     * initial idle state before any tap, and handleRefreshTap's "Refreshed"
     * screen, which clears currentFav for exactly this reason). Label
     * carries the current genre directly ("Genre: Rock") so it also serves
     * as the "what's it set to right now" readout — see
     * handleReclassifyTap's doc for the full reasoning on this design.
     */
    private fun PlaybackStateCompat.Builder.withReclassifyAction(): PlaybackStateCompat.Builder {
        val f = currentFav ?: return this
        addCustomAction(
            PlaybackStateCompat.CustomAction.Builder(
                ACTION_RECLASSIFY,
                "Genre: ${f.genre.ifBlank { "Uncategorized" }}",
                R.drawable.ic_genre
            ).build()
        )
        return this
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
