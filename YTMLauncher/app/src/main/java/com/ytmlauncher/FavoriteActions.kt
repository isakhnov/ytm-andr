package com.ytmlauncher

import android.app.Activity

/**
 * The two actions available on a favorite row from LaunchActivity's
 * ItemTouchHelper.SwipeCallback (LEFT = remove, RIGHT = tag genre; see
 * LaunchActivity's SwipeCallback for the drag/reveal handling itself) and
 * from GenreRowAdapter's long-press listener.
 *
 * Formerly FavoriteGestures, which also owned a GestureDetector-based swipe
 * implementation (attach()) used by two now-retired screens. LaunchActivity
 * never used it — it drives ItemTouchHelper directly — so that
 * implementation was dropped rather than ported; only these two actions
 * survived.
 */
object FavoriteActions {

    fun removeFavorite(ctx: Activity, f: Favorites.Fav, onChanged: () -> Unit) {
        Favorites.remove(ctx, f.key)
        ProbeLog.w(ctx, "removed favorite (swipe): ${f.label()}")
        onChanged()
    }

    /**
     * YTM exposes no genre anywhere (see ytmprobe/FINDINGS.md probe A) —
     * this is entirely self-authored. Current tag, if any, is pre-checked.
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
