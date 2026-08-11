package com.ytmlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down Resolver's scoring weights (ytmprobe/FINDINGS.md "Resolution"
 * section: title 50 / artist 25 / album 10 / duration 15, ATV/OMV/UGC
 * bonus-penalty) and the org.json optString() coercion trap norm()/texts()
 * exist to avoid (FINDINGS.md "Traps and gotchas" — the single hardest bug
 * in the project's history). Uses relative comparisons (does X outrank Y)
 * rather than exact fuzzy-match values, since LCS ratio isn't
 * hand-reproducible reliably, but the *direction* of every weight is
 * exactly what a regression would break.
 */
class ResolverTest {

    private fun candidate(
        videoId: String = "abc",
        title: String,
        artist: String,
        durationSec: Int = -1,
        type: String = ""
    ) = Resolver.Candidate(videoId, title, artist, "songs", durationSec, type)

    // -------------------------------------------------------------- norm()

    @Test
    fun `norm lowercases and strips punctuation`() {
        assertEquals("hello world", Resolver.norm("Hello, World!"))
    }

    @Test
    fun `norm strips noise words like official and remastered`() {
        assertEquals("song", Resolver.norm("Song (Official Video)"))
        assertEquals("song", Resolver.norm("Song - Remastered"))
    }

    @Test
    fun `norm handles null and blank`() {
        assertEquals("", Resolver.norm(null))
        assertEquals("", Resolver.norm(""))
    }

    // ------------------------------------------------------------- score()

    @Test
    fun `exact title and artist match scores near maximum`() {
        val c = candidate(title = "Nothing Else Matters", artist = "Metallica")
        val score = Resolver.score(c, "Nothing Else Matters", "Metallica")
        assertTrue("expected a high score for an exact match, got $score", score >= 80.0)
    }

    @Test
    fun `completely unrelated candidate scores far below an exact match`() {
        // Absolute LCS-ratio values aren't hand-reproducible (short strings can
        // share incidental letters), but an unrelated candidate must never come
        // close to an exact match — that's the actual behavior contract.
        val exact = Resolver.score(
            candidate(title = "Nothing Else Matters", artist = "Metallica"),
            "Nothing Else Matters", "Metallica"
        )
        val unrelated = Resolver.score(
            candidate(title = "Some Other Song", artist = "Some Other Band"),
            "Nothing Else Matters", "Metallica"
        )
        assertTrue(
            "unrelated ($unrelated) should score far below an exact match ($exact)",
            exact - unrelated >= 40.0
        )
    }

    @Test
    fun `duration within 2s bonus outranks duration off by more than 30s`() {
        val base = candidate(title = "Song", artist = "Artist", type = "ATV")
        val close = base.copy(durationSec = 200)
        val wrong = base.copy(durationSec = 260) // 60s off — the actively-penalised bucket

        val closeScore = Resolver.score(close, "Song", "Artist", durationMs = 200_000)
        val wrongScore = Resolver.score(wrong, "Song", "Artist", durationMs = 200_000)

        // +15 vs -10 on the duration term: a 25-point spread at minimum.
        assertTrue(
            "duration match ($closeScore) should clearly outscore a >30s-off duration ($wrongScore)",
            closeScore - wrongScore >= 20.0
        )
    }

    @Test
    fun `ATV outranks UGC for otherwise identical candidates`() {
        val title = "Song"; val artist = "Artist"
        val atv = candidate(title = title, artist = artist, type = "ATV")
        val ugc = candidate(title = title, artist = artist, type = "UGC")

        val atvScore = Resolver.score(atv, title, artist)
        val ugcScore = Resolver.score(ugc, title, artist)

        // ATV +5 vs UGC -5: exactly a 10-point spread, matching FINDINGS.md's
        // real example (100.0 for the correct ATV vs 68.8 for the UGC cover).
        assertTrue(
            "ATV ($atvScore) should outrank UGC ($ugcScore) by the documented bonus/penalty spread",
            atvScore - ugcScore >= 9.0
        )
    }

    @Test
    fun `score is clamped to the 0 to 100 range`() {
        val perfect = candidate(title = "Song", artist = "Artist", durationSec = 200, type = "ATV")
        val score = Resolver.score(perfect, "Song", "Artist", "Song", 200_000)
        assertTrue("score $score should never exceed 100", score <= 100.0)
        assertTrue("score $score should never be negative", score >= 0.0)
    }
}
