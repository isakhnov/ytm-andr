package com.ytmprobe

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Status line + mix header, shared across every screen that shows "what's
 * playing" — built once, mounted identically everywhere, so no screen can
 * silently end up missing it the way QuickPlayActivity did for a whole round
 * of changes (it was hand-copied into MainActivity only).
 *
 *  - Status line: "Playing: <title>" (or a warning when Notification Access
 *    is missing), refreshed on a poll while the screen is visible — nothing
 *    else pushes an update when the track changes for another reason (YTM
 *    auto-advancing, a manual skip in YTM itself).
 *  - Mix header: a one-time static read of whatever YTM is playing when the
 *    header is built, then frozen. dumpsys confirmed YTM's own queueTitle
 *    field never carries a stable "session name" — it drifts with whatever
 *    track is currently playing ("Up next" regardless of context) — so this
 *    is self-authored from the known selection via setMixHeader(), not
 *    re-derived from any live YTM field.
 */
class AppHeader(private val activity: Activity) {

    companion object {
        const val STATUS_POLL_MS = 3000L
    }

    val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private val statusView = TextView(activity).apply { setPadding(0, 0, 0, 8) }
    private val mixHeaderView = TextView(activity).apply {
        text = "YTM original selection"
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    init {
        view.addView(statusView)
        view.addView(mixHeaderView)

        val c0 = Probes.ytmController(activity)
        val t0 = c0?.metadata?.description?.title?.toString()
        val a0 = c0?.metadata?.description?.subtitle?.toString()
        mixHeaderView.text = when {
            t0.isNullOrBlank() -> "YTM original selection"
            a0.isNullOrBlank() -> "Mix: $t0"
            else -> "Mix: $t0 by $a0"
        }
    }

    /** Call from the hosting Activity's onResume. */
    fun start() {
        refreshStatus()
        handler.post(poll)
    }

    /** Call from the hosting Activity's onPause. */
    fun stop() {
        handler.removeCallbacks(poll)
    }

    fun refreshStatus() {
        val c = Probes.ytmController(activity)
        val sb = StringBuilder()
        if (!Probes.hasNotificationAccess(activity)) {
            sb.append("⚠ Notification access needed — see Diagnostics\n")
        }
        sb.append(
            if (c != null) "Playing: ${c.metadata?.description?.title ?: "?"}"
            else "Not playing"
        )
        statusView.text = sb.toString().trim()
    }

    /** Self-authored, not read from YTM — see class doc. Call right after an explicit play action. */
    fun setMixHeader(title: String, artist: String) {
        mixHeaderView.text = "Mix: $title by $artist"
        handler.postDelayed({ refreshStatus() }, 1500)
    }
}
