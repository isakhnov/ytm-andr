package com.nightstand

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Always-on watcher (idea B in README): the piece that replaces the system's
 * screensaver trigger with StandBy's actual rule — charging + landscape + still
 * + locked.
 *
 * Cost when not charging: one registered broadcast receiver, nothing else. The
 * accelerometer and a partial wake lock are only held while power is connected,
 * when battery drain doesn't matter.
 */
class WatcherService : Service(), SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private val posture = PostureDetector()
    private val policy = TriggerPolicy()
    private var accelOn = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** When stable posture last stopped being LANDSCAPE; debounces taps/jiggles. */
    private var leftLandscapeAt = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> setCharging(true)
                Intent.ACTION_POWER_DISCONNECTED -> setCharging(false)
                Intent.ACTION_SCREEN_OFF -> { log("screen off"); maybeLaunch(policy.onScreenOff()) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refreshLocked()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        createChannels(this)
        startInForeground()

        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
        StandByState.listener = { visible ->
            policy.onVisible(visible)
            if (visible) cancelLaunchNotification(this)
        }
        setCharging(isPluggedIn())
        refreshLocked()
        log("watcher started; charging=${policy.charging}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        StandByState.listener = null
        stopAccel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- state inputs -------------------------------------------------------

    private fun isPluggedIn(): Boolean {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // "Plugged", not "charging": One UI's battery protection stops charging
        // at 80/90% while the phone is still on the stand.
        return (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun setCharging(on: Boolean) {
        log("power connected=$on")
        if (on) startAccel() else stopAccel()
        maybeLaunch(policy.onCharging(on))
        if (!on) StandByState.requestHide()
    }

    private fun refreshLocked() {
        val locked = keyguard.isKeyguardLocked || !power.isInteractive
        maybeLaunch(policy.onLocked(locked))
    }

    private fun startAccel() {
        if (accelOn) return
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        posture.reset()
        sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        accelOn = true
        // Without this the CPU may suspend with the screen off and sensor events
        // stop arriving, so a phone set down on the stand with the screen already
        // off would never be noticed. Held only while plugged in.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightstand:watch").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stopAccel() {
        if (accelOn) sensors.unregisterListener(this)
        accelOn = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        policy.onLandscape(false)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = e.timestamp / 1_000_000
        val p = posture.feed(e.values[0], e.values[1], e.values[2], now)
        StandByState.lastPosture = p
        val isLand = p == PostureDetector.Posture.LANDSCAPE
        if (isLand) {
            leftLandscapeAt = -1
            if (!policy.landscape) {
                log("landscape (stable)")
                maybeLaunch(policy.onLandscape(true))
            }
        } else if (policy.landscape) {
            if (leftLandscapeAt < 0) leftLandscapeAt = now
            // A tap or a nudge makes the reading UNKNOWN for a moment; only a
            // definite other posture, or 3s of no landscape, counts as picked up.
            val definite = p == PostureDetector.Posture.PORTRAIT || p == PostureDetector.Posture.FLAT
            if (definite || now - leftLandscapeAt > 3000) {
                log("left landscape -> $p")
                policy.onLandscape(false)
                StandByState.requestHide()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- launching ----------------------------------------------------------

    private fun maybeLaunch(should: Boolean) {
        if (!should) return
        policy.onLaunched()
        log("launching Nightstand")
        launch(this)
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_nightstand)
            .setContentTitle("Nightstand is watching for the charger")
            .setContentText("Place the phone sideways on a charger, then lock it.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_WATCH, n)
        }
    }

    companion object {
        const val TAG = "Nightstand"
        const val CH_WATCH = "watch"
        const val CH_LAUNCH = "launch"
        const val ID_WATCH = 1
        const val ID_LAUNCH = 2

        fun log(msg: String) {
            Log.i(TAG, msg)
            StandByState.appendLog(msg)
        }

        fun start(c: Context) {
            c.startForegroundService(Intent(c, WatcherService::class.java))
        }

        fun createChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WATCH, "Watcher", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
            // Must be high importance: full-screen intents on lower-importance
            // channels are downgraded to plain notifications.
            nm.createNotificationChannel(
                NotificationChannel(CH_LAUNCH, "Nightstand launch", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        /**
         * Two routes, both allowed for a sideloaded app once the user grants them:
         *  1. "Display over other apps" exempts the app from background-activity-
         *     launch restrictions → start the Activity directly.
         *  2. Otherwise a full-screen-intent notification — the alarm-clock
         *     mechanism. The system launches it straight over the lock screen
         *     (or as a heads-up if the phone happens to be unlocked).
         * Both are sent: if (1) works the Activity cancels (2) as soon as it's up.
         */
        fun launch(c: Context) {
            val intent = Intent(c, NightstandActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            if (Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(intent)
                } catch (t: Throwable) {
                    log("direct start failed: $t")
                }
            }
            val nm = c.getSystemService(NotificationManager::class.java)
            val fsiAllowed = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            val pi = PendingIntent.getActivity(
                c, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(c, CH_LAUNCH)
                .setSmallIcon(R.drawable.ic_stat_nightstand)
                .setContentTitle("Nightstand")
                .setContentText("Tap to open")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .apply { if (fsiAllowed) setFullScreenIntent(pi, true) }
                .build()
            nm.notify(ID_LAUNCH, n)
            if (!fsiAllowed) log("full-screen intent not granted; posted plain notification")
        }

        fun cancelLaunchNotification(c: Context) {
            c.getSystemService(NotificationManager::class.java).cancel(ID_LAUNCH)
        }
    }
}
