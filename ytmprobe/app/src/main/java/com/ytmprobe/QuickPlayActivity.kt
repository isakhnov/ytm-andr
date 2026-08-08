package com.ytmprobe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "YTM Launch" — the app's actual startup screen (see the manifest's
 * LAUNCHER intent-filter, now on this activity rather than MainActivity).
 * Optimised purely for song selection: the shared AppHeader (status + mix
 * header — same as YTM Old, via the shared component, not hand-copied), one
 * row per genre (a random resolved favorite tagged with it — genres with
 * nothing resolved/tagged are skipped), tap to play, swipe left to
 * remove/swipe right to re-tag genre (via FavoriteGestures, shared with
 * MainActivity's rows), and a Refresh button. No tracking controls, no log —
 * those live on the full page ("YTM Old"/MainActivity) and in
 * DiagnosticsActivity. The 3-dot nav menu comes from NavActivity, shared by
 * all three screens.
 */
class QuickPlayActivity : NavActivity() {

    private lateinit var appHeader: AppHeader
    private lateinit var listContainer: LinearLayout

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

        appHeader = AppHeader(this)
        root.addView(appHeader.view)

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
        appHeader.setMixHeader(f.title, f.artist)
        Probes.probeC(this, f.videoId)
    }

    override fun onResume() {
        super.onResume()
        appHeader.start()
    }

    override fun onPause() {
        appHeader.stop()
        super.onPause()
    }
}
