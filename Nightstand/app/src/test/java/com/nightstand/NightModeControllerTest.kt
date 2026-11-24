package com.nightstand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeControllerTest {
    @Test fun entersAfterHoldInDark() {
        val c = NightModeController(enterLux = 3f, exitLux = 15f, holdMs = 8000)
        assertFalse(c.onLux(1f, 0))
        assertFalse(c.onLux(1f, 7000))
        assertTrue(c.onLux(1f, 8000))
        assertTrue(c.night)
    }

    @Test fun briefLightDoesNotExit() {
        val c = NightModeController(holdMs = 8000).apply { set(true) }
        c.onLux(200f, 0)        // headlights
        c.onLux(1f, 2000)       // dark again → pending reset
        assertFalse(c.onLux(200f, 9000))
        assertTrue(c.night)
    }

    @Test fun hysteresisBandHoldsState() {
        val c = NightModeController(enterLux = 3f, exitLux = 15f, holdMs = 0).apply { set(true) }
        assertFalse(c.onLux(10f, 0)) // between thresholds: stay night
        assertTrue(c.night)
        assertTrue(c.onLux(20f, 1))
        assertFalse(c.night)
    }

    @Test fun schedule() {
        assertTrue(NightModeController.nightBySchedule(23))
        assertTrue(NightModeController.nightBySchedule(3))
        assertFalse(NightModeController.nightBySchedule(12))
        assertEquals(false, NightModeController.nightBySchedule(7))
    }
}
