package com.ytmprobe

import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Swipe + long-press behavior shared by every favorites row across both home
 * screens (MainActivity/"YTM Old" and QuickPlayActivity/"YTM Launch"): swipe
 * left removes the favorite, swipe right (or long-press) opens the genre-tag
 * picker. Previously duplicated per-screen; kept here once so the two rows
 * can't silently drift apart the way YTM Launch's did (it never got the
 * gesture wiring MainActivity's rows had).
 *
 * A GestureDetector runs alongside the row's normal click listener rather
 * than replacing it — but a fling still ends with ACTION_UP inside a
 * full-width row, so the default click machinery would fire too unless
 * suppressed for the one click that immediately follows a handled fling.
 */
object FavoriteGestures {

    private const val SWIPE_MIN_DISTANCE_PX = 120
    private const val SWIPE_MIN_VELOCITY = 200

    /** Wires click (onPlay), long-press, and swipe onto [row] — do not attach a separate click listener to it. */
    fun attach(ctx: Activity, row: View, f: Favorites.Fav, onPlay: () -> Unit, onChanged: () -> Unit) {
        var flungHandled = false

        val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(velocityX) <= abs(velocityY)) return false
                if (abs(dx) < SWIPE_MIN_DISTANCE_PX || abs(velocityX) < SWIPE_MIN_VELOCITY) return false
                flungHandled = true
                if (dx < 0) removeFavorite(ctx, f, onChanged) else showGenreTagPicker(ctx, f, onChanged)
                return true
            }
        })

        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            if (flungHandled) { flungHandled = false; return@setOnClickListener }
            onPlay()
        }
        row.setOnLongClickListener { showGenreTagPicker(ctx, f, onChanged); true }
        row.setOnTouchListener { _, event -> gestures.onTouchEvent(event); false }
    }

    private fun removeFavorite(ctx: Activity, f: Favorites.Fav, onChanged: () -> Unit) {
        Favorites.remove(ctx, f.key)
        ProbeLog.w(ctx, "removed favorite (swipe): ${f.label()}")
        onChanged()
    }

    /**
     * Long-press tagging (also reachable via swipe right). YTM exposes no
     * genre anywhere (see FINDINGS.md probe A) — this is entirely
     * self-authored. Current tag, if any, is pre-checked.
     */
    fun showGenreTagPicker(ctx: Activity, f: Favorites.Fav, onChanged: () -> Unit) {
        val labels = (listOf("(none)") + Favorites.GENRES).toTypedArray()
        val current = if (f.genre.isBlank()) 0 else (Favorites.GENRES.indexOf(f.genre) + 1).coerceAtLeast(0)
        var picked = current
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Tag genre — ${f.label()}")
            .setSingleChoiceItems(labels, current) { _, which -> picked = which }
            .setPositiveButton("OK") { d, _ ->
                val genre = if (picked == 0) "" else labels[picked]
                Favorites.setGenre(ctx, f.key, genre)
                ProbeLog.w(ctx, "tagged genre \"$genre\": ${f.label()}")
                onChanged()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
