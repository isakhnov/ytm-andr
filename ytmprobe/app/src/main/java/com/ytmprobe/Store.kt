package com.ytmprobe

import android.content.Context
import org.json.JSONObject

/**
 * Persistence. Replaces last.env and known.json from the v7 shell toolkit.
 *
 * Two things are stored:
 *   last  — the most recent track, overwritten as it changes
 *   cache — title|artist -> videoId, so a confirmed resolution never repeats
 */
object Store {

    private const val PREFS = "ytmprobe"
    private const val K_LAST = "last"
    private const val K_CACHE = "cache"

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
        sb.append("cache: ${cacheSize(ctx)} entries")
        return sb.toString()
    }
}
