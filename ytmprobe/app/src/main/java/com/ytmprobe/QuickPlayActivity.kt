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
 * "YTM Launch (v1)" — retired in favor of LaunchActivity ("YTM Launch"),
 * which rebuilt the same screen's swipe gestures on ItemTouchHelper after
 * this version's manual GestureDetector approach regressed under Android
 * 16's predictive-back gesture and, more fundamentally, never handled a
 * slow controlled drag correctly to begin with (see LaunchActivity's doc
 * comment). Kept startable via the 3-dot menu and adb for comparison/
 * reference, same as "YTM Old"/MainActivity — not a startup screen anymore.
 */
class QuickPlayActivity : NavActivity() {

    private lateinit var appHeader: AppHeader
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YTM Launch (v1)"

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
        Store.saveMixSeed(this, f.title, f.artist, f.videoId)
        Probes.playOrLaunch(this, f.videoId)
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
