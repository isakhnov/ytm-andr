package com.nightstand

/**
 * Decides when to show the Nightstand screen — the iPhone StandBy rule:
 * charging + landscape + still + locked. Pure state machine, unit-tested.
 *
 * "Armed" prevents a fight with the user: after Nightstand has been shown once,
 * it won't pop back up the moment they dismiss it. It re-arms on a fresh
 * reason to show — the charger reconnected, the screen turned off, or the phone
 * left landscape and came back. That gives the iPhone behaviour: unlock and use
 * the phone on the stand, and Nightstand returns when the screen times out.
 */
class TriggerPolicy {
    var charging = false
        private set
    var landscape = false
        private set
    var locked = false
        private set
    var visible = false
        private set
    private var armed = true

    fun onCharging(value: Boolean): Boolean {
        if (value && !charging) armed = true
        charging = value
        return shouldShow()
    }

    fun onLandscape(value: Boolean): Boolean {
        if (value != landscape && !value) armed = true // re-arm when it leaves landscape
        landscape = value
        return shouldShow()
    }

    /** [value] = keyguard is showing, or screen is off. */
    fun onLocked(value: Boolean): Boolean {
        locked = value
        return shouldShow()
    }

    fun onScreenOff(): Boolean {
        armed = true
        locked = true
        return shouldShow()
    }

    fun onVisible(value: Boolean) {
        visible = value
    }

    /** Call when a launch is actually attempted. */
    fun onLaunched() {
        armed = false
    }

    fun shouldShow(): Boolean = charging && landscape && locked && armed && !visible

    /** Nightstand should close itself: charger pulled or phone picked up. */
    fun shouldHide(): Boolean = visible && (!charging || !landscape)
}
