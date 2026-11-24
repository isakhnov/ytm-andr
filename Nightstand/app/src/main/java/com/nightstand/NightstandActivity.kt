package com.nightstand

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.Calendar

/**
 * The StandBy screen. Shown over the lock screen (showWhenLocked) without
 * unlocking, turns the screen on when launched, keeps it on while it's up.
 *
 * Gestures: tap = brighten briefly (night) · double-tap = unlock and close.
 * Closes itself when the charger is pulled or the phone is picked up
 * (WatcherService → StandByState.requestHide).
 */
class NightstandActivity : Activity(), SensorEventListener {

    private lateinit var view: NightstandView
    private lateinit var sensors: SensorManager
    private val main = Handler(Looper.getMainLooper())
    private val nightCtl = NightModeController()
    private var lightSensor: Sensor? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(i)
                else -> refreshAll() // TIME_TICK / TIME_SET / TIMEZONE_CHANGED
            }
        }
    }

    private val calendarObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshEvents()
    }

    private val onFeedChanged: () -> Unit = { refreshNotifs() }
    private val hide: () -> Unit = { if (!isFinishing) finish() }
    private val endBoost = Runnable { view.nightBoost = false; applyBrightness() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = NightstandView(this)
        setContentView(view)
        hideSystemBars()

        sensors = getSystemService(SensorManager::class.java)
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            nightCtl.set(NightModeController.nightBySchedule(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { boost(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { unlockAndClose(); return true }
        })
        view.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        StandByState.hideRequest = hide
        StandByState.setVisible(true)
        WatcherService.cancelLaunchNotification(this)
        WatcherService.log("Nightstand shown")
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
        if (CalendarSource.isGranted(this)) {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        }
        StandByState.changeListeners += onFeedChanged
        lightSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        view.showNotifContent = Prefs.showContent(this)
        refreshAll()
        applyBrightness()
    }

    override fun onStop() {
        unregisterReceiver(tick)
        contentResolver.unregisterContentObserver(calendarObserver)
        StandByState.changeListeners -= onFeedChanged
        sensors.unregisterListener(this)
        super.onStop()
        // Stopped because the screen went off → stay, so Nightstand is still
        // there when the screen comes back (iPhone behaviour). Stopped because
        // the user unlocked and went elsewhere → close, so the watcher can show
        // it again next time the phone is locked on the stand.
        val screenOff = !getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!screenOff && !locked && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (StandByState.hideRequest === hide) StandByState.hideRequest = null
        StandByState.setVisible(false)
        main.removeCallbacksAndMessages(null)
        WatcherService.log("Nightstand closed")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- content ------------------------------------------------------------

    private fun refreshAll() {
        refreshEvents()
        refreshNotifs()
        if (lightSensor == null &&
            nightCtl.set(NightModeController.nightBySchedule(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        ) applyBrightness()
        view.invalidate() // minute changed → clock + burn-in shift
    }

    private fun refreshEvents() { view.events = CalendarSource.upcoming(this) }
    private fun refreshNotifs() { view.notifs = NotificationFeed.snapshot }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        view.batteryPct = if (level >= 0) level * 100 / scale else -1
        view.charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING
        view.invalidate()
    }

    // --- night mode ---------------------------------------------------------

    private var firstLux = true

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        val changed = if (firstLux) {
            // Decide immediately on launch instead of waiting out the hold time.
            firstLux = false
            nightCtl.set(lux < 3f)
        } else nightCtl.onLux(lux, System.currentTimeMillis())
        if (changed) {
            WatcherService.log("night=${nightCtl.night} (lux=$lux)")
            applyBrightness()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyBrightness() {
        view.night = nightCtl.night
        window.attributes = window.attributes.apply {
            screenBrightness = when {
                !nightCtl.night -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                view.nightBoost -> 0.15f
                else -> 0.01f
            }
        }
    }

    private fun boost() {
        if (!nightCtl.night) return
        view.nightBoost = true
        applyBrightness()
        main.removeCallbacks(endBoost)
        main.postDelayed(endBoost, 6000)
    }

    private fun unlockAndClose() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else finish()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
