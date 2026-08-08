package com.ytmprobe

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves "title + artist" to a YouTube Music videoId.
 *
 * YTM's MediaSession exposes no track identity — METADATA_KEY_MEDIA_ID is
 * absent and every queue item has mediaId=null (confirmed by probes A and E).
 * So identity has to be reconstructed by search, exactly as the v7 shell
 * toolkit did with ytmusicapi.
 *
 * This is the same InnerTube endpoint ytmusicapi wraps, called directly:
 * unauthenticated, no API key, no dependencies.
 */
object Resolver {

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search"
    private const val CLIENT_VERSION = "1.20240403.01.00"

    // InnerTube filter params
    private const val FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    private const val FILTER_VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA=="

    data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String,
        val source: String,
        val durationSec: Int = -1,
        val type: String = "",
        var score: Double = 0.0
    ) {
        fun durationText(): String =
            if (durationSec < 0) "?" else "%d:%02d".format(durationSec / 60, durationSec % 60)
    }

    // ------------------------------------------------------------------
    // network
    // ------------------------------------------------------------------

    private fun search(query: String, params: String?): String {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
            if (params != null) put("params", params)
        }.toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    // ------------------------------------------------------------------
    // parsing
    //
    // The response is deeply nested and its shape shifts between versions,
    // so rather than walking a fixed path we collect every
    // musicResponsiveListItemRenderer found anywhere in the tree.
    // ------------------------------------------------------------------

    private fun collectItems(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    node.optJSONObject("musicResponsiveListItemRenderer")?.let { out.add(it) }
                }
                for (k in node.keys()) collectItems(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectItems(node.opt(i), out)
        }
    }

    /**
     * Every "text" run under this node, in document order.
     *
     * Must check the value is genuinely a String. org.json's optString()
     * coerces objects via toString(), so a "text" key whose value is
     * {"runs":[...]} silently returns the entire JSON blob as the title.
     */
    private fun texts(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val t = node.opt("text")
                if (t is String && t.isNotBlank()) out.add(t)
                for (k in node.keys()) texts(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) texts(node.opt(i), out)
        }
    }

    private val DURATION_RE = Regex("^(\\d{1,2}):(\\d{2})$")

    private fun parseDuration(s: String): Int? =
        DURATION_RE.find(s.trim())?.let {
            it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }

    private fun parse(json: String, source: String): List<Candidate> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<JSONObject>()
        collectItems(root, items)

        val out = mutableListOf<Candidate>()
        for (item in items) {
            val vid = item.optJSONObject("playlistItemData")?.optString("videoId")
                ?: item.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
            if (vid.isNullOrBlank()) continue

            val runs = mutableListOf<String>()
            texts(item.optJSONArray("flexColumns"), runs)
            texts(item.optJSONArray("fixedColumns"), runs)

            var duration = -1
            val clean = mutableListOf<String>()
            for (r in runs) {
                val t = r.trim()
                if (t.isBlank() || t == "•" || t == " • ") continue
                val d = parseDuration(t)
                if (d != null) { if (duration < 0) duration = d; continue }
                if (t.equals("Song", true) || t.equals("Video", true)) continue
                clean.add(t)
            }

            val title = clean.getOrNull(0) ?: ""
            val artist = clean.getOrNull(1) ?: ""
            if (title.isBlank()) continue

            // ATV = official audio track, OMV = official music video,
            // UGC = user upload. Prefer official releases on ties.
            val raw = item.toString()
            val type = when {
                raw.contains("MUSIC_VIDEO_TYPE_ATV") -> "ATV"
                raw.contains("MUSIC_VIDEO_TYPE_OMV") -> "OMV"
                raw.contains("MUSIC_VIDEO_TYPE_UGC") -> "UGC"
                else -> ""
            }

            out.add(Candidate(vid, title, artist, source, duration, type))
        }
        return out
    }

    // ------------------------------------------------------------------
    // scoring — same weighting as the v7 toolkit
    // ------------------------------------------------------------------

    private val NOISE = Regex(
        "\\b(official|video|audio|lyrics?|hd|hq|remaster(ed)?|explicit|" +
                "feat|ft|prod|extended|radio\\s*edit|music\\s*video)\\b",
        RegexOption.IGNORE_CASE
    )

    fun norm(s: String?): String {
        val t = NOISE.replace((s ?: "").lowercase(), " ")
        return t.filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(" ").filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Longest-common-subsequence ratio, 0..1. */
    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1
                else maxOf(prev[j], cur[j - 1])
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 2.0 * prev[b.length] / (a.length + b.length)
    }

    /**
     * Weighting: title 50, artist 25, album 10, duration 15.
     *
     * Duration is the hardest signal available — a cover or a partial edit
     * almost never matches the original runtime, where fuzzy text often
     * does. durationMs comes from METADATA_KEY_DURATION on the session.
     */
    fun score(
        c: Candidate,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): Double {
        var s = ratio(norm(c.title), norm(title)) * 50 +
                ratio(norm(c.artist), norm(artist)) * 25

        if (album.isNotBlank()) {
            // For singles the album equals the title, so match either.
            val albumFit = maxOf(
                ratio(norm(c.title), norm(album)),
                ratio(norm(c.artist), norm(album))
            )
            s += albumFit * 10
        }

        if (durationMs > 0 && c.durationSec > 0) {
            val delta = kotlin.math.abs(c.durationSec - (durationMs / 1000).toInt())
            s += when {
                delta <= 2  -> 15.0
                delta <= 5  -> 11.0
                delta <= 15 -> 5.0
                delta <= 30 -> 0.0
                else        -> -10.0     // wrong length: actively penalise
            }
        }

        if (norm(c.title) == norm(title)) s += 5
        if (norm(c.artist) == norm(artist)) s += 5

        s += when (c.type) {
            "ATV" -> 5.0     // official audio track
            "OMV" -> 2.0     // official music video
            "UGC" -> -5.0    // user upload: usually a cover
            else  -> 0.0
        }

        return maxOf(0.0, minOf(100.0, s))
    }

    // ------------------------------------------------------------------
    // public entry point — call off the main thread
    // ------------------------------------------------------------------

    fun resolve(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        limit: Int = 8
    ): List<Candidate> {
        val query = "$title $artist".trim()
        val seen = mutableSetOf<String>()
        val all = mutableListOf<Candidate>()

        for ((params, label) in listOf(
            FILTER_SONGS to "songs",
            FILTER_VIDEOS to "videos",
            null to "any"
        )) {
            val raw = runCatching { search(query, params) }.getOrNull() ?: continue
            for (c in parse(raw, label)) {
                if (seen.add(c.videoId)) all.add(c)
            }
            // Stop early on a confident hit from the songs shelf.
            if (label == "songs" &&
                all.any { score(it, title, artist, album, durationMs) >= 95 }) break
        }

        all.forEach { it.score = score(it, title, artist, album, durationMs) }
        return all.sortedByDescending { it.score }.take(limit)
    }
}
