package com.nightstand

import com.nightstand.PostureDetector.Posture
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PostureDetectorTest {
    private val g = 9.81f

    /** Gravity for a phone on a stand tilted back [tiltDeg]; landscape = rotated onto its side. */
    private fun onStand(landscape: Boolean, tiltDeg: Double = 20.0, left: Boolean = true): FloatArray {
        val t = Math.toRadians(tiltDeg)
        val up = (g * cos(t)).toFloat()
        val z = (g * sin(t)).toFloat()
        return if (landscape) floatArrayOf(if (left) up else -up, 0.3f, z) else floatArrayOf(0.2f, up, z)
    }

    private fun feedFor(d: PostureDetector, v: FloatArray, fromMs: Long, durMs: Long): Posture {
        var p = Posture.UNKNOWN
        var t = fromMs
        while (t <= fromMs + durMs) { p = d.feed(v[0], v[1], v[2], t); t += 200 }
        return p
    }

    @Test fun classify_basicPostures() {
        assertEquals(Posture.LANDSCAPE, PostureDetector.classify(9.5f, 0.5f, 2.5f))
        assertEquals(Posture.LANDSCAPE, PostureDetector.classify(-9.5f, 0.5f, 2.5f))
        assertEquals(Posture.PORTRAIT, PostureDetector.classify(0.3f, 9.5f, 2.5f))
        assertEquals(Posture.FLAT, PostureDetector.classify(0.1f, 0.2f, 9.8f))
        // Upside-down portrait isn't a stand posture.
        assertEquals(Posture.UNKNOWN, PostureDetector.classify(0.3f, -9.5f, 2.5f))
        // Free fall / violent shake.
        assertEquals(Posture.UNKNOWN, PostureDetector.classify(0.5f, 0.5f, 0.5f))
    }

    @Test fun landscapeOnStand_becomesStableAfterHold() {
        val d = PostureDetector(stableMs = 1500)
        assertEquals(Posture.UNKNOWN, feedFor(d, onStand(true), 0, 1000))
        assertEquals(Posture.LANDSCAPE, feedFor(d, onStand(true), 1200, 1000))
    }

    @Test fun bothLandscapeDirectionsAndSteepTilt() {
        assertEquals(Posture.LANDSCAPE, feedFor(PostureDetector(), onStand(true, 40.0, left = false), 0, 3000))
        assertEquals(Posture.LANDSCAPE, feedFor(PostureDetector(), onStand(true, 5.0), 0, 3000))
    }

    @Test fun portraitOnStand_isNotLandscape() {
        assertEquals(Posture.PORTRAIT, feedFor(PostureDetector(), onStand(false), 0, 3000))
    }

    @Test fun motion_dropsStableImmediately() {
        val d = PostureDetector()
        assertEquals(Posture.LANDSCAPE, feedFor(d, onStand(true), 0, 3000))
        // A jolt: big sample far from the gravity estimate.
        assertEquals(Posture.UNKNOWN, d.feed(2f, 8f, 9f, 3200))
    }
}
