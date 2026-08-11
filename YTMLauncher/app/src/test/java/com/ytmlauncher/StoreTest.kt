package com.ytmlauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence round-trips — including the mix seed / auto-continue-eligible
 * state used by the auto-continue feature, so a serialization regression
 * there (e.g. a JSON key typo) fails fast instead of silently no-op'ing in
 * the field on a real drive.
 *
 * sdk=34: see FavoritesTest — Robolectric 4.13 doesn't ship API 36 shadows yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `saveLast then loadLast round-trips every field`() {
        val track = Store.Track("Title", "Artist", "Album", "vid123", 4242L, liked = true)
        Store.saveLast(ctx, track)
        val loaded = Store.loadLast(ctx)
        assertEquals(track.title, loaded?.title)
        assertEquals(track.artist, loaded?.artist)
        assertEquals(track.album, loaded?.album)
        assertEquals(track.videoId, loaded?.videoId)
        assertEquals(track.positionMs, loaded?.positionMs)
        assertEquals(track.liked, loaded?.liked)
    }

    @Test
    fun `loadLast returns null before anything has been saved`() {
        assertNull(Store.loadLast(ctx))
    }

    @Test
    fun `cache is keyed on normalised title and artist`() {
        Store.cache(ctx, "Song (Official Video)", "Artist", "vid1")
        // Same song under noise-word-stripped, differently-cased input should hit the same entry.
        assertEquals("vid1", Store.cached(ctx, "song", "ARTIST"))
    }

    @Test
    fun `cached returns null for an unresolved entry`() {
        assertNull(Store.cached(ctx, "Never Resolved", "Nobody"))
    }

    @Test
    fun `clearCache empties the cache`() {
        Store.cache(ctx, "Song", "Artist", "vid1")
        Store.clearCache(ctx)
        assertEquals(0, Store.cacheSize(ctx))
        assertNull(Store.cached(ctx, "Song", "Artist"))
    }

    @Test
    fun `mix seed round-trips`() {
        Store.saveMixSeed(ctx, "Title", "Artist", "vid123")
        val seed = Store.loadMixSeed(ctx)
        assertEquals("Title", seed?.title)
        assertEquals("Artist", seed?.artist)
        assertEquals("vid123", seed?.videoId)
    }

    @Test
    fun `mix seed is null before anything has been saved`() {
        assertNull(Store.loadMixSeed(ctx))
    }

    @Test
    fun `auto-continue eligibility defaults to false`() {
        assertEquals(false, Store.isAutoContinueEligible(ctx))
    }

    @Test
    fun `auto-continue eligibility round-trips true and back to false`() {
        Store.setAutoContinueEligible(ctx, true)
        assertEquals(true, Store.isAutoContinueEligible(ctx))
        Store.setAutoContinueEligible(ctx, false)
        assertEquals(false, Store.isAutoContinueEligible(ctx))
    }
}
