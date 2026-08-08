package com.ytmprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Everything that already answered its question for good, manual videoId
 * handling for the cases the automatic pickers can't cover, plus the shared
 * AppHeader (status + mix header, same as the other two screens), and the
 * two cross-cutting concerns shared by every screen: the tracking on/off
 * toggle and the log. Both also live on MainActivity ("YTM Old") — not a
 * "moved to Diagnostics" relationship, all three screens carry them via
 * shared components (AppHeader, NavActivity's 3-dot menu) rather than
 * hand-copied code, after QuickPlayActivity silently missed the header for a
 * whole round of changes when it was still copy-paste-based.
 *
 * Probes B1 and D are confirmed dead ends (FINDINGS.md, E9) and A/E are
 * one-time verifications (no MEDIA_ID, no queue mediaId) — kept here only to
 * re-check after a YTM update, not for routine use.
 */
class DiagnosticsActivity : NavActivity() {

    companion object {
        /** Confirmed working in probe C; used when nothing is stored yet. */
        const val FALLBACK_VIDEO_ID = "ThqRONlaT_I"
        /** Live view is trimmed to this many lines; the file on disk is never trimmed. */
        const val MAX_LOG_LINES = 400
        /** Force a full rebuild (to actually drop old lines) at most this often. */
        const val REBUILD_INTERVAL = 100
    }

    private lateinit var videoIdField: EditText
    private lateinit var appHeader: AppHeader

    private lateinit var trackBtn: Button
    private lateinit var trackState: TextView

    private lateinit var page: LinearLayout
    private lateinit var controlsScroll: ScrollView
    private lateinit var logSection: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logToggleBtn: Button
    private var logExpanded = false

    private val logLines = ArrayDeque<String>()
    private var appendsSinceRebuild = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostics"

        videoIdField = EditText(this).apply {
            hint = "videoId for manual probe C"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Store.loadLast(this@DiagnosticsActivity)?.videoId
                ?.takeIf { it.isNotBlank() } ?: FALLBACK_VIDEO_ID)
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

        appHeader = AppHeader(this)
        root.addView(appHeader.view)

        header("Setup")
        btn("Grant Notification Access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("Status") { Probes.status(this) }

        header("Tracking  (sessions + favorites, polls every 10s)")

        trackState = TextView(this).apply { setPadding(0, 0, 0, 8) }
        root.addView(trackState)

        trackBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (SessionLogger.running) {
                    stopService(Intent(this@DiagnosticsActivity, SessionLogger::class.java))
                } else {
                    startTracking()
                }
                logView.postDelayed({ refreshTrackState() }, 400)
            }
        }
        root.addView(trackBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        header("Raw probes  (settled — see FINDINGS.md)")
        btn("A — metadata fields (needs YTM playing)") { Probes.probeA(this) }
        btn("E — queue contents (needs YTM playing)") { Probes.probeE(this) }
        btn("B1 — MediaBrowser connect (force-stop YTM first)") { Probes.probeB1(this) }
        btn("D — MediaButtonReceiver broadcast (force-stop first)") { Probes.probeD(this) }

        header("Store / cache")
        btn("A+ — capture, resolve, store  (no auto-play)") {
            Probes.probeAPlus(this) { vid ->
                if (vid != null) videoIdField.setText(vid)
            }
        }
        btn("A+ then C — full chain") {
            Probes.probeAPlus(this) { vid ->
                if (vid == null) {
                    ProbeLog.w(this, "  chain stopped: nothing resolved")
                } else {
                    videoIdField.setText(vid)
                    ProbeLog.w(this, "  chaining into probe C in 2s...")
                    videoIdField.postDelayed({ Probes.probeC(this, vid) }, 2000)
                }
            }
        }
        btn("Show stored") { Probes.showStore(this) }
        btn("Show favorites") { Probes.showFavorites(this) }
        btn("Clear resolution cache") { Probes.clearCache(this) }
        btn("Tag genres automatically") { Probes.tagGenres(this) }

        header("Manual override")
        btn("Pick from last candidates") {
            val list = Probes.lastCandidates
            if (list.isEmpty()) {
                ProbeLog.w(this, "no candidates — run A+ first")
            } else {
                val labels = list.mapIndexed { i, c ->
                    "%d  %.0f  %s  %s — %s".format(i, c.score, c.durationText(),
                        c.title.take(34), c.artist.take(18))
                }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Pick the correct track")
                    .setItems(labels) { _, which ->
                        Probes.pickCandidate(this, which)?.let { videoIdField.setText(it) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        val pickField = EditText(this).apply {
            hint = "candidate index to force (0-7)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(pickField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        btn("Use candidate #  (override a bad pick)") {
            val i = pickField.text.toString().trim().toIntOrNull()
            if (i == null) ProbeLog.w(this, "enter a candidate index first")
            else Probes.pickCandidate(this, i)?.let { videoIdField.setText(it) }
        }

        root.addView(videoIdField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Programmatic clipboard access. The long-press paste toolbar is
        // unreliable here, so don't depend on it.
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val half = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

            addView(Button(this@DiagnosticsActivity).apply {
                text = "Paste"
                isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    // Android 10+ blocks clipboard reads unless the app holds
                    // window focus. A keyboard or dialog stealing focus makes
                    // primaryClip come back null with no error.
                    if (!hasWindowFocus()) {
                        ProbeLog.w(this@DiagnosticsActivity,
                            "clipboard: no window focus — tap the app background first")
                        return@setOnClickListener
                    }

                    val hasClip = cm.hasPrimaryClip()
                    val desc = cm.primaryClipDescription
                    ProbeLog.w(this@DiagnosticsActivity,
                        "clipboard: hasClip=$hasClip label=${desc?.label} " +
                                "items=${cm.primaryClip?.itemCount ?: 0}")

                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@DiagnosticsActivity)
                        ?.toString()?.trim()
                    if (t.isNullOrBlank()) {
                        ProbeLog.w(this@DiagnosticsActivity,
                            "clipboard empty or unreadable (Android 10+ restricts " +
                                    "reads to the focused app)")
                    } else {
                        // Accept a bare id or a full watch URL.
                        val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
                            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
                            ?: Regex("^[A-Za-z0-9_-]{11}$").find(t)?.value
                            ?: t
                        videoIdField.setText(id)
                        ProbeLog.w(this@DiagnosticsActivity, "pasted videoId: $id")
                    }
                }
            }, half)

            addView(Button(this@DiagnosticsActivity).apply {
                text = "Use stored"
                isAllCaps = false
                setOnClickListener {
                    val v = Store.loadLast(this@DiagnosticsActivity)?.videoId
                    if (v.isNullOrBlank()) ProbeLog.w(this@DiagnosticsActivity, "no stored videoId")
                    else { videoIdField.setText(v); ProbeLog.w(this@DiagnosticsActivity, "using stored: $v") }
                }
            }, half)

            addView(Button(this@DiagnosticsActivity).apply {
                text = "Copy"
                isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText("videoId", videoIdField.text.toString().trim()))
                    ProbeLog.w(this@DiagnosticsActivity, "copied to clipboard")
                }
            }, half)

            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        btn("C — playFromUri on live session") {
            Probes.probeC(this, videoIdField.text.toString().trim())
        }

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
            addView(Button(this@DiagnosticsActivity).apply {
                text = "Refresh log"
                isAllCaps = false
                setOnClickListener { loadLogFromFile() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@DiagnosticsActivity).apply {
                text = "Clear log"
                isAllCaps = false
                setOnClickListener {
                    ProbeLog.clear(this@DiagnosticsActivity)
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
        // controls pane is scrolled.
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

        if (!SessionLogger.running && Probes.hasNotificationAccess(this)) startTracking()
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
     * the TextView each time. Old lines are only actually dropped from the
     * view every REBUILD_INTERVAL lines, not on every single append, to keep
     * the common case cheap.
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
    // MainActivity's own copies of this UI — registering in onResume/clearing
    // in onPause (not onCreate/onDestroy) ensures whichever screen is
    // actually in front is the one getting live updates, instead of the two
    // screens fighting over a stale registration.
    override fun onResume() {
        super.onResume()
        if (videoIdField.text.isNullOrBlank()) {
            videoIdField.setText(Store.loadLast(this)?.videoId
                ?.takeIf { it.isNotBlank() } ?: FALLBACK_VIDEO_ID)
        }
        ProbeLog.setListener { line -> runOnUiThread { appendLogLine(line) } }
        SessionLogger.onStateChange = { runOnUiThread { refreshTrackState() } }
        loadLogFromFile()
        refreshTrackState()
        appHeader.start()
    }

    override fun onPause() {
        appHeader.stop()
        ProbeLog.setListener(null)
        SessionLogger.onStateChange = null
        super.onPause()
    }
}
