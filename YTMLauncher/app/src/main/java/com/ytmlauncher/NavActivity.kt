package com.ytmlauncher

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Base class shared by LaunchActivity and SettingsActivity — owns the 3-dot
 * nav menu (just "Settings"; LaunchActivity is always the screen you're
 * already on, so it isn't listed) and edge-to-edge inset handling.
 * onContentChanged() runs right after every subclass's setContentView(), so
 * this pads whatever the actual top-level content view turns out to be,
 * with zero changes needed per screen.
 */
abstract class NavActivity : Activity() {

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
        menu.add(0, 1, 0, "Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
