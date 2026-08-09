package com.ytmprobe

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Base class for the 3-dot navigation menu shared by every screen
 * (YTM Launch / YTM Old / Diagnostics). Previously copy-pasted identically
 * into all three Activities — that kind of duplication is exactly how
 * QuickPlayActivity ended up missing the status/mix header for a whole round
 * of changes: nothing forced every screen to stay in sync with the others.
 * One implementation here instead; MainActivity, QuickPlayActivity, and
 * DiagnosticsActivity all extend this rather than Activity() directly.
 *
 * Also owns edge-to-edge inset handling: bumping targetSdk to 36 (for
 * ResumeCarAppService's intentMatchingFlags — see its manifest entry) makes
 * edge-to-edge mandatory on Android 15+; apps can no longer opt out, and
 * content that doesn't account for system bar insets draws underneath the
 * status bar / gesture nav bar (confirmed — the first favorites row was
 * rendering half-hidden behind the title bar). onContentChanged() runs right
 * after every subclass's setContentView(), so this pads whatever the actual
 * top-level content view turns out to be, with zero changes needed per
 * screen — same "fix it once in the shared base" pattern as the nav menu.
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
        menu.add(0, 1, 0, "YTM Launch")
        menu.add(0, 2, 1, "YTM Old")
        menu.add(0, 3, 2, "Diagnostics")
        menu.add(0, 4, 3, "YTM Launch (v1)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { startActivity(Intent(this, LaunchActivity::class.java)); true }
        2 -> { startActivity(Intent(this, MainActivity::class.java)); true }
        3 -> { startActivity(Intent(this, DiagnosticsActivity::class.java)); true }
        4 -> { startActivity(Intent(this, QuickPlayActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
