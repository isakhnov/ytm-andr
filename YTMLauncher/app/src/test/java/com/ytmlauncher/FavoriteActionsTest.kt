package com.ytmlauncher

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Ported from ytmprobe's FavoriteGesturesTest, trimmed to the two actions
 * that survived the FavoriteGestures -> FavoriteActions cut (see that file's
 * doc comment): removeFavorite() and showGenreTagPicker(). The fling-
 * direction cases don't port — attach() (the GestureDetector-based swipe
 * implementation they exercised) was dead code even in ytmprobe by the time
 * of this port, since LaunchActivity drives swipe through
 * ItemTouchHelper.SwipeCallback directly. That SwipeCallback itself still has
 * no test coverage, a pre-existing gap carried forward, not introduced here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteActionsTest {

    private lateinit var ctx: Context
    private lateinit var activity: Activity
    private lateinit var fav: Favorites.Fav

    private var changedCalled = false

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Favorites.clear(ctx)
        activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()

        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        fav = Favorites.all(ctx).first()

        changedCalled = false
    }

    @Test
    fun `removeFavorite removes the entry and calls onChanged`() {
        FavoriteActions.removeFavorite(activity, fav) { changedCalled = true }

        assertTrue("expected the favorite to have been removed", Favorites.all(ctx).isEmpty())
        assertTrue("onChanged should fire after removal", changedCalled)
    }

    @Test
    fun `showGenreTagPicker opens a dialog without mutating anything until OK`() {
        FavoriteActions.showGenreTagPicker(activity, fav) { changedCalled = true }

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("expected the genre-tag dialog to be showing", dialog != null && dialog.isShowing)
        assertEquals("", Favorites.all(ctx).first { it.key == fav.key }.genre)
        assertTrue("nothing should have changed until OK is tapped", !changedCalled)
    }

    @Test
    fun `showGenreTagPicker OK applies the picked genre and calls onChanged`() {
        FavoriteActions.showGenreTagPicker(activity, fav) { changedCalled = true }

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val rockIndex = Favorites.GENRES.indexOf("Rock") + 1 // offset by the leading "(none)" entry
        dialog.listView.performItemClick(null, rockIndex, dialog.listView.getItemIdAtPosition(rockIndex))
        shadowOf(Looper.getMainLooper()).idle()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Rock", Favorites.all(ctx).first { it.key == fav.key }.genre)
        assertTrue("onChanged should fire after tagging", changedCalled)
    }
}
