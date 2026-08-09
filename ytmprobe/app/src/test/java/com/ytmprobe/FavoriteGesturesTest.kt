package com.ytmprobe

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Exercises FavoriteGestures' own swipe-interpretation logic with synthetic
 * MotionEvent sequences — this is what actually regressed when targetSdk was
 * bumped to 36 (see AndroidManifest.xml's enableOnBackInvokedCallback
 * comment). Important scope note: that regression was the *system's*
 * predictive-back gesture intercepting the touch stream before it ever
 * reached our view — a platform/window-level behavior Robolectric does not
 * simulate, so no unit test, including this one, could have caught that
 * specific failure. What this suite *does* guard is our own left/right/
 * below-threshold interpretation logic never regressing on its own terms.
 *
 * sdk=34: see FavoritesTest — Robolectric 4.13 doesn't ship API 36 shadows yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteGesturesTest {

    private lateinit var ctx: Context
    private lateinit var activity: Activity
    private lateinit var row: View
    private lateinit var fav: Favorites.Fav

    private var playCalled = false
    private var changedCalled = false

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Favorites.clear(ctx)
        activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        row = View(activity)
        // Attached as the real content view (not a bare unparented View) —
        // Android's default click-on-ACTION_UP handling needs a real window/
        // view-root attachment to fire reliably, and every test's touch
        // coordinates must land inside its laid-out bounds.
        activity.setContentView(row, android.view.ViewGroup.LayoutParams(1000, 300))
        Robolectric.flushForegroundThreadScheduler()

        Favorites.observe(ctx, "Song", "Artist", "Album", 200_000, liked = true, countPlay = true)
        fav = Favorites.all(ctx).first()

        playCalled = false
        changedCalled = false
        FavoriteGestures.attach(activity, row, fav, onPlay = { playCalled = true }, onChanged = { changedCalled = true })
    }

    /**
     * downTime=0 fixed; each (time, x) pair becomes one MotionEvent, y held
     * constant. Idles the main looper afterward — Robolectric's default
     * PAUSED looper mode means a tap's click Runnable (posted by View's own
     * ACTION_UP handling, not by our code) never actually runs otherwise.
     */
    private fun dispatch(vararg points: Pair<Long, Float>, y: Float = 100f) {
        val actions = listOf(MotionEvent.ACTION_DOWN) +
            List(points.size - 2) { MotionEvent.ACTION_MOVE } +
            listOf(MotionEvent.ACTION_UP)
        points.forEachIndexed { i, (time, x) ->
            val event = MotionEvent.obtain(0L, time, actions[i], x, y, 0)
            row.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `fast leftward swipe removes the favorite`() {
        dispatch(0L to 500f, 30L to 200f, 60L to 20f)

        assertTrue("expected the favorite to have been removed", Favorites.all(ctx).isEmpty())
        assertTrue("onChanged should fire after a swipe-triggered removal", changedCalled)
        assertFalse("onPlay must not fire for a swipe — only for a genuine tap", playCalled)
    }

    @Test
    fun `fast rightward swipe opens the genre picker without mutating anything`() {
        dispatch(0L to 20f, 30L to 300f, 60L to 500f)

        assertTrue("still present — swipe right ", Favorites.all(ctx).isNotEmpty())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("expected the genre-tag dialog to be showing", dialog != null && dialog.isShowing)
        assertFalse("onPlay must not fire for a swipe — only for a genuine tap", playCalled)
    }

    @Test
    fun `a short slow drag below the distance and velocity thresholds does nothing`() {
        // 40px over 200ms — real but far too slow/short to count as a swipe.
        dispatch(0L to 500f, 200L to 460f)

        assertTrue("nothing should have been removed", Favorites.all(ctx).isNotEmpty())
        assertFalse(changedCalled)
        assertNull("no dialog should have opened", ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun `a mostly-vertical drag is treated as scrolling, not a swipe`() {
        // Fast, but vertical dominates horizontal — matches "flick to scroll the list".
        val event1 = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 300f, 50f, 0)
        val event2 = MotionEvent.obtain(0L, 30L, MotionEvent.ACTION_MOVE, 280f, 400f, 0)
        val event3 = MotionEvent.obtain(0L, 60L, MotionEvent.ACTION_UP, 260f, 700f, 0)
        row.dispatchTouchEvent(event1); row.dispatchTouchEvent(event2); row.dispatchTouchEvent(event3)
        event1.recycle(); event2.recycle(); event3.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("a vertical fling must not remove the favorite", Favorites.all(ctx).isNotEmpty())
        assertFalse(changedCalled)
    }

    @Test
    fun `a tap that never registered as a swipe calls onPlay`() {
        // Deliberately not replaying raw MotionEvents into a click here —
        // whether Android's own View internals decide a given touch sequence
        // counts as "a tap" is framework behavior this app doesn't own or
        // need to re-verify. What FavoriteGestures actually owns is the
        // flungHandled gate in its click listener (see the doc comment on
        // attach()); this tests that directly. The swipe tests above already
        // cover the flungHandled=true path end-to-end via real touch replay.
        row.performClick()
        assertTrue(playCalled)
        assertFalse(changedCalled)
    }
}
