package com.ytmprobe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favorites list, built passively from USER_RATING as tracks play.
 *
 * Keyed on norm(title)|norm(artist) — the same normalisation the resolver
 * uses — because YouTube Music publishes no track identity (see FINDINGS,
 * "What YTM's MediaSession exposes").
 */
object Favorites {

    private const val PREFS = "ytmprobe"
    private const val KEY = "favorites"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Fav(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        var videoId: String,
        val addedAt: Long,
        var lastSeenAt: Long,
        var playCount: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key); put("title", title); put("artist", artist)
            put("album", album); put("durationMs", durationMs)
            put("videoId", videoId); put("addedAt", addedAt)
            put("lastSeenAt", lastSeenAt); put("playCount", playCount)
        }

        companion object {
            fun fromJson(o: JSONObject) = Fav(
                o.optString("key"), o.optString("title"), o.optString("artist"),
                o.optString("album"), o.optLong("durationMs"),
                o.optString("videoId"), o.optLong("addedAt"),
                o.optLong("lastSeenAt"), o.optInt("playCount")
            )
        }

        fun label(): String = "$title — $artist"
    }

    fun keyFor(title: String, artist: String) =
        "${Resolver.norm(title)}|${Resolver.norm(artist)}"

    // ------------------------------------------------------------------ io

    fun all(ctx: Context): MutableList<Fav> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Fav>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Fav.fromJson(it)) }
            }
        }
        return out
    }

    private fun save(ctx: Context, list: List<Fav>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun count(ctx: Context): Int = all(ctx).size

    fun resolvedCount(ctx: Context): Int = all(ctx).count { it.videoId.isNotBlank() }

    // ------------------------------------------------------- capture rules

    enum class Outcome { ADDED, REFRESHED, REMOVED, NONE }

    /**
     * Apply one observation.
     *
     * liked == true   -> add or refresh
     * liked == false  -> remove   (only for an explicit thumb-down)
     * liked == null   -> no signal, do nothing
     *
     * The caller must pass null for isRated=false. A never-rated track and a
     * cleared rating are indistinguishable, so neither may delete anything.
     */
    fun observe(
        ctx: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        liked: Boolean?,
        countPlay: Boolean
    ): Outcome {
        if (title.isBlank() || liked == null) return Outcome.NONE

        val k = keyFor(title, artist)
        val list = all(ctx)
        val idx = list.indexOfFirst { it.key == k }
        val now = System.currentTimeMillis()

        if (!liked) {
            if (idx < 0) return Outcome.NONE
            list.removeAt(idx)
            save(ctx, list)
            return Outcome.REMOVED
        }

        if (idx >= 0) {
            val f = list[idx]
            f.lastSeenAt = now
            if (countPlay) f.playCount += 1
            // Fill the id for free if it has since been cached.
            if (f.videoId.isBlank()) {
                Store.cached(ctx, title, artist)?.let { f.videoId = it }
            }
            save(ctx, list)
            return Outcome.REFRESHED
        }

        list.add(
            Fav(
                key = k, title = title, artist = artist, album = album,
                durationMs = durationMs,
                videoId = Store.cached(ctx, title, artist) ?: "",
                addedAt = now, lastSeenAt = now, playCount = if (countPlay) 1 else 0
            )
        )
        save(ctx, list)
        return Outcome.ADDED
    }

    // ---------------------------------------------------------- resolution

    fun unresolved(ctx: Context): List<Fav> = all(ctx).filter { it.videoId.isBlank() }

    /** Never touches an entry that already has an id — manual picks are permanent. */
    fun setVideoId(ctx: Context, key: String, videoId: String) {
        val list = all(ctx)
        val f = list.firstOrNull { it.key == key } ?: return
        if (f.videoId.isNotBlank()) return
        f.videoId = videoId
        save(ctx, list)
    }

    // -------------------------------------------------------------- picker

    /** Random sample, preferring resolved entries so early lists aren't empty. */
    fun randomSample(ctx: Context, n: Int): List<Fav> {
        val list = all(ctx)
        if (list.isEmpty()) return emptyList()
        val resolved = list.filter { it.videoId.isNotBlank() }.shuffled()
        val rest = list.filter { it.videoId.isBlank() }.shuffled()
        return (resolved + rest).take(n)
    }

    fun remove(ctx: Context, key: String) {
        val list = all(ctx)
        if (list.removeAll { it.key == key }) save(ctx, list)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    fun dump(ctx: Context): String {
        val list = all(ctx).sortedByDescending { it.lastSeenAt }
        if (list.isEmpty()) return "no favorites yet"
        val sb = StringBuilder("${list.size} favorite(s), ${resolvedCount(ctx)} resolved:\n")
        list.forEachIndexed { i, f ->
            sb.append("  [%2d] %-11s %2dx  %s\n".format(
                i, f.videoId.ifBlank { "(unresolved)" }, f.playCount, f.label()))
        }
        return sb.toString().trimEnd()
    }
}
