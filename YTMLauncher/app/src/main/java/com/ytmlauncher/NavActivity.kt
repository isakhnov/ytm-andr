package com.ytmlauncher

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Base class shared by LaunchActivity and SettingsActivity — owns the
 * top-bar Settings action and edge-to-edge inset handling.
 * onContentChanged() runs right after every subclass's setContentView(), so
 * this pads whatever the actual top-level content view turns out to be,
 * with zero changes needed per screen.
 *
 * The Settings action used to be a plain unicoded MenuItem, which Android
 * renders behind a "..." overflow button — one tap to open the dropdown,
 * a second to actually pick "Settings", real user feedback that the first
 * tap should just go straight there. SHOW_AS_ACTION_ALWAYS plus an icon
 * makes it its own always-visible button instead of overflow content, so
 * tapping it directly fires onOptionsItemSelected with no intermediate
 * menu. Not shown on SettingsActivity itself — a "Settings" action pointing
 * at the screen already on screen doesn't mean anything; that screen gets
 * an actual Up/back arrow instead, wired below.
 */
abstract class NavActivity : Activity() {

    companion object {
        private const val MENU_ID_SETTINGS = 1
    }

    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (this !is SettingsActivity) {
            menu.add(0, MENU_ID_SETTINGS, 0, "Settings").apply {
                // The classic "..." glyph, not a gear/wrench icon — visual
                // continuity with the old system-drawn overflow button was
                // explicitly requested; only the *behavior* changed (single
                // tap straight to Settings), not the look.
                setIcon(R.drawable.ic_more)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ID_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        // Up/back arrow — only actually shown on SettingsActivity (see its
        // onCreate), but handled here so both subclasses share one path.
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }
}
