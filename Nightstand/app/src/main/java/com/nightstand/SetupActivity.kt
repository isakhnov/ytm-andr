package com.nightstand

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Launcher screen: one row per permission with its live status and a button to
 * the right system page, plus a preview button and the watcher's debug log.
 * Built in code — a prototype doesn't need layout XML.
 */
class SetupActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var logView: TextView
    private val main = Handler(Looper.getMainLooper())
    private val logTick = object : Runnable {
        override fun run() { logView.text = StandByState.logText(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(0xFF9A9AA0.toInt())
            setPadding(0, pad, 0, 0)
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(list); addView(logView)
            })
        })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.enabled(this)) WatcherService.start(this)
        render()
        main.post(logTick)
    }

    override fun onPause() {
        main.removeCallbacks(logTick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = render()

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "Nightstand"; textSize = 28f; setTextColor(Color.WHITE)
        })
        list.addView(TextView(this).apply {
            text = "Charging + sideways + still + locked → clock, calendar and notifications. " +
                "Tap to brighten at night, double-tap to unlock."
            setTextColor(0xFF9A9AA0.toInt()); setPadding(0, 8, 0, 24)
        })

        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)

        row("Notifications", "Needed for the watcher and the launch alert",
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }

        row("Full-screen alerts", "Lets Nightstand appear over the lock screen",
            Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }

        row("Display over other apps", "Lets the watcher open Nightstand directly",
            Settings.canDrawOverlays(this)
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        row("Battery: unrestricted", "Stops One UI from killing the watcher overnight",
            pm.isIgnoringBatteryOptimizations(packageName)
        ) {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        row("Calendar", "Shows upcoming events", CalendarSource.isGranted(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
        }

        row("Notification access", "Shows your notifications (optional)", NotificationFeed.isGranted(this)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        toggle("Watcher on", Prefs.enabled(this)) { on ->
            Prefs.setEnabled(this, on)
            if (on) WatcherService.start(this) else stopService(Intent(this, WatcherService::class.java))
        }
        toggle("Show notification titles while locked", Prefs.showContent(this)) { Prefs.setShowContent(this, it) }

        list.addView(Button(this).apply {
            text = "Preview now"
            setOnClickListener { startActivity(Intent(context, NightstandActivity::class.java)) }
        })
        list.addView(TextView(this).apply {
            text = "Live posture: ${StandByState.lastPosture} — debug log (newest first):"
            setTextColor(Color.WHITE); setPadding(0, 24, 0, 0)
        })
    }

    private fun row(title: String, sub: String, ok: Boolean, fix: () -> Unit) {
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
            addView(TextView(context).apply {
                text = "${if (ok) "✅" else "⚠️"}  $title\n$sub"
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = if (ok) "Open" else "Grant"
                setOnClickListener { fix() }
                visibility = View.VISIBLE
            })
        })
    }

    private fun toggle(title: String, on: Boolean, set: (Boolean) -> Unit) {
        list.addView(Switch(this).apply {
            text = title; isChecked = on; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, v -> set(v) }
        })
    }
}
