package com.ytmprobe

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem

/**
 * Base class for the 3-dot navigation menu shared by every screen
 * (YTM Launch / YTM Old / Diagnostics). Previously copy-pasted identically
 * into all three Activities — that kind of duplication is exactly how
 * QuickPlayActivity ended up missing the status/mix header for a whole round
 * of changes: nothing forced every screen to stay in sync with the others.
 * One implementation here instead; MainActivity, QuickPlayActivity, and
 * DiagnosticsActivity all extend this rather than Activity() directly.
 */
abstract class NavActivity : Activity() {

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "YTM Launch")
        menu.add(0, 2, 1, "YTM Old")
        menu.add(0, 3, 2, "Diagnostics")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { startActivity(Intent(this, QuickPlayActivity::class.java)); true }
        2 -> { startActivity(Intent(this, MainActivity::class.java)); true }
        3 -> { startActivity(Intent(this, DiagnosticsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
