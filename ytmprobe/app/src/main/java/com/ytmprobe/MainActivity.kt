package com.ytmprobe

import android.app.Activity
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

class MainActivity : Activity() {

    companion object {
        /** Confirmed working in probe C; used when nothing is stored yet. */
        const val FALLBACK_VIDEO_ID = "ThqRONlaT_I"
    }


    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var trackBtn: Button
    private lateinit var trackState: TextView
    private lateinit var videoIdField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        videoIdField = EditText(this).apply {
            hint = "videoId for probe C"
            inputType = InputType.TYPE_CLASS_TEXT
            // Prefer the last resolved id; fall back to a known-good one so
            // probe C is always runnable without typing.
            setText(Store.loadLast(this@MainActivity)?.videoId
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

        header("Setup")
        btn("1. Grant Notification Access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("Status") { Probes.status(this) }

        header("Probes")
        btn("A+ — capture, resolve, store  (populates videoId)") {
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
                    logView.postDelayed({ Probes.probeC(this, vid) }, 2000)
                }
            }
        }
        btn("Show stored") { Probes.showStore(this) }

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
        btn("Clear resolution cache") { Probes.clearCache(this) }
        btn("Pick from last candidates (list)") {
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
        btn("A — metadata fields (needs YTM playing)") { Probes.probeA(this) }
        btn("E — queue contents (needs YTM playing)") { Probes.probeE(this) }
        btn("B1 — MediaBrowser connect (force-stop YTM first)") { Probes.probeB1(this) }
        btn("D — MediaButtonReceiver broadcast (force-stop first)") { Probes.probeD(this) }

        root.addView(videoIdField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Programmatic clipboard access. The long-press paste toolbar is
        // unreliable here, so don't depend on it.
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val half = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

            addView(Button(this@MainActivity).apply {
                text = "Paste"
                isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    // Android 10+ blocks clipboard reads unless the app holds
                    // window focus. A keyboard or dialog stealing focus makes
                    // primaryClip come back null with no error.
                    if (!hasWindowFocus()) {
                        ProbeLog.w(this@MainActivity,
                            "clipboard: no window focus — tap the app background first")
                        return@setOnClickListener
                    }

                    val hasClip = cm.hasPrimaryClip()
                    val desc = cm.primaryClipDescription
                    ProbeLog.w(this@MainActivity,
                        "clipboard: hasClip=$hasClip label=${desc?.label} " +
                                "items=${cm.primaryClip?.itemCount ?: 0}")

                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)
                        ?.toString()?.trim()
                    if (t.isNullOrBlank()) {
                        ProbeLog.w(this@MainActivity,
                            "clipboard empty or unreadable (Android 10+ restricts " +
                                    "reads to the focused app)")
                    } else {
                        // Accept a bare id or a full watch URL.
                        val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
                            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
                            ?: Regex("^[A-Za-z0-9_-]{11}$").find(t)?.value
                            ?: t
                        videoIdField.setText(id)
                        ProbeLog.w(this@MainActivity, "pasted videoId: $id")
                    }
                }
            }, half)

            addView(Button(this@MainActivity).apply {
                text = "Use stored"
                isAllCaps = false
                setOnClickListener {
                    val v = Store.loadLast(this@MainActivity)?.videoId
                    if (v.isNullOrBlank()) ProbeLog.w(this@MainActivity, "no stored videoId")
                    else { videoIdField.setText(v); ProbeLog.w(this@MainActivity, "using stored: $v") }
                }
            }, half)

            addView(Button(this@MainActivity).apply {
                text = "Copy"
                isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText("videoId", videoIdField.text.toString().trim()))
                    ProbeLog.w(this@MainActivity, "copied to clipboard")
                }
            }, half)

            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        btn("C — playFromUri on live session") {
            Probes.probeC(this, videoIdField.text.toString().trim())
        }

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

        header("Favorites")
        btn("Pick a favorite  (10 random, refreshable)") { showFavoritePicker() }
        btn("Resolve favorites") { Probes.resolveFavorites(this) }
        btn("Show favorites") { Probes.showFavorites(this) }

        header("Log")
        btn("Refresh") { showLog() }
        btn("Clear") { ProbeLog.clear(this); showLog() }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(8, 8, 8, 8)
        }
        TextView(this).apply {
            text = "adb pull /sdcard/Android/data/com.ytmprobe/files/probe.log"
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
            root.addView(this)
        }

        // Two independent scroll regions side by side vertically. Previously
        // the log ScrollView sat INSIDE an outer ScrollView with weight=1,
        // which is meaningless (a ScrollView gives children unbounded height)
        // and broke touch handling — including the paste toolbar.
        val controls = ScrollView(this).apply { addView(root) }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 3f })
            addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 2f })
        }
        setContentView(page)

        ProbeLog.setListener { runOnUiThread { showLog() } }
        SessionLogger.onStateChange = { runOnUiThread { refreshTrackState() } }

        // Tracking starts with the app; the button exists to stop it.
        if (!SessionLogger.running && Probes.hasNotificationAccess(this)) startTracking()
        refreshTrackState()

        // Fill any blank favorite IDs quietly in the background.
        Probes.resolveFavorites(this, quiet = true)

        showLog()
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

    /**
     * 10 random favorites, title — artist only. Resolves the visible set
     * first and drops anything that fails, so no unplayable row is shown.
     */
    private fun showFavoritePicker() {
        if (Favorites.count(this) == 0) {
            ProbeLog.w(this, "no favorites yet — like a track while tracking is active")
            return
        }
        ProbeLog.w(this, "favorites picker: resolving unresolved entries...")
        Probes.resolveFavorites(this, quiet = true) { _, _ ->
            val sample = Favorites.randomSample(this, 10).filter { it.videoId.isNotBlank() }
            if (sample.isEmpty()) {
                ProbeLog.w(this, "no favorites could be resolved — check connectivity")
                return@resolveFavorites
            }
            val labels = sample.map { it.label() }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Pick a favorite")
                .setItems(labels) { _, which ->
                    val f = sample[which]
                    videoIdField.setText(f.videoId)
                    ProbeLog.w(this, "selected favorite: ${f.label()}  ${f.videoId}")
                }
                .setNeutralButton("Refresh") { d, _ -> d.dismiss(); showFavoritePicker() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrackState()
        if (videoIdField.text.isNullOrBlank()) {
            videoIdField.setText(Store.loadLast(this)?.videoId
                ?.takeIf { it.isNotBlank() } ?: FALLBACK_VIDEO_ID)
        }
    }

    private fun showLog() {
        logView.text = ProbeLog.read(this)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        ProbeLog.setListener(null)
        SessionLogger.onStateChange = null
        super.onDestroy()
    }
}
