package com.ytmlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
 * Setup, cross-cutting controls (tracking toggle, log), and the recovery
 * paths for what LaunchActivity does automatically — plus favorites backup.
 *
 * Trimmed from ytmprobe's DiagnosticsActivity: dropped the raw diagnostic
 * probes (A/B1/D/E — dead ends or one-time-answered questions, see
 * ytmprobe/FINDINGS.md), "capture/resolve/store" (probe A+), and the
 * candidate-override system that was downstream of probe A+ (without it,
 * "Pick from last candidates" would just say "no candidates" forever).
 * Kept: everything that's actually load-bearing or a genuine recovery path.
 * "Grant Notification Access" in particular isn't diagnostic clutter — it's
 * the only place to fix a real first-run failure mode
 * (MediaSessionManager.getActiveSessions() throws without it).
 */
class SettingsActivity : NavActivity() {

    companion object {
        const val MAX_LOG_LINES = 400
        const val REBUILD_INTERVAL = 100
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
    }

    private lateinit var appHeader: AppHeader
    private lateinit var debugVideoIdField: EditText

    private lateinit var trackBtn: Button
    private lateinit var trackState: TextView

    private lateinit var refreshModelBtn: Button
    private lateinit var notificationsEnabledBtn: Button

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
        title = "Settings"
        // Explicit back arrow to return to normal mode (LaunchActivity) —
        // real user feedback that Settings had no visible way back besides
        // the system Back gesture/button. android.R.id.home is handled in
        // NavActivity.onOptionsItemSelected, shared with LaunchActivity's
        // Settings action.
        actionBar?.setDisplayHomeAsUpEnabled(true)

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
                    stopService(Intent(this@SettingsActivity, SessionLogger::class.java))
                } else {
                    startTracking()
                }
                logView.postDelayed({ refreshTrackState() }, 400)
            }
        }
        root.addView(trackBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        header("Android Auto")
        // Same toggle idiom as trackBtn above: the button's own label is
        // the current state, tapping it cycles to the next one. Two
        // designs for AutoMediaService's Refresh tile, meant to be tried
        // against each other on real hardware without a rebuild — see
        // Store.RefreshModel's doc for what each one actually does.
        refreshModelBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                val next = when (Store.getRefreshModel(this@SettingsActivity)) {
                    Store.RefreshModel.LEGACY -> Store.RefreshModel.IN_PLACE
                    Store.RefreshModel.IN_PLACE -> Store.RefreshModel.LEGACY
                }
                Store.setRefreshModel(this@SettingsActivity, next)
                refreshRefreshModelState()
            }
        }
        root.addView(refreshModelBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        header("Favorites")
        btn("Resolve favorites now") {
            Probes.resolveFavorites(this)
        }
        btn("Tag genres automatically") {
            Probes.tagGenres(this)
        }
        btn("Show favorites") { Probes.showFavorites(this) }

        header("Store / cache")
        btn("Show stored") { Probes.showStore(this) }
        btn("Clear resolution cache") { Probes.clearCache(this) }

        header("Lock-screen favorites notification")
        // Same toggle idiom as trackBtn/refreshModelBtn above. Off by
        // default (Store.isFavoritesNotificationsEnabled) — gates only the
        // *automatic* posting (SessionLogger on tracking start,
        // PlayFavoriteActivity's re-post after a row is tapped); toggling
        // this immediately shows/clears too, so the on-screen state and the
        // actual notifications never disagree.
        notificationsEnabledBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                val enabled = !Store.isFavoritesNotificationsEnabled(this@SettingsActivity)
                Store.setFavoritesNotificationsEnabled(this@SettingsActivity, enabled)
                if (enabled) FavoritesNotifier.show(this@SettingsActivity) else FavoritesNotifier.cancelAll(this@SettingsActivity)
                refreshNotificationsEnabledState()
            }
        }
        root.addView(notificationsEnabledBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        btn("Post now (one per genre)") { FavoritesNotifier.show(this) }
        btn("Clear") { FavoritesNotifier.cancelAll(this) }

        header("Backup")
        btn("Export favorites") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "ytm-launcher-favorites.json")
            }
            startActivityForResult(i, REQ_EXPORT)
        }
        btn("Import favorites") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT)
        }

        header("Debug")
        debugVideoIdField = EditText(this).apply {
            hint = "videoId to test playFromUri against"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Store.loadLast(this@SettingsActivity)?.videoId ?: "")
        }
        root.addView(debugVideoIdField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        btn("Test playFromUri on live session") {
            Probes.debugCommandLiveSession(this, debugVideoIdField.text.toString().trim())
        }

        // --- log section: see DiagnosticsActivity's original note on why
        // two independent ScrollViews rather than one nested in the other. ---

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
            addView(Button(this@SettingsActivity).apply {
                text = "Refresh log"
                isAllCaps = false
                setOnClickListener { loadLogFromFile() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@SettingsActivity).apply {
                text = "Clear log"
                isAllCaps = false
                setOnClickListener {
                    ProbeLog.clear(this@SettingsActivity)
                    logLines.clear()
                    logView.text = ""
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val logHint = TextView(this).apply {
            text = "adb pull /sdcard/Android/data/com.ytmlauncher/files/probe.log"
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

        controlsScroll = ScrollView(this).apply { addView(root) }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return

        when (requestCode) {
            REQ_EXPORT -> {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(Backup.export(this).toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess { ProbeLog.w(this, "backup: exported to $uri") }
                    .onFailure { ProbeLog.w(this, "backup: export failed: $it") }
            }
            REQ_IMPORT -> {
                runCatching {
                    val json = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("could not open $uri")
                    Backup.import(this, json)
                }.onSuccess { count ->
                    ProbeLog.w(this, "backup: imported $count key(s) from $uri")
                }.onFailure { ProbeLog.w(this, "backup: import failed: $it") }
            }
        }
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

    private fun refreshRefreshModelState() {
        refreshModelBtn.text = when (Store.getRefreshModel(this)) {
            Store.RefreshModel.LEGACY -> "Refresh style: Legacy  (tap to switch)"
            Store.RefreshModel.IN_PLACE -> "Refresh style: InPlace  (tap to switch)"
        }
    }

    private fun refreshNotificationsEnabledState() {
        notificationsEnabledBtn.text = if (Store.isFavoritesNotificationsEnabled(this))
            "Notifications: On  (tap to disable)"
        else
            "Notifications: Off  (tap to enable)"
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

    private fun scrollLogToBottom() {
        if (!logExpanded) return
        logScroll.post { logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }
    }

    override fun onResume() {
        super.onResume()
        ProbeLog.setListener { line -> runOnUiThread { appendLogLine(line) } }
        SessionLogger.onStateChange = { runOnUiThread { refreshTrackState() } }
        loadLogFromFile()
        refreshTrackState()
        refreshRefreshModelState()
        refreshNotificationsEnabledState()
        appHeader.start()
    }

    override fun onPause() {
        appHeader.stop()
        ProbeLog.setListener(null)
        SessionLogger.onStateChange = null
        super.onPause()
    }
}
