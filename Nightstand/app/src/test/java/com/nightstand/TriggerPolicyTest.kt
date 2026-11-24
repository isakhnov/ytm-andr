package com.nightstand

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerPolicyTest {
    private fun ready() = TriggerPolicy().apply {
        onCharging(true); onLocked(true)
    }

    @Test fun showsWhenAllConditionsMet() {
        val p = TriggerPolicy()
        assertFalse(p.onCharging(true))
        assertFalse(p.onLandscape(true))
        assertTrue(p.onLocked(true))
    }

    @Test fun notWhenUnlocked() {
        val p = TriggerPolicy()
        p.onCharging(true); p.onLocked(false)
        assertFalse(p.onLandscape(true))
    }

    @Test fun notWhenPortrait() {
        val p = ready()
        assertFalse(p.onLandscape(false))
    }

    @Test fun doesNotRelaunchAfterDismissUntilRearmed() {
        val p = ready()
        assertTrue(p.onLandscape(true)); p.onLaunched()
        p.onVisible(true); p.onVisible(false) // user double-tapped to unlock
        assertFalse(p.onLocked(true))         // locked again, but not re-armed
        assertTrue(p.onScreenOff())           // screen timed out → back to Nightstand
    }

    @Test fun rearmsWhenChargerReconnected() {
        val p = ready()
        p.onLandscape(true); p.onLaunched()
        p.onCharging(false)
        assertTrue(p.onCharging(true))
    }

    @Test fun rearmsWhenPickedUpAndPutBack() {
        val p = ready()
        p.onLandscape(true); p.onLaunched()
        p.onLandscape(false)
        assertTrue(p.onLandscape(true))
    }

    @Test fun neverWhileAlreadyVisible() {
        val p = ready()
        p.onLandscape(true); p.onLaunched(); p.onVisible(true)
        assertFalse(p.onScreenOff()) // power button while showing: don't relaunch
    }

    @Test fun hideWhenUnpluggedOrPickedUp() {
        val p = ready()
        p.onLandscape(true); p.onVisible(true)
        assertFalse(p.shouldHide())
        p.onCharging(false)
        assertTrue(p.shouldHide())
    }
}
