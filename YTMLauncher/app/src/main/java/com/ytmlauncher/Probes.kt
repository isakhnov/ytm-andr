package com.ytmlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Session helpers and the production play/resolve/tag pipeline.
 *
 * Trimmed from ytmprobe's Probes.kt, which also carried the raw diagnostic
 * probes (A/B1/D/E/A+) and manual-override machinery (pickCandidate/
 * lastCandidates) that answered their questions for good — see
 * ytmprobe/FINDINGS.md — and aren't reachable from anywhere in this app.
 */
object Probes {

    const val YTM = "com.google.android.apps.youtube.music"

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
    // Play a favorite whether or not YTM's process is currently alive.
    //
    // Every "tap a favorite to play" surface (LaunchActivity, the lock-
    // screen notification, Android Auto) must go through this, not just the
    // live-session command — skipping the saveMixSeed step here previously
    // caused auto-continue to replay a stale seed, since SessionLogger reads
    // whatever was last saved here, independent of what's actually playing.
    // ------------------------------------------------------------------
    fun playFavorite(ctx: Context, title: String, artist: String, videoId: String) {
        Store.saveMixSeed(ctx, title, artist, videoId)

        if (ytmController(ctx) != null) {
            commandLiveSession(ctx, videoId)
            return
        }
        // No live session: the only mechanism proven to cold-start YTM at
        // all (see ytmprobe/FINDINGS.md's "Cold start" section — every
        // MediaSession-based cold-start path failed; YTM validates those
        // down to Android Auto/Wear OS callers) is a deep link, pinned to
        // YTM's package so Android resolves directly to its own deep-link
        // activity instead of showing App Links' chooser.
        ProbeLog.w(ctx, "  no live YTM session — cold-starting via deep link")
        val uri = Uri.parse("https://music.youtube.com/watch?v=$videoId")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(YTM)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { ProbeLog.w(ctx, "  cold-start launch failed: $it") }
    }

    /**
     * Commands an existing live session via playFromUri — quiet, no banner,
     * no delayed re-query. This runs on every single play from every
     * surface in the app, unlike ytmprobe's original probeC (kept there,
     * dropped here) which logged a verbose section banner plus a
     * 6-second-delayed "VERDICT" re-check appropriate for a one-off manual
     * probe, not production log volume.
     */
    fun commandLiveSession(ctx: Context, videoId: String) {
        val c = ytmController(ctx) ?: return
        val uri = Uri.parse("https://music.youtube.com/watch?v=$videoId")
        runCatching { c.transportControls.playFromUri(uri, Bundle()) }
            .onFailure { ProbeLog.w(ctx, "  playFromUri failed: $it") }
    }

    /**
     * Verbose variant for manual debugging from Settings — logs a banner,
     * then re-queries after 6s and logs whether the track actually changed.
     */
    fun debugCommandLiveSession(ctx: Context, videoId: String) {
        ProbeLog.section(ctx, "playFromUri on a live session")

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
            } else {
                ProbeLog.w(ctx, "  VERDICT: track did not change — playFromUri ignored.")
            }
        }, 6000)
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
    //
    // Manual-only in this app (Settings' "Tag genres automatically" button)
    // — not auto-triggered on startup, kept simple by explicit choice.
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
        }

        val all = runCatching {
            manager(ctx).getActiveSessions(listenerComponent(ctx))
        }.getOrDefault(emptyList())
        ProbeLog.w(ctx, "  total active sessions: ${all.size}")
        all.forEach { ProbeLog.w(ctx, "    - ${it.packageName}") }
    }
}
