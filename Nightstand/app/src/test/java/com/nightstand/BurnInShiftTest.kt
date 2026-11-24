package com.nightstand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInShiftTest {
    @Test fun shiftStaysWithinAmplitudeAndMoves() {
        val seen = (0 until 60).map { NightstandView.shiftFor(it, 10f) }
        assertTrue(seen.all { (x, y) -> x in -10f..10f && y in -10f..10f })
        assertTrue(seen.toSet().size >= 9)
        assertEquals(seen[0], seen[9]) // 9-step cycle
    }
}
