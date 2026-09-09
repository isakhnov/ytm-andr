package com.ytmlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favorites list, built passively from USER_RATING as tracks play.
 *
 * Keyed on norm(title)|norm(artist) — the same normalisation the resolver
 * uses — because YouTube Music publishes no track identity (see
 * ytmprobe/FINDINGS.md, "What YTM's MediaSession exposes").
 */
object Favorites {

    private const val PREFS = "ytmlauncher"
    private const val KEY = "favorites"

    /**
     * YTM exposes no genre anywhere (probe A's 9 metadata keys don't include
     * one, and InnerTube search results don't carry one either) — this is a
     * fixed set, derived from a real survey of this library against iTunes'
     * search API cross-checked by hand (see ytmprobe/FINDINGS.md). "Russian"
     * absorbs anything in Cyrillic script regardless of its actual iTunes
     * genre — iTunes' catalog has no reliable language/regional dimension, so
     * nearly every Cyrillic-artist track just came back generic "Rock"
     * there, which wasn't a useful distinction for this library. Kept as a
     * plain list so the picker UI stays a simple tap-to-choose dialog (see
     * GenreTagger, FavoriteActions.showGenreTagPicker) — never free text.
     */
    val GENRES = listOf(
        "Rock", "Russian", "Metal", "Alternative", "Pop", "Country", "Electronic", "Classical",
        "Uncategorized"
    )

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
        var playCount: Int,
        var genre: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key); put("title", title); put("artist", artist)
            put("album", album); put("durationMs", durationMs)
            put("videoId", videoId); put("addedAt", addedAt)
            put("lastSeenAt", lastSeenAt); put("playCount", playCount)
            put("genre", genre)
        }

        companion object {
            fun fromJson(o: JSONObject) = Fav(
                o.optString("key"), o.optString("title"), o.optString("artist"),
                o.optString("album"), o.optLong("durationMs"),
                o.optString("videoId"), o.optLong("addedAt"),
                o.optLong("lastSeenAt"), o.optInt("playCount"),
                o.optString("genre")
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

    fun unGenred(ctx: Context): List<Fav> = all(ctx).filter { it.genre.isBlank() }

    /** Never touches an entry that already has an id — manual picks are permanent. */
    fun setVideoId(ctx: Context, key: String, videoId: String) {
        val list = all(ctx)
        val f = list.firstOrNull { it.key == key } ?: return
        if (f.videoId.isNotBlank()) return
        f.videoId = videoId
        save(ctx, list)
    }

    /** Unlike setVideoId, this always overwrites — re-tagging is a normal correction, not a one-shot fill-in. */
    fun setGenre(ctx: Context, key: String, genre: String) {
        val list = all(ctx)
        val f = list.firstOrNull { it.key == key } ?: return
        f.genre = genre
        save(ctx, list)
    }

    /**
     * Advances `current` to the next entry in `genres`, wrapping around —
     * the single step behind AutoMediaService's now-playing "Reclassify"
     * action (see its doc for why a cycling button, not a picker screen, is
     * what Android Auto's framework actually allows here). `genres`
     * defaults to GENRES so every real call site (just AutoMediaService)
     * needs zero code, zero awareness of the list's contents, to stay
     * correct the moment GENRES gains, loses, or reorders an entry — the
     * parameter exists only so FavoritesTest can prove that genericity
     * directly, against lists GENRES will never actually contain, rather
     * than only ever re-testing today's fixed 9 entries. Unrecognized or
     * blank input (indexOf returns -1) lands on genres[0] with no
     * special-casing needed.
     */
    fun nextGenre(current: String, genres: List<String> = GENRES): String {
        val idx = genres.indexOf(current)
        return genres[(idx + 1).mod(genres.size)]
    }

    // -------------------------------------------------------------- picker

    /**
     * Random sample, preferring resolved entries so early lists aren't empty.
     * genre == null means no filter (any genre); otherwise matches exactly.
     */
    fun randomSample(ctx: Context, n: Int, genre: String? = null): List<Fav> {
        val list = all(ctx).filter { genre == null || it.genre == genre }
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
            sb.append("  [%2d] %-11s %2dx  %-13s %s\n".format(
                i, f.videoId.ifBlank { "(unresolved)" }, f.playCount,
                f.genre.ifBlank { "(none)" }, f.label()))
        }
        return sb.toString().trimEnd()
    }
}
