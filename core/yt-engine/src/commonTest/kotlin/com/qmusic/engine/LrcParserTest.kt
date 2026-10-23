package com.qmusic.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LrcParserTest {
    @Test
    fun readsTimesAndWords() {
        val lines = LrcParser.parse("[00:12.50] First line\n[01:02.05]Second line\n[ar: Someone]\n[10:00.000] Late")
        assertEquals(3, lines.size)
        assertEquals(12_500L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(62_050L, lines[1].timeMs)
        assertEquals(600_000L, lines[2].timeMs)
    }

    @Test
    fun aLineWithSeveralTagsIsRepeatedAndEverythingIsSorted() {
        val lines = LrcParser.parse("[00:30.00][00:10.00] Chorus\n[00:20.00] Verse")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.text })
    }

    @Test
    fun plainTextHasNoLines() {
        assertTrue(LrcParser.parse("just words\nno times").isEmpty())
    }
}
