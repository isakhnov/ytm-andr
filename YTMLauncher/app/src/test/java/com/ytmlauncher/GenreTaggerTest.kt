package com.ytmlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cyrillic-detection rule is entirely self-contained and network-free
 * (classify() returns "Russian" before ever calling iTunes), so it's safe
 * and fast to test directly without mocking HTTP. The actual iTunes-backed
 * classification path isn't covered here — it needs network I/O, out of
 * scope for a fast unit suite; that path is exercised manually via
 * Settings' "Tag genres automatically".
 */
class GenreTaggerTest {

    @Test
    fun `isCyrillic detects Cyrillic script`() {
        assertTrue(GenreTagger.isCyrillic("Кино"))
        assertTrue(GenreTagger.isCyrillic("Душа бойца"))
    }

    @Test
    fun `isCyrillic is false for Latin script including diacritics`() {
        assertFalse(GenreTagger.isCyrillic("Rammstein"))
        assertFalse(GenreTagger.isCyrillic("Motörhead"))
    }

    @Test
    fun `classify routes a Cyrillic title straight to Russian without network`() {
        assertEquals("Russian", GenreTagger.classify("Душа бойца", "7Б"))
    }

    @Test
    fun `classify routes a Cyrillic artist to Russian even with a Latin title`() {
        assertEquals("Russian", GenreTagger.classify("Ya delayu shag", "Кино"))
    }
}
