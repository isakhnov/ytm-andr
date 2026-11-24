package com.nightstand

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Classifies how the phone is resting from raw accelerometer samples.
 *
 * Device axes (Android convention, phone held in natural portrait):
 *   x → toward the right edge, y → toward the top edge, z → out of the screen.
 * At rest the accelerometer reads gravity (~9.8 m/s²) split across those axes:
 *   - landscape on a stand: mostly along ±x, some z from the stand's back tilt
 *   - portrait on a stand:  mostly along +y
 *   - flat on a table:      mostly along ±z
 *
 * "Stable" means the same posture held for [stableMs] with no significant motion,
 * which mirrors StandBy's "placed and still" condition and filters out the
 * phone being carried or fiddled with on the charger.
 *
 * Pure Kotlin — no Android types — so it's unit-tested on the JVM.
 */
class PostureDetector(
    private val stableMs: Long = 1500,
    /** Summed per-axis deviation from the gravity estimate still counted as "still", m/s². */
    private val motionTolerance: Float = 2.5f,
) {
    enum class Posture { LANDSCAPE, PORTRAIT, FLAT, UNKNOWN }

    private var fx = 0f
    private var fy = 0f
    private var fz = 0f
    private var primed = false

    private var candidate = Posture.UNKNOWN
    private var candidateSince = 0L

    /** Last posture that held for [stableMs]; UNKNOWN until one has. */
    var stable: Posture = Posture.UNKNOWN
        private set

    fun reset() {
        primed = false
        candidate = Posture.UNKNOWN
        stable = Posture.UNKNOWN
    }

    /** Feed one sample. Returns the current stable posture. */
    fun feed(x: Float, y: Float, z: Float, nowMs: Long): Posture {
        if (!primed) {
            fx = x; fy = y; fz = z; primed = true
            candidateSince = nowMs
        }
        // Low-pass to get gravity; the residual is motion.
        val a = 0.2f
        fx += a * (x - fx); fy += a * (y - fy); fz += a * (z - fz)
        val motion = abs(x - fx) + abs(y - fy) + abs(z - fz)

        if (motion > motionTolerance) {
            // Being moved: drop the stable reading straight away so a phone lifted
            // off the stand stops counting as "landscape" immediately.
            candidate = Posture.UNKNOWN
            candidateSince = nowMs
            stable = Posture.UNKNOWN
            return stable
        }
        val instant = classify(fx, fy, fz)
        if (instant != candidate) {
            candidate = instant
            candidateSince = nowMs
        }
        if (nowMs - candidateSince >= stableMs) stable = candidate
        return stable
    }

    companion object {
        fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

        /**
         * Thresholds allow up to ~45° of back-tilt (typical stands are 15–30°)
         * and ~20° of roll before the reading stops counting as landscape.
         */
        fun classify(x: Float, y: Float, z: Float): Posture {
            val g = magnitude(x, y, z)
            if (g < 7f || g > 12.5f) return Posture.UNKNOWN // free-fall / shaking
            val ax = abs(x); val ay = abs(y); val az = abs(z)
            return when {
                az > 0.8f * g -> Posture.FLAT
                ax > 0.6f * g && ax > 2.5f * ay -> Posture.LANDSCAPE
                y > 0.6f * g && ay > 2.5f * ax -> Posture.PORTRAIT
                else -> Posture.UNKNOWN
            }
        }
    }
}
