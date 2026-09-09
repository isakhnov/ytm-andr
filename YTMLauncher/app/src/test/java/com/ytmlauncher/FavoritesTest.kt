package com.ytmlauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down Favorites.observe()'s capture rules exactly as documented in
 * ytmprobe/FINDINGS.md ("Resulting architecture" / Favourites row) and the
 * class doc: liked=true adds/refreshes, liked=false removes ONLY an
 * already-present entry, liked=null is inert either way — a never-rated
 * track and a cleared rating are indistinguishable, so neither may delete a
 * favorite.
 *
 * sdk=34: Robolectric 4.13 doesn't ship full shadows for API 36 (the app's
 * actual compileSdk) yet — this only pins what SDK Robolectric simulates for
 * the test JVM, unrelated to what the shipped app targets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Favorites.clear(ctx)
    }

    @Test
    fun `liked true on a new track adds it`() {
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        assertEquals(Favorites.Outcome.ADDED, outcome)
        assertEquals(1, Favorites.count(ctx))
    }

    @Test
    fun `liked true on an already-present track refreshes not duplicates`() {
        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        assertEquals(Favorites.Outcome.REFRESHED, outcome)
        assertEquals(1, Favorites.count(ctx))
    }

    @Test
    fun `liked false removes an already-present favorite`() {
        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = false, countPlay = false)
        assertEquals(Favorites.Outcome.REMOVED, outcome)
        assertEquals(0, Favorites.count(ctx))
    }

    @Test
    fun `liked false on a track that was never a favorite does nothing`() {
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = false, countPlay = false)
        assertEquals(Favorites.Outcome.NONE, outcome)
        assertEquals(0, Favorites.count(ctx))
    }

    @Test
    fun `liked null never adds even though isRated could be misread as false`() {
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = null, countPlay = true)
        assertEquals(Favorites.Outcome.NONE, outcome)
        assertEquals(0, Favorites.count(ctx))
    }

    @Test
    fun `liked null never removes an existing favorite either`() {
        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        val outcome = Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = null, countPlay = true)
        assertEquals(Favorites.Outcome.NONE, outcome)
        assertEquals(1, Favorites.count(ctx))
    }

    @Test
    fun `blank title is always a no-op regardless of liked state`() {
        val outcome = Favorites.observe(ctx, "", "Artist", "Album", 200_000, liked = true, countPlay = true)
        assertEquals(Favorites.Outcome.NONE, outcome)
        assertEquals(0, Favorites.count(ctx))
    }

    @Test
    fun `setGenre always overwrites even an existing tag`() {
        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        val key = Favorites.keyFor("Song", "Artist")
        Favorites.setGenre(ctx, key, "Rock")
        Favorites.setGenre(ctx, key, "Metal")
        assertEquals("Metal", Favorites.all(ctx).first { it.key == key }.genre)
    }

    @Test
    fun `setVideoId never overwrites an already-resolved entry`() {
        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        val key = Favorites.keyFor("Song", "Artist")
        Favorites.setVideoId(ctx, key, "first")
        Favorites.setVideoId(ctx, key, "second")
        assertEquals("first", Favorites.all(ctx).first { it.key == key }.videoId)
    }

    // -------------------------------------------------------- nextGenre
    // AutoMediaService's Android Auto "Reclassify" cycling button — no
    // Context/device needed, so these run as plain JVM logic tests despite
    // living in this Robolectric-annotated class.

    @Test
    fun `nextGenre visits every real genre exactly once and wraps back to the start`() {
        val visited = mutableListOf<String>()
        var current = Favorites.GENRES[0]
        repeat(Favorites.GENRES.size) {
            visited.add(current)
            current = Favorites.nextGenre(current)
        }
        assertEquals(Favorites.GENRES, visited)          // full loop, in order, no repeats/skips
        assertEquals(Favorites.GENRES[0], current)        // one more step lands back at the start
    }

    @Test
    fun `nextGenre treats an unrecognized or blank genre as starting before the first entry`() {
        assertEquals(Favorites.GENRES[0], Favorites.nextGenre(""))
        assertEquals(Favorites.GENRES[0], Favorites.nextGenre("Not A Real Genre"))
    }

    @Test
    fun `nextGenre is driven entirely by whatever list is passed in`() {
        // A synthetic list containing a genre Favorites.GENRES will never actually
        // have — proof this needs zero code change the day a real genre is
        // added, not just a re-run against today's fixed 9 entries.
        val hypothetical = listOf("Rock", "Lo-Fi", "K-Pop")
        assertEquals("Lo-Fi", Favorites.nextGenre("Rock", hypothetical))
        assertEquals("K-Pop", Favorites.nextGenre("Lo-Fi", hypothetical))
        assertEquals("Rock", Favorites.nextGenre("K-Pop", hypothetical))   // wraps
    }

    @Test
    fun `nextGenre handles a single-entry list without dividing by a bad index`() {
        assertEquals("Solo", Favorites.nextGenre("Solo", listOf("Solo")))
        assertEquals("Solo", Favorites.nextGenre("Anything", listOf("Solo")))
    }
}
