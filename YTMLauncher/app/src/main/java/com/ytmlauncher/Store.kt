package com.ytmlauncher

import android.content.Context
import org.json.JSONObject

/**
 * Persistence.
 *
 * Two things are stored:
 *   last  — the most recent track, overwritten as it changes
 *   cache — title|artist -> videoId, so a confirmed resolution never repeats
 */
object Store {

    private const val PREFS = "ytmlauncher"
    private const val K_LAST = "last"
    private const val K_CACHE = "cache"
    private const val K_MIX_SEED = "mixSeed"
    private const val K_AUTO_CONTINUE_ELIGIBLE = "autoContinueEligible"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Track(
        val title: String,
        val artist: String,
        val album: String,
        val videoId: String,
        val positionMs: Long,
        val liked: Boolean,
        val at: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("title", title); put("artist", artist); put("album", album)
            put("videoId", videoId); put("positionMs", positionMs)
            put("liked", liked); put("at", at)
        }

        companion object {
            fun fromJson(o: JSONObject) = Track(
                o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("videoId"), o.optLong("positionMs"),
                o.optBoolean("liked"), o.optLong("at")
            )
        }
    }

    // ---------------------------------------------------------------- last

    fun saveLast(ctx: Context, t: Track) {
        prefs(ctx).edit().putString(K_LAST, t.toJson().toString()).apply()
    }

    fun loadLast(ctx: Context): Track? {
        val s = prefs(ctx).getString(K_LAST, null) ?: return null
        return runCatching { Track.fromJson(JSONObject(s)) }.getOrNull()
    }

    // --------------------------------------------------------------- cache

    private fun key(title: String, artist: String) =
        "${Resolver.norm(title)}|${Resolver.norm(artist)}"

    private fun cache(ctx: Context): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(K_CACHE, "{}")!!) }
            .getOrDefault(JSONObject())

    fun cached(ctx: Context, title: String, artist: String): String? =
        cache(ctx).optString(key(title, artist), "").takeIf { it.isNotBlank() }

    fun cache(ctx: Context, title: String, artist: String, videoId: String) {
        val c = cache(ctx)
        c.put(key(title, artist), videoId)
        prefs(ctx).edit().putString(K_CACHE, c.toString()).apply()
    }

    fun cacheSize(ctx: Context): Int = cache(ctx).length()

    fun clearCache(ctx: Context) {
        prefs(ctx).edit().remove(K_CACHE).apply()
    }

    // ------------------------------------------------------------- mix seed

    /**
     * The favorite that was explicitly tapped to start the *current* mix —
     * not the currently-playing track (YTM's algorithm moves on from that on
     * its own). Auto-continue replays this seed, the same action a manual
     * tap would do, so YTM's algorithm picks up a fresh but related mix
     * rather than trying to restore the exact song/position.
     */
    data class MixSeed(val title: String, val artist: String, val videoId: String)

    fun saveMixSeed(ctx: Context, title: String, artist: String, videoId: String) {
        val json = JSONObject().apply {
            put("title", title); put("artist", artist); put("videoId", videoId)
        }
        prefs(ctx).edit().putString(K_MIX_SEED, json.toString()).apply()
    }

    fun loadMixSeed(ctx: Context): MixSeed? {
        val s = prefs(ctx).getString(K_MIX_SEED, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            MixSeed(o.optString("title"), o.optString("artist"), o.optString("videoId"))
        }.getOrNull()
    }

    // ------------------------------------------------------- auto-continue

    /**
     * Set by SessionLogger when the YTM session disappears while it was
     * genuinely playing (not paused/silent) within the last ~60s — i.e. the
     * car/AA session probably just shut down mid-song, not that the user
     * deliberately stopped it. Consumed (cleared) the next time a session
     * appears and auto-continue actually fires, so it only ever fires once
     * per eligible disappearance.
     */
    fun setAutoContinueEligible(ctx: Context, eligible: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTO_CONTINUE_ELIGIBLE, eligible).apply()
    }

    fun isAutoContinueEligible(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_AUTO_CONTINUE_ELIGIBLE, false)

    // ------------------------------------------------- Auto refresh model

    /**
     * Two mutually-exclusive designs for AutoMediaService's Refresh
     * affordance, selectable from Settings so both can be tried against
     * each other on real hardware without a rebuild. A third design,
     * EXTRA_BUTTON (a custom PlaybackStateCompat action on the now-playing
     * template calling notifyChildrenChanged(ROOT_ID) with no navigation),
     * was built and real-hardware tested, then removed outright rather than
     * kept as a non-recommended option: it's not a bug away from working,
     * it's structurally dead — its button only ever exists on the
     * now-playing template, which shows track metadata, not the browse
     * grid, so there is no on-screen content for it to ever repaint. If a
     * true in-place repaint (no new stack frame, no forced template
     * transition) is wanted again later, it needs a different mechanism
     * than "a button on some other screen," not a revival of this one.
     *
     *  LEGACY — real-hardware-verified-working. Refresh is a FLAG_PLAYABLE
     *  tile; tapping it forces gearhead's now-playing template (unavoidable
     *  for any playable tap) showing "Refreshed" metadata, and calls
     *  notifyChildrenChanged(ROOT_ID) immediately so root is fresh by the
     *  time the user backs out. One forced screen transition per refresh.
     *
     *  IN_PLACE — the default. Refresh is a FLAG_BROWSABLE tile (a design
     *  tried and abandoned once before LEGACY existed — see
     *  AutoMediaService's onLoadChildren doc for why a second attempt was
     *  warranted), minted with a fresh id each load so gearhead can't treat
     *  repeat taps as an already-answered subscription. Tapping it pushes a
     *  new child browse screen — never the now-playing template — built by
     *  the exact same code path as root, so it looks identical (same
     *  tiles, same now-playing ring) except freshly reshuffled; reads as
     *  "refreshed in place" even though it's technically a new browse-stack
     *  frame. Real-hardware confirmed working, and preferred over LEGACY's
     *  jarring detour through the now-playing template. Known, accepted
     *  limitation: gearhead draws its own back-arrow chrome on every pushed
     *  browse screen with no API to suppress it — the same category of hard
     *  platform boundary as the missing haptics/press-highlight documented
     *  in CLAUDE.md, not something more code here can fix.
     */
    enum class RefreshModel { LEGACY, IN_PLACE }

    private const val K_REFRESH_MODEL = "refreshModel"
    private const val REFRESH_MODEL_LEGACY = "legacy"
    private const val REFRESH_MODEL_IN_PLACE = "in_place"

    fun getRefreshModel(ctx: Context): RefreshModel =
        when (prefs(ctx).getString(K_REFRESH_MODEL, null)) {
            REFRESH_MODEL_LEGACY -> RefreshModel.LEGACY
            else -> RefreshModel.IN_PLACE
        }

    fun setRefreshModel(ctx: Context, model: RefreshModel) {
        val value = when (model) {
            RefreshModel.LEGACY -> REFRESH_MODEL_LEGACY
            RefreshModel.IN_PLACE -> REFRESH_MODEL_IN_PLACE
        }
        prefs(ctx).edit().putString(K_REFRESH_MODEL, value).apply()
    }

    // -------------------------------------------- favorites notifications

    /**
     * Off by default. FavoritesNotifier's lock-screen picker used to post
     * itself automatically every time tracking started
     * (SessionLogger.onCreate) with no way to turn it off — real user
     * feedback that this read as unwanted/unexpected notifications. Gates
     * only the automatic call sites (SessionLogger, PlayFavoriteActivity's
     * re-post-after-play); Settings' own explicit "Post now" button always
     * works regardless of this setting, since pressing it already is the
     * explicit enable this setting exists to require for anything
     * unprompted.
     */
    private const val K_FAVORITES_NOTIFICATIONS_ENABLED = "favoritesNotificationsEnabled"

    fun isFavoritesNotificationsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_FAVORITES_NOTIFICATIONS_ENABLED, false)

    fun setFavoritesNotificationsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(K_FAVORITES_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun dump(ctx: Context): String {
        val last = loadLast(ctx)
        val sb = StringBuilder()
        sb.append("stored track:\n")
        if (last == null) sb.append("  (none)\n")
        else {
            sb.append("  title    : ${last.title}\n")
            sb.append("  artist   : ${last.artist}\n")
            sb.append("  album    : ${last.album}\n")
            sb.append("  videoId  : ${last.videoId.ifBlank { "(unresolved)" }}\n")
            sb.append("  position : ${last.positionMs}ms\n")
            sb.append("  liked    : ${last.liked}\n")
        }
        sb.append("cache: ${cacheSize(ctx)} entries\n")
        val seed = loadMixSeed(ctx)
        sb.append("mix seed: ${seed?.let { "${it.title} — ${it.artist}  ${it.videoId}" } ?: "(none)"}\n")
        sb.append("auto-continue eligible: ${isAutoContinueEligible(ctx)}")
        return sb.toString()
    }
}
