package com.ytmprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "YTM Launch" — the app's actual startup screen (see the manifest's
 * LAUNCHER intent-filter, now on this activity rather than MainActivity).
 * Optimised purely for song selection: one row per genre (a random resolved
 * favorite tagged with it — genres with nothing resolved/tagged are
 * skipped), tap to play, swipe left to remove/swipe right to re-tag genre
 * (via FavoriteGestures, shared with MainActivity's rows), and a Refresh
 * button. No status line, no tracking controls, no log — those live on the
 * full page ("YTM Old"/MainActivity) and in DiagnosticsActivity.
 *
 * The 3-dot menu is the platform Options Menu, not a custom Toolbar —
 * Theme.Material already renders an ActionBar, and an unclaimed menu item
 * goes to the overflow icon by default, so this needs no XML resource,
 * consistent with how the rest of the app avoids layout/menu resource files.
 * It's a plain page switcher (YTM Launch / YTM Old / Diagnostics) — it does
 * not change the app's startup default, which is fixed to this screen.
 */
class QuickPlayActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var warningView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YTM Launch"

        // This is the app's default entry point now — tracking (passive
        // favorites capture) must actually be running even if the user never
        // visits YTM Old, or new favorites silently never get captured.
        if (!SessionLogger.running && Probes.hasNotificationAccess(this)) startTracking()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        warningView = TextView(this).apply { setPadding(0, 0, 0, 8) }
        root.addView(warningView)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Refresh"
            isAllCaps = false
            setOnClickListener { reload() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })

        // resolveFavorites's callback can fire synchronously (when there's
        // nothing left to resolve) rather than on the background thread —
        // must not run before listContainer exists, hence after reload()
        // rather than before it, mirroring MainActivity's own ordering.
        reload()
        Probes.resolveFavorites(this, quiet = true) { _, _ -> reload() }
    }

    private fun startTracking() {
        val i = Intent(this, SessionLogger::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onResume() {
        super.onResume()
        // Without this, tapping a song silently does nothing and nothing on
        // this minimal screen would explain why — the only feedback would
        // otherwise land in a log this screen doesn't show.
        warningView.text = if (!Probes.hasNotificationAccess(this))
            "⚠ Notification access needed — see Diagnostics" else ""
    }

    private fun reload() {
        listContainer.removeAllViews()

        val all = Favorites.all(this).filter { it.videoId.isNotBlank() }
        val picks = Favorites.GENRES.mapNotNull { genre ->
            all.filter { it.genre == genre }.shuffled().firstOrNull()
        }

        if (picks.isEmpty()) {
            TextView(this).apply {
                text = "no tagged, resolved favorites yet — tag genres from YTM Old first"
                setPadding(0, 16, 0, 0)
            }.also { listContainer.addView(it) }
            return
        }

        picks.forEach { f ->
            val titleView = TextView(this).apply {
                text = f.title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E3ECFF"))
            }
            val subView = TextView(this).apply {
                text = "${f.artist}  ·  ${f.genre}"
                textSize = 12f
                setTextColor(Color.parseColor("#8FA6D6"))
                setPadding(0, 2, 0, 0)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E2A47"))
                setPadding(28, 22, 28, 22)
                addView(titleView)
                addView(subView)
            }
            FavoriteGestures.attach(this, row, f, onPlay = { play(f) }, onChanged = { reload() })

            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            params.bottomMargin = 8
            listContainer.addView(row, params)
        }
    }

    private fun play(f: Favorites.Fav) {
        ProbeLog.w(this, "quick play: ${f.label()}  ${f.videoId}")
        Probes.probeC(this, f.videoId)
    }

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
