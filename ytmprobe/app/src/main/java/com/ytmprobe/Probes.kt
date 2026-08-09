package com.ytmprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.view.KeyEvent

object Probes {

    const val YTM = "com.google.android.apps.youtube.music"

    /** Candidates from the most recent A+ run, for manual override. */
    @Volatile
    var lastCandidates: List<Resolver.Candidate> = emptyList()

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    fun manager(ctx: Context): MediaSessionManager =
        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    fun listenerComponent(ctx: Context) =
        ComponentName(ctx, NotifListener::class.java)

    /** Null when YTM has no live session, or when Notification Access is missing. */
    fun ytmController(ctx: Context): MediaController? = try {
        manager(ctx).getActiveSessions(listenerComponent(ctx))
            .firstOrNull { it.packageName == YTM }
    } catch (e: SecurityException) {
        ProbeLog.w(ctx, "  !! SecurityException — Notification Access not granted")
        null
    }

    fun hasNotificationAccess(ctx: Context): Boolean = try {
        manager(ctx).getActiveSessions(listenerComponent(ctx)); true
    } catch (e: SecurityException) { false }

    // ------------------------------------------------------------------
    // PROBE A — what does YTM publish in its metadata bundle?
    //
    // The question that decides whether the whole search/resolution layer
    // survives into the real app. If METADATA_KEY_MEDIA_ID holds a real
    // videoId, resolution is unnecessary. If USER_RATING is populated,
    // like state is a boolean rather than icon-id pattern matching.
    // ------------------------------------------------------------------
    fun probeA(ctx: Context) {
        ProbeLog.section(ctx, "PROBE A — metadata fields")

        val c = ytmController(ctx)
        if (c == null) {
            ProbeLog.w(ctx, "  no live YTM session — start playback first")
            return
        }

        val md = c.metadata
        if (md == null) {
            ProbeLog.w(ctx, "  session exists but metadata is null")
            return
        }

        ProbeLog.w(ctx, "  ${md.keySet().size} metadata key(s):")
        for (k in md.keySet().sorted()) {
            val v: Any? = when {
                k.contains("RATING") -> md.getRating(k)?.let {
                    "isRated=${it.isRated} ratingStyle=${it.ratingStyle} thumbUp=${
                        runCatching { it.isThumbUp }.getOrNull()
                    }"
                }
                else -> md.getString(k) ?: md.getLong(k).takeIf { it != 0L }
            }
            ProbeLog.w(ctx, "    $k = $v")
        }

        val mediaId = md.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
        ProbeLog.w(ctx, "")
        ProbeLog.w(ctx, "  VERDICT:")
        if (!mediaId.isNullOrBlank()) {
            ProbeLog.w(ctx, "    MEDIA_ID = '$mediaId'")
            ProbeLog.w(ctx, "    -> resolution layer can be DELETED")
        } else {
            ProbeLog.w(ctx, "    MEDIA_ID empty -> search-based resolution still needed")
        }

        val durationMs = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val rating = md.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)
        if (rating != null) {
            ProbeLog.w(ctx, "    USER_RATING present -> like state is a boolean")
        } else {
            ProbeLog.w(ctx, "    USER_RATING absent -> keep icon-id detection (2131233050)")
        }

        // What the session will accept, as a cross-check on the shell findings.
        val a = c.playbackState?.actions ?: 0L
        ProbeLog.w(ctx, "")
        ProbeLog.w(ctx, "  actions bitmask = $a")
        fun f(bit: Long, name: String) =
            ProbeLog.w(ctx, "    ${if (a and bit != 0L) "YES" else " no"}  $name")
        f(1L shl 10, "PLAY_FROM_MEDIA_ID")
        f(1L shl 13, "PLAY_FROM_URI")
        f(1L shl 8,  "SEEK_TO")
        f(1L shl 12, "SKIP_TO_QUEUE_ITEM")
        ProbeLog.w(ctx, "  queue size = ${c.queue?.size ?: -1} (-1 = null)")
    }

    // ------------------------------------------------------------------
    // PROBE B1 — can we cold-start YTM by binding its MediaBrowserService?
    //
    // A service bind is NOT subject to the background activity launch
    // restriction, so if this works the app can resume with no button and
    // no permissions. The obstacle is YTM's onGetRoot() package validation.
    // ------------------------------------------------------------------
    fun probeB1(ctx: Context, done: (Boolean) -> Unit = {}) {
        ProbeLog.section(ctx, "PROBE B1 — MediaBrowser connect (cold start)")

        val services = ctx.packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService").setPackage(YTM), 0)

        if (services.isEmpty()) {
            ProbeLog.w(ctx, "  no MediaBrowserService found in $YTM")
            ProbeLog.w(ctx, "  (check <queries> in the manifest if this is unexpected)")
            done(false); return
        }

        services.forEach {
            ProbeLog.w(ctx, "  found service: ${it.serviceInfo.name}")
        }

        val svc = ComponentName(YTM, services[0].serviceInfo.name)
        ProbeLog.w(ctx, "  binding ${svc.className} ...")

        var browser: MediaBrowserCompat? = null
        val cb = object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                ProbeLog.w(ctx, "  CONNECTED")
                val token = browser?.sessionToken
                ProbeLog.w(ctx, "  sessionToken = $token")
                ProbeLog.w(ctx, "  root = ${browser?.root}")
                ProbeLog.w(ctx, "")
                ProbeLog.w(ctx, "  VERDICT: bind-based cold start WORKS.")
                ProbeLog.w(ctx, "    -> automatic resume on car connect is possible,")
                ProbeLog.w(ctx, "       no button and no overlay permission needed.")
                browser?.disconnect()
                done(true)
            }
            override fun onConnectionFailed() {
                ProbeLog.w(ctx, "  CONNECTION FAILED — onGetRoot() rejected this package")
                ProbeLog.w(ctx, "")
                ProbeLog.w(ctx, "  VERDICT: bind-based cold start BLOCKED.")
                ProbeLog.w(ctx, "    -> fall back to deep link, which needs either a tap")
                ProbeLog.w(ctx, "       or SYSTEM_ALERT_WINDOW to fire from background.")
                done(false)
            }
            override fun onConnectionSuspended() {
                ProbeLog.w(ctx, "  connection suspended")
            }
        }

        browser = MediaBrowserCompat(ctx, svc, cb, null)
        runCatching { browser.connect() }
            .onFailure { ProbeLog.w(ctx, "  connect() threw: $it"); done(false) }

        // Report if nothing came back — silence is itself a result.
        Handler(Looper.getMainLooper()).postDelayed({
            if (browser?.isConnected != true) {
                ProbeLog.w(ctx, "  (no callback within 10s — treat as failure)")
            }
        }, 10_000)
    }

    /**
     * Play a videoId whether or not YTM's process is currently alive.
     *
     * probeC alone silently no-ops when YTM has been killed (no session to
     * command) — exactly the case a lock-screen tap after a long drive with
     * Spotify needs to handle, since the whole point is recovering from YTM
     * having been evicted. When there's no live session, fall back to the
     * same deep-link launch FINDINGS.md's E3/E9 proved is the only mechanism
     * that can cold-start YTM at all (every MediaSession-based cold-start
     * path — dispatch play, a raw media-button broadcast, MediaBrowserCompat
     * — failed; YTM validates those down to Android Auto/Wear OS callers).
     * setPackage(YTM) pins the target app so Android resolves directly to
     * YTM's own deep-link activity instead of showing App Links' chooser.
     * A notification tap's PendingIntent is BAL-exempt (documented in
     * FINDINGS.md's "Cold start" section), so starting this activity from
     * here — even with our own process freshly cold-started to deliver the
     * broadcast — is allowed.
     */
    fun playOrLaunch(ctx: Context, videoId: String) {
        if (ytmController(ctx) != null) {
            probeC(ctx, videoId)
            return
        }
        ProbeLog.w(ctx, "  no live YTM session — cold-starting via deep link")
        val uri = Uri.parse("https://music.youtube.com/watch?v=$videoId")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(YTM)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { ProbeLog.w(ctx, "  cold-start launch failed: $it") }
    }

    // ------------------------------------------------------------------
    // PROBE C — will a LIVE session honour playFromUri?
    //
    // Everything validated over adb went through `am start`, which launches
    // and plays in one move. Commanding an already-running session was never
    // tested. PLAY_FROM_URI is advertised, but advertised != implemented.
    // ------------------------------------------------------------------
    fun probeC(ctx: Context, videoId: String, positionMs: Long = 0L) {
        ProbeLog.section(ctx, "PROBE C — playFromUri on a live session")

        val c = ytmController(ctx)
        if (c == null) {
            ProbeLog.w(ctx, "  no live YTM session — start playback first")
            return
        }

        val before = c.metadata?.description?.title?.toString()
        ProbeLog.w(ctx, "  before: $before")

        val uri = Uri.parse("https://music.youtube.com/watch?v=$videoId")
        ProbeLog.w(ctx, "  sending playFromUri($uri)")
        runCatching { c.transportControls.playFromUri(uri, Bundle()) }
            .onFailure { ProbeLog.w(ctx, "  threw: $it"); return }

        Handler(Looper.getMainLooper()).postDelayed({
            val c2 = ytmController(ctx)
            val after = c2?.metadata?.description?.title?.toString()
            ProbeLog.w(ctx, "  after:  $after")
            if (after != null && after != before) {
                ProbeLog.w(ctx, "  VERDICT: playFromUri WORKS on a live session.")
                if (positionMs > 0) {
                    ProbeLog.w(ctx, "  seeking to ${positionMs}ms")
                    runCatching { c2.transportControls.seekTo(positionMs) }
                    Handler(Looper.getMainLooper()).postDelayed({
                        ProbeLog.w(ctx, "  position now = ${ytmController(ctx)?.playbackState?.position}")
                    }, 3000)
                }
            } else {
                ProbeLog.w(ctx, "  VERDICT: track did not change — playFromUri ignored.")
                ProbeLog.w(ctx, "    -> deep link remains the only way to select a track.")
            }
        }, 6000)
    }

    // ------------------------------------------------------------------
    // PROBE D — direct broadcast to YTM's MediaButtonReceiver.
    //
    // dumpsys showed the receiver is manifest-registered and survives a
    // force-stop, but "Media button session is null" means the framework
    // has nothing to route to. A broadcast aimed straight at the component
    // bypasses that routing. Broadcasts are not background-restricted.
    // ------------------------------------------------------------------
    fun probeD(ctx: Context) {
        ProbeLog.section(ctx, "PROBE D — direct MediaButtonReceiver broadcast")

        val receiver = ComponentName(
            YTM, "androidx.media.session.MediaButtonReceiver")
        ProbeLog.w(ctx, "  target: ${receiver.flattenToShortString()}")

        fun send(action: Int, label: String) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = receiver
                putExtra(Intent.EXTRA_KEY_EVENT,
                    KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            runCatching { ctx.sendBroadcast(i) }
                .onSuccess { ProbeLog.w(ctx, "  sent $label") }
                .onFailure { ProbeLog.w(ctx, "  $label threw: $it") }
        }

        send(KeyEvent.ACTION_DOWN, "ACTION_DOWN")
        send(KeyEvent.ACTION_UP, "ACTION_UP")

        Handler(Looper.getMainLooper()).postDelayed({
            val c = ytmController(ctx)
            if (c != null) {
                ProbeLog.w(ctx, "  session appeared: state=${c.playbackState?.state}")
                ProbeLog.w(ctx, "  VERDICT: broadcast wake WORKS.")
            } else {
                ProbeLog.w(ctx, "  no session after 8s")
                ProbeLog.w(ctx, "  VERDICT: broadcast wake failed.")
            }
        }, 8000)
    }

    // ------------------------------------------------------------------
    // PROBE E — what is actually in the queue?
    //
    // dumpsys always reported queue size=0, but the MediaController sees 25
    // items. Each QueueItem carries its own mediaId on the description,
    // separate from the metadata bundle. If those hold videoIds, per-track
    // identity is solved for the whole queue at once and the search layer
    // disappears entirely.
    // ------------------------------------------------------------------
    fun probeE(ctx: Context) {
        ProbeLog.section(ctx, "PROBE E — queue contents")

        val c = ytmController(ctx)
        if (c == null) {
            ProbeLog.w(ctx, "  no live YTM session — start playback first")
            return
        }

        val q = c.queue
        if (q == null) {
            ProbeLog.w(ctx, "  queue is null")
            return
        }

        ProbeLog.w(ctx, "  queueTitle = ${c.queueTitle}")
        ProbeLog.w(ctx, "  ${q.size} item(s)")
        ProbeLog.w(ctx, "  active queue id = ${c.playbackState?.activeQueueItemId}")
        ProbeLog.w(ctx, "")

        var withMediaId = 0
        q.forEachIndexed { i, item ->
            val d = item.description
            val mid = d.mediaId
            if (!mid.isNullOrBlank()) withMediaId++
            ProbeLog.w(ctx, "  [$i] qid=${item.queueId}")
            ProbeLog.w(ctx, "      mediaId=${mid ?: "(null)"}")
            ProbeLog.w(ctx, "      title=${d.title}  subtitle=${d.subtitle}")
            if (d.mediaUri != null) ProbeLog.w(ctx, "      mediaUri=${d.mediaUri}")
            // Extras sometimes carry the videoId even when mediaId is null.
            d.extras?.let { ex ->
                val keys = ex.keySet()
                if (keys.isNotEmpty()) {
                    ProbeLog.w(ctx, "      extras: ${keys.joinToString()}")
                    keys.forEach { k ->
                        val v = runCatching { ex.get(k)?.toString() }.getOrNull()
                        if (v != null && v.length < 120) ProbeLog.w(ctx, "        $k = $v")
                    }
                }
            }
        }

        ProbeLog.w(ctx, "")
        ProbeLog.w(ctx, "  VERDICT:")
        if (withMediaId == q.size && q.isNotEmpty()) {
            ProbeLog.w(ctx, "    all $withMediaId items carry a mediaId")
            ProbeLog.w(ctx, "    -> exact identity for the whole queue.")
            ProbeLog.w(ctx, "       SEARCH LAYER CAN BE DELETED.")
            ProbeLog.w(ctx, "       Resume becomes: store the mediaId, replay it.")
        } else if (withMediaId > 0) {
            ProbeLog.w(ctx, "    $withMediaId of ${q.size} items carry a mediaId")
            ProbeLog.w(ctx, "    -> partial; search needed only for the gaps")
        } else {
            ProbeLog.w(ctx, "    no mediaIds in the queue")
            ProbeLog.w(ctx, "    -> but queue order + titles still give you")
            ProbeLog.w(ctx, "       playlist position, which dumpsys never showed.")
        }
    }

    // ------------------------------------------------------------------
    // PROBE A+ — the full capture path, end to end.
    //
    // Reads the live session, resolves title+artist to a videoId over
    // InnerTube, persists the result, and hands the id back so probe C can
    // use it without retyping. This is the whole "store" half of the real
    // app, running for real.
    //
    // Network work happens off the main thread; onResolved fires on the
    // main thread with the videoId (or null).
    // ------------------------------------------------------------------
    fun probeAPlus(ctx: Context, onResolved: (String?) -> Unit = {}) {
        ProbeLog.section(ctx, "PROBE A+ — capture, resolve, store")

        val c = ytmController(ctx)
        if (c == null) {
            ProbeLog.w(ctx, "  no live YTM session — start playback first")
            onResolved(null); return
        }

        val md = c.metadata
        if (md == null) {
            ProbeLog.w(ctx, "  metadata is null")
            onResolved(null); return
        }

        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val rating = md.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)
        val liked = rating?.let { it.isRated && runCatching { it.isThumbUp }.getOrDefault(false) } ?: false

        // Position is a snapshot plus a timestamp — extrapolate when playing.
        val ps = c.playbackState
        val position = if (ps == null) 0L else {
            if (ps.state == android.media.session.PlaybackState.STATE_PLAYING) {
                ps.position + ((android.os.SystemClock.elapsedRealtime() -
                        ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            } else ps.position
        }

        ProbeLog.w(ctx, "  captured:")
        ProbeLog.w(ctx, "    title    : $title")
        ProbeLog.w(ctx, "    artist   : $artist")
        ProbeLog.w(ctx, "    album    : $album")
        ProbeLog.w(ctx, "    duration : ${durationMs}ms (${durationMs/1000}s)")
        ProbeLog.w(ctx, "    position : ${position}ms")
        ProbeLog.w(ctx, "    liked    : $liked")

        if (title.isBlank()) {
            ProbeLog.w(ctx, "  no title to resolve")
            onResolved(null); return
        }

        val main = Handler(Looper.getMainLooper())

        // Cache hit — no network needed.
        Store.cached(ctx, title, artist)?.let { hit ->
            ProbeLog.w(ctx, "")
            ProbeLog.w(ctx, "  CACHED -> $hit")
            Store.saveLast(ctx, Store.Track(title, artist, album, hit, position, liked))
            ProbeLog.w(ctx, "  stored. ${Store.cacheSize(ctx)} cache entries")
            onResolved(hit)
            return
        }

        ProbeLog.w(ctx, "")
        ProbeLog.w(ctx, "  resolving over InnerTube (no auth)...")

        Thread {
            val results = runCatching {
                Resolver.resolve(title, artist, album, durationMs)
            }
                .onFailure { e -> main.post { ProbeLog.w(ctx, "  search failed: $e") } }
                .getOrDefault(emptyList())

            main.post {
                if (results.isEmpty()) {
                    ProbeLog.w(ctx, "  no candidates found")
                    Store.saveLast(ctx, Store.Track(title, artist, album, "", position, liked))
                    ProbeLog.w(ctx, "  stored without videoId")
                    onResolved(null)
                    return@post
                }

                ProbeLog.w(ctx, "  candidates:")
                results.forEachIndexed { i, r ->
                    val mark = if (i == 0) "->" else "  "
                    ProbeLog.w(ctx, "   $mark [%d] %5.1f %-11s %-5s %-4s %s — %s"
                        .format(i, r.score, r.videoId, r.durationText(),
                                r.type.ifBlank { "-" }, r.title, r.artist))
                }
                lastCandidates = results

                val best = results.first()
                ProbeLog.w(ctx, "")
                if (best.score < 70) {
                    ProbeLog.w(ctx, "  !! top score %.1f is low — verify before trusting"
                        .format(best.score))
                }

                Store.cache(ctx, title, artist, best.videoId)
                Store.saveLast(ctx,
                    Store.Track(title, artist, album, best.videoId, position, liked))

                ProbeLog.w(ctx, "  RESOLVED -> ${best.videoId}")
                ProbeLog.w(ctx, "  stored. ${Store.cacheSize(ctx)} cache entries")
                ProbeLog.w(ctx, "  videoId field populated — probe C is ready")
                onResolved(best.videoId)
            }
        }.start()
    }

    /** Override a bad automatic pick with candidate [index]. */
    fun pickCandidate(ctx: Context, index: Int): String? {
        val list = lastCandidates
        if (list.isEmpty()) {
            ProbeLog.w(ctx, "no candidates — run A+ first"); return null
        }
        if (index !in list.indices) {
            ProbeLog.w(ctx, "index $index out of range (0-${list.size - 1})"); return null
        }
        val c = list[index]
        val last = Store.loadLast(ctx)
        if (last != null) {
            Store.cache(ctx, last.title, last.artist, c.videoId)
            Store.saveLast(ctx, last.copy(videoId = c.videoId))
        }
        ProbeLog.section(ctx, "MANUAL PICK")
        ProbeLog.w(ctx, "  [$index] ${c.videoId}  ${c.title} — ${c.artist}")
        ProbeLog.w(ctx, "  cached; future resolutions use this")
        return c.videoId
    }

    // ------------------------------------------------------------------
    // Favorites: resolve entries that have no videoId yet.
    // Never touches one that already has an id, so manual corrections and
    // picker choices are permanent.
    // ------------------------------------------------------------------
    fun resolveFavorites(ctx: Context, quiet: Boolean = false, done: (Int, Int) -> Unit = { _, _ -> }) {
        val todo = Favorites.unresolved(ctx)
        if (todo.isEmpty()) {
            if (!quiet) {
                ProbeLog.section(ctx, "RESOLVE FAVORITES")
                ProbeLog.w(ctx, "  nothing to do — all ${Favorites.count(ctx)} resolved")
            }
            done(0, 0); return
        }

        if (!quiet) {
            ProbeLog.section(ctx, "RESOLVE FAVORITES")
            ProbeLog.w(ctx, "  ${todo.size} unresolved of ${Favorites.count(ctx)}")
        }

        val main = Handler(Looper.getMainLooper())
        Thread {
            var ok = 0
            for (f in todo) {
                // Cache first — free and already user-confirmed.
                val cached = Store.cached(ctx, f.title, f.artist)
                if (cached != null) {
                    Favorites.setVideoId(ctx, f.key, cached)
                    ok++
                    if (!quiet) main.post { ProbeLog.w(ctx, "  cached  $cached  ${f.label()}") }
                    continue
                }
                val res = runCatching {
                    Resolver.resolve(f.title, f.artist, f.album, f.durationMs)
                }.getOrDefault(emptyList())

                val best = res.firstOrNull()
                if (best != null && best.score >= 60) {
                    Favorites.setVideoId(ctx, f.key, best.videoId)
                    Store.cache(ctx, f.title, f.artist, best.videoId)
                    ok++
                    if (!quiet) main.post {
                        ProbeLog.w(ctx, "  %5.1f  %s  %s".format(best.score, best.videoId, f.label()))
                    }
                } else {
                    if (!quiet) main.post {
                        ProbeLog.w(ctx, "  ----   no confident match: ${f.label()}")
                    }
                }
            }
            main.post {
                if (!quiet) {
                    ProbeLog.w(ctx, "  resolved $ok of ${todo.size}")
                    ProbeLog.w(ctx, "  ${Favorites.resolvedCount(ctx)}/${Favorites.count(ctx)} playable")
                }
                done(ok, todo.size)
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Genre tagging: GenreTagger.classify() per untagged favorite. Cyrillic
    // titles/artists classify instantly (no network call), so only pace
    // between the ones that actually hit iTunes — otherwise a library
    // that's mostly Russian-language would wait for no reason.
    // ------------------------------------------------------------------
    fun tagGenres(ctx: Context, quiet: Boolean = false, done: (Int, Int) -> Unit = { _, _ -> }) {
        val todo = Favorites.unGenred(ctx)
        if (todo.isEmpty()) {
            if (!quiet) {
                ProbeLog.section(ctx, "TAG GENRES")
                ProbeLog.w(ctx, "  nothing to do — all ${Favorites.count(ctx)} tagged")
            }
            done(0, 0); return
        }

        if (!quiet) {
            ProbeLog.section(ctx, "TAG GENRES")
            ProbeLog.w(ctx, "  ${todo.size} untagged of ${Favorites.count(ctx)}")
            ProbeLog.w(ctx, "  paced to avoid iTunes rate limits — this takes a while")
        }

        val main = Handler(Looper.getMainLooper())
        Thread {
            var ok = 0
            for (f in todo) {
                val genre = runCatching { GenreTagger.classify(f.title, f.artist) }
                    .getOrDefault("Uncategorized")
                Favorites.setGenre(ctx, f.key, genre)
                ok++
                if (!quiet) main.post { ProbeLog.w(ctx, "  %-12s %s".format(genre, f.label())) }
                if (!GenreTagger.isCyrillic(f.title) && !GenreTagger.isCyrillic(f.artist)) {
                    Thread.sleep(1200)
                }
            }
            main.post {
                if (!quiet) ProbeLog.w(ctx, "  tagged $ok of ${todo.size}")
                done(ok, todo.size)
            }
        }.start()
    }

    fun showFavorites(ctx: Context) {
        ProbeLog.section(ctx, "FAVORITES")
        Favorites.dump(ctx).lines().forEach { ProbeLog.w(ctx, "  $it") }
    }

    fun clearCache(ctx: Context) {
        val n = Store.cacheSize(ctx)
        Store.clearCache(ctx)
        ProbeLog.section(ctx, "CACHE CLEARED")
        ProbeLog.w(ctx, "  removed $n entry(ies)")
    }

    // ------------------------------------------------------------------
    // Show what has been persisted.
    // ------------------------------------------------------------------
    fun showStore(ctx: Context) {
        ProbeLog.section(ctx, "STORE")
        Store.dump(ctx).lines().forEach { ProbeLog.w(ctx, "  $it") }
    }

    // ------------------------------------------------------------------
    // Snapshot of current state — cheap and useful before every probe.
    // ------------------------------------------------------------------
    fun status(ctx: Context) {
        ProbeLog.section(ctx, "STATUS")
        ProbeLog.w(ctx, "  notification access: ${hasNotificationAccess(ctx)}")

        val installed = runCatching {
            ctx.packageManager.getPackageInfo(YTM, 0); true
        }.getOrDefault(false)
        ProbeLog.w(ctx, "  YTM installed: $installed")

        val c = ytmController(ctx)
        if (c == null) {
            ProbeLog.w(ctx, "  YTM session: NONE")
        } else {
            ProbeLog.w(ctx, "  YTM session: state=${c.playbackState?.state}")
            ProbeLog.w(ctx, "    title=${c.metadata?.description?.title}")
            ProbeLog.w(ctx, "    mediaId=${c.metadata?.getString(
                android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)}")
        }

        val all = runCatching {
            manager(ctx).getActiveSessions(listenerComponent(ctx))
        }.getOrDefault(emptyList())
        ProbeLog.w(ctx, "  total active sessions: ${all.size}")
        all.forEach { ProbeLog.w(ctx, "    - ${it.packageName}") }
    }
}
