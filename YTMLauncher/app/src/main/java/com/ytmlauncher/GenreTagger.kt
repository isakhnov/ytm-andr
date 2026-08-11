package com.ytmlauncher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Automatic genre classification for favorites.
 *
 * YTM exposes no genre anywhere (see ytmprobe/FINDINGS.md, probe A's 9
 * metadata keys) — this pulls from Apple's iTunes Search API, free and
 * unauthenticated, the same "no auth, no API key" approach Resolver.kt
 * already uses for video matching. It returns a primaryGenreName per
 * candidate; candidates are scored against the known artist the same way
 * Resolver scores video candidates (LCS ratio on normalised text), and a
 * weak match is rejected rather than trusted — otherwise a search can
 * silently return the wrong artist entirely (confirmed during the survey:
 * "Boulevard of Broken Dreams" by Green Day matched a lullaby-cover artist).
 *
 * Cyrillic-script artists/titles are bucketed as "Russian" outright, without
 * calling iTunes at all — a manual comparison against ~50 real favorites
 * showed iTunes' catalog has no reliable language/regional dimension, so
 * nearly every Cyrillic-artist track just came back generic "Rock" there,
 * which wasn't a useful distinction for this library.
 */
object GenreTagger {

    private val CYRILLIC = Regex("\\p{IsCyrillic}")

    /** iTunes' raw genre vocabulary is finer-grained than Favorites.GENRES; collapse it here. */
    private val GENRE_MAP = mapOf(
        "Pop Latino" to "Pop",
        "Outlaw Country" to "Country",
        "Dance" to "Electronic",
        "Hard Rock" to "Rock",
        "Rock in Russian" to "Russian"
    )

    fun isCyrillic(s: String) = CYRILLIC.containsMatchIn(s)

    private fun mapGenre(raw: String?): String {
        if (raw == null) return "Uncategorized"
        GENRE_MAP[raw]?.let { return it }
        return if (raw in Favorites.GENRES) raw else "Uncategorized"
    }

    /**
     * iTunes rate-limits fairly aggressively — a burst of unpaced requests
     * started returning 403s during testing. Retry with backoff rather than
     * giving up on the first one; callers (see Probes.tagGenres) still need
     * to pace *between* calls on top of this.
     */
    private fun search(query: String): String {
        val url = "https://itunes.apple.com/search?term=" +
            URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=5"

        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "ytmlauncher/1.0")
                }
                val code = conn.responseCode
                if (code == 403 && attempt < 2) {
                    Thread.sleep(5000L * (attempt + 1))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("iTunes search failed")
    }

    /** Call off the main thread — does network I/O and may sleep on rate-limit retries. */
    fun classify(title: String, artist: String): String {
        if (isCyrillic(title) || isCyrillic(artist)) return "Russian"

        val raw = runCatching { search("$title $artist".trim()) }.getOrNull()
            ?: return "Uncategorized"
        val results = runCatching { JSONObject(raw).optJSONArray("results") }.getOrNull()
            ?: return "Uncategorized"

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val score = Resolver.ratio(Resolver.norm(r.optString("artistName")), Resolver.norm(artist))
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }

        if (artist.isNotBlank() && bestScore < 0.6) return "Uncategorized"
        return mapGenre(best?.optString("primaryGenreName"))
    }
}
