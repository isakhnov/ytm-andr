package com.ytmprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "YTM Old" — the original day-to-day surface: a passive status readout, a
 * rehearsal of the Android Auto favorites screen (same 5-random-plus-refresh
 * shape as ResumeCarAppService.FavoritesScreen — tapping a row plays it
 * directly, no videoId to see or type), the tracking toggle, and a
 * collapsible log. DiagnosticsActivity has its own copies of the tracking
 * toggle and log too (they're cross-cutting, not specific to one screen) —
 * this is not a "moved to Diagnostics" relationship, both screens carry them.
 * See QuickPlayActivity ("YTM Launch") for the minimal song-selection-only
 * alternative, which is the app's actual startup screen.
 *
 * Everything that already answered its question for good (probes A/E/B1/D),
 * one-time setup (Grant Notification Access), and manual videoId handling
 * live in DiagnosticsActivity instead of cluttering this screen — see
 * FINDINGS.md for what each of those settled.
 */
class MainActivity : Activity() {

    companion object {
        /** Live view is trimmed to this many lines; the file on disk is never trimmed. */
        const val MAX_LOG_LINES = 400
        /** Force a full rebuild (to actually drop old lines) at most this often. */
        const val REBUILD_INTERVAL = 100
        /** How often the status strip re-checks YTM while the screen is visible. */
        const val STATUS_POLL_MS = 3000L
    }

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The status strip is otherwise only refreshed on explicit triggers
     * (open, resume, after tapping a favorite) — nothing pushes an update
     * when the track changes for any other reason (YTM auto-advancing, a
     * manual skip in YTM itself). This keeps it live while the app is open.
     */
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private lateinit var page: LinearLayout
    private lateinit var controlsScroll: ScrollView
    private lateinit var logSection: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logToggleBtn: Button
    private var logExpanded = false

    private val logLines = ArrayDeque<String>()
    private var appendsSinceRebuild = 0

    private lateinit var statusView: TextView
    private lateinit var mixHeaderView: TextView
    private lateinit var favContainer: LinearLayout
    private lateinit var genreBtn: Button
    private lateinit var trackBtn: Button
    private lateinit var trackState: TextView

    private var favSample: List<Favorites.Fav> = emptyList()

    /** null = no filter, show any genre. */
    private var selectedGenre: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YTM Old"

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        fun header(t: String) = TextView(this).apply {
            text = t
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
            root.addView(this)
        }

        statusView = TextView(this).apply { setPadding(0, 0, 0, 8) }
        root.addView(statusView)

        mixHeaderView = TextView(this).apply {
            text = "YTM original selection"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        root.addView(mixHeaderView)
        favContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(favContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        genreBtn = btn("Refresh: All") {
            // Resolves anything captured since the last refresh (quietly) before
            // reshuffling, so a track liked mid-session shows up immediately
            // instead of waiting for a separate "resolve" step.
            Probes.resolveFavorites(this, quiet = true) { _, _ -> reloadFavorites() }
        }
        genreBtn.setOnLongClickListener { showGenrePicker(); true }

        header("Tracking  (sessions + favorites, polls every 10s)")

        trackState = TextView(this).apply { setPadding(0, 0, 0, 8) }
        root.addView(trackState)

        trackBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (SessionLogger.running) {
                    stopService(Intent(this@MainActivity, SessionLogger::class.java))
                } else {
                    startTracking()
                }
                logView.postDelayed({ refreshTrackState() }, 400)
            }
        }
        root.addView(trackBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- log section: built separately from `root` so it can sit in its
        // own scroll region below the controls (see the note further down on
        // why two ScrollViews, not one nested inside the other). ---

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#C8C8C8"))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(8, 8, 8, 8)
        }

        val logControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Refresh log"
                isAllCaps = false
                setOnClickListener { loadLogFromFile() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Clear log"
                isAllCaps = false
                setOnClickListener {
                    ProbeLog.clear(this@MainActivity)
                    logLines.clear()
                    logView.text = ""
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val logHint = TextView(this).apply {
            text = "adb pull /sdcard/Android/data/com.ytmprobe/files/probe.log"
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        logSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(logControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })
            addView(logHint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        // Two independent scroll regions side by side vertically. Previously
        // the log ScrollView sat INSIDE an outer ScrollView with weight=1,
        // which is meaningless (a ScrollView gives children unbounded height)
        // and broke touch handling — including the paste toolbar.
        controlsScroll = ScrollView(this).apply { addView(root) }

        // A fixed bar, NOT inside controlsScroll's scrollable content — the
        // toggle must stay reachable in one tap regardless of how far the
        // controls pane is scrolled or how tall the favorites list is.
        logToggleBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                logExpanded = !logExpanded
                applyLogVisibility()
            }
        }
        val logToggleBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(logToggleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controlsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })
            addView(logToggleBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(logSection, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 0f })
        }
        setContentView(page)
        applyLogVisibility()

        // Tracking starts with the app; the button exists to stop it.
        if (!SessionLogger.running && Probes.hasNotificationAccess(this)) startTracking()
        refreshTrackState()
        refreshStatus()

        // One-time static read of whatever YTM is already playing, if
        // anything — not live, never re-read after this; playFavorite()
        // overwrites it the moment an explicit selection is made.
        run {
            val c0 = Probes.ytmController(this)
            val t0 = c0?.metadata?.description?.title?.toString()
            val a0 = c0?.metadata?.description?.subtitle?.toString()
            mixHeaderView.text = when {
                t0.isNullOrBlank() -> "YTM original selection"
                a0.isNullOrBlank() -> "Mix: $t0"
                else -> "Mix: $t0 by $a0"
            }
        }

        // Fill any blank favorite IDs quietly in the background, then show
        // a sample — same shape the car will show once this app connects.
        reloadFavorites()
        Probes.resolveFavorites(this, quiet = true) { _, _ -> reloadFavorites() }
    }

    private fun startTracking() {
        val i = Intent(this, SessionLogger::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun refreshTrackState() {
        val on = SessionLogger.running
        trackBtn.text = if (on) "Stop tracking" else "Start tracking"
        trackState.text = if (on)
            "● tracking active   —   ${Favorites.count(this)} favorite(s), " +
                    "${Favorites.resolvedCount(this)} playable"
        else
            "○ tracking stopped   —   ${Favorites.count(this)} favorite(s)"
    }

    /** YTM session + notification access, at a glance — replaces the old Status button. */
    private fun refreshStatus() {
        val c = Probes.ytmController(this)

        val sb = StringBuilder()
        if (!Probes.hasNotificationAccess(this)) {
            sb.append("⚠ Notification access needed — see Diagnostics\n")
        }
        sb.append(
            if (c != null) "Playing: ${c.metadata?.description?.title ?: "?"}"
            else "Not playing"
        )
        statusView.text = sb.toString().trim()
    }

    /**
     * Same shape as ResumeCarAppService.FavoritesScreen: up to
     * FavoritesScreen.ROWS random *resolved* favorites, tap to play instantly.
     * Restricted to selectedGenre when one is chosen (see showGenrePicker).
     */
    private fun reloadFavorites() {
        favSample = Favorites.all(this)
            .filter { it.videoId.isNotBlank() }
            .filter { selectedGenre == null || it.genre == selectedGenre }
            .shuffled()
            .take(FavoritesScreen.ROWS)
        renderFavorites()
    }

    private fun renderFavorites() {
        favContainer.removeAllViews()
        if (favSample.isEmpty()) {
            TextView(this).apply {
                text = when {
                    Favorites.count(this@MainActivity) == 0 ->
                        "no favorites yet — thumbs-up a track while tracking is active"
                    selectedGenre != null -> "no favorites tagged \"$selectedGenre\" yet"
                    else -> "no favorites resolved yet — tap Refresh"
                }
                setPadding(0, 0, 0, 8)
            }.also { favContainer.addView(it) }
            return
        }
        favSample.forEach { f ->
            // Title and artist as two visually distinct lines, not one string
            // with a dash — f.label() stays in use for log messages/dialog
            // titles, just not for on-screen row rendering.
            val titleView = TextView(this).apply {
                text = f.title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E3ECFF"))
            }
            val artistView = TextView(this).apply {
                text = f.artist
                textSize = 12f
                setTextColor(Color.parseColor("#8FA6D6"))
                setPadding(0, 2, 0, 0)
            }

            // Deliberately not styled like an action button (Refresh, Stop
            // tracking...) — this is a list of songs, not a control.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E2A47"))
                setPadding(24, 16, 24, 16)
                addView(titleView)
                addView(artistView)
            }
            FavoriteGestures.attach(this, row, f, onPlay = { playFavorite(f) }, onChanged = { reloadFavorites() })

            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            params.bottomMargin = 6
            favContainer.addView(row, params)
        }
    }

    /**
     * Long-press on Refresh. Filters the pool used by Refresh/reloadFavorites
     * — "All" clears the filter. The choice sticks (reflected in the button
     * label) until changed again. Current selection is pre-checked.
     */
    private fun showGenrePicker() {
        val labels = (listOf("All") + Favorites.GENRES).toTypedArray()
        val current = selectedGenre?.let { Favorites.GENRES.indexOf(it) + 1 } ?: 0
        var picked = current
        android.app.AlertDialog.Builder(this)
            .setTitle("Show favorites in genre")
            .setSingleChoiceItems(labels, current) { _, which -> picked = which }
            .setPositiveButton("OK") { d, _ ->
                selectedGenre = if (picked == 0) null else labels[picked]
                genreBtn.text = "Refresh: ${selectedGenre ?: "All"}"
                reloadFavorites()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playFavorite(f: Favorites.Fav) {
        ProbeLog.w(this, "playing favorite: ${f.label()}  ${f.videoId}")
        // Self-authored, not read from YTM: dumpsys confirmed queueTitle never
        // carries the mix name ("Adieu Mix" showed in YTM's own UI while
        // queueTitle stayed "Up next") — so there's nothing reliable to pull.
        // We already know exactly what we selected; use that directly.
        mixHeaderView.text = "Mix: ${f.title} by ${f.artist}"
        Probes.probeC(this, f.videoId)
        logView.postDelayed({ refreshStatus() }, 1500)
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

    // -------------------------------------------------------------- log

    private fun applyLogVisibility() {
        logSection.visibility = if (logExpanded) View.VISIBLE else View.GONE
        (controlsScroll.layoutParams as LinearLayout.LayoutParams).weight = if (logExpanded) 3f else 1f
        (logSection.layoutParams as LinearLayout.LayoutParams).weight = if (logExpanded) 2f else 0f
        logToggleBtn.text = if (logExpanded) "▾ Hide log" else "▸ Show log"
        page.requestLayout()
        if (logExpanded) scrollLogToBottom()
    }

    /** Full resync from the on-disk file — used for the initial load and manual "Refresh log". */
    private fun loadLogFromFile() {
        logLines.clear()
        ProbeLog.read(this).lineSequence().forEach { logLines.addLast(it) }
        trimLogLines()
        renderLogLines()
    }

    private fun trimLogLines() {
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
    }

    private fun renderLogLines() {
        logView.text = logLines.joinToString("\n")
        appendsSinceRebuild = 0
        scrollLogToBottom()
    }

    /**
     * Called on every single ProbeLog.w() in the app, which can fire in fast
     * bursts (e.g. resolving several favorites at once). Appending the one
     * new line is far cheaper than re-reading the whole file and resetting
     * the TextView each time — that repeated full reset was why the log
     * previously looked "stuck" scrolling during a burst. Old lines are only
     * actually dropped from the view every REBUILD_INTERVAL lines, not on
     * every single append, to keep the common case cheap.
     */
    private fun appendLogLine(line: String) {
        logLines.addLast(line)
        appendsSinceRebuild++
        if (logLines.size > MAX_LOG_LINES || appendsSinceRebuild >= REBUILD_INTERVAL) {
            trimLogLines()
            renderLogLines()
        } else {
            logView.append(line + "\n")
            scrollLogToBottom()
        }
    }

    /**
     * A single post-then-scroll can run before the TextView has finished
     * re-measuring the new (taller) content, landing short of the real
     * bottom. Nesting the post ensures the layout pass triggered by the text
     * change has actually completed first.
     */
    private fun scrollLogToBottom() {
        if (!logExpanded) return
        logScroll.post { logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }
    }

    // ProbeLog/SessionLogger callbacks are single global slots shared with
    // DiagnosticsActivity's own copies of this UI — registering in
    // onResume/clearing in onPause (not onCreate/onDestroy) ensures whichever
    // screen is actually in front is the one getting live updates, instead of
    // the two screens fighting over a stale registration.
    override fun onResume() {
        super.onResume()
        ProbeLog.setListener { line -> runOnUiThread { appendLogLine(line) } }
        SessionLogger.onStateChange = { runOnUiThread { refreshTrackState(); refreshStatus() } }
        loadLogFromFile()
        refreshTrackState()
        refreshStatus()
        statusHandler.post(statusPoll)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusPoll)
        ProbeLog.setListener(null)
        SessionLogger.onStateChange = null
        super.onPause()
    }
}
