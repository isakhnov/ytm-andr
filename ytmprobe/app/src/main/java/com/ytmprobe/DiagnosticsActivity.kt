package com.ytmprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * Everything that already answered its question for good, plus manual
 * videoId handling for the cases the automatic pickers can't cover.
 *
 * Probes B1 and D are confirmed dead ends (FINDINGS.md, E9) and A/E are
 * one-time verifications (no MEDIA_ID, no queue mediaId) — kept here only to
 * re-check after a YTM update, not for routine use. See MainActivity for the
 * day-to-day surface.
 */
class DiagnosticsActivity : Activity() {

    companion object {
        /** Confirmed working in probe C; used when nothing is stored yet. */
        const val FALLBACK_VIDEO_ID = "ThqRONlaT_I"
    }

    private lateinit var videoIdField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        header("Setup")
        btn("Grant Notification Access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("Status") { Probes.status(this) }

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

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (videoIdField.text.isNullOrBlank()) {
            videoIdField.setText(Store.loadLast(this)?.videoId
                ?.takeIf { it.isNotBlank() } ?: FALLBACK_VIDEO_ID)
        }
    }
}
