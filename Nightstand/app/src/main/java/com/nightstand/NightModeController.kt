package com.nightstand

/**
 * Dark-room detection with hysteresis, like StandBy's red Night Mode: driven by
 * ambient light, not the clock. Enter night after [holdMs] below [enterLux];
 * leave after [holdMs] above [exitLux]. The gap between thresholds plus the
 * hold time stops flicker from a passing car's headlights or the screen's own
 * glow reflecting into the sensor.
 *
 * Fallback when the device reports no light sensor: night by schedule.
 */
class NightModeController(
    private val enterLux: Float = 3f,
    private val exitLux: Float = 15f,
    private val holdMs: Long = 8000,
) {
    var night = false
        private set
    private var pendingSince = -1L

    /** Returns true if [night] changed. */
    fun onLux(lux: Float, nowMs: Long): Boolean {
        val wantsChange = if (night) lux > exitLux else lux < enterLux
        if (!wantsChange) {
            pendingSince = -1
            return false
        }
        if (pendingSince < 0) pendingSince = nowMs
        if (nowMs - pendingSince >= holdMs) {
            night = !night
            pendingSince = -1
            return true
        }
        return false
    }

    /** Forces the state, e.g. from the schedule fallback or on first reading. */
    fun set(value: Boolean): Boolean {
        val changed = value != night
        night = value
        pendingSince = -1
        return changed
    }

    companion object {
        /** Schedule fallback: 22:00–07:00. */
        fun nightBySchedule(hourOfDay: Int) = hourOfDay >= 22 || hourOfDay < 7
    }
}
