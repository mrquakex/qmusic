package com.qmusic.ui

import com.qmusic.ui.videos.chaptersOf
import com.qmusic.ui.videos.currentChapter
import com.qmusic.ui.videos.linkedDescription
import com.qmusic.ui.videos.timeAt
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTextTest {
    private val description = """Great video!
0:00 Intro
1:30 - The first idea
1:02:05 Last words
Follow me everywhere"""

    @Test
    fun chaptersAreReadFromTheTimesInADescription() {
        val chapters = chaptersOf(description)
        assertEquals(listOf(0L, 90_000L, 3_725_000L), chapters.map { it.timeMs })
        assertEquals("The first idea", chapters[1].title)
        assertEquals("The first idea", currentChapter(chapters, 120_000)?.title)
        assertEquals("Intro", currentChapter(chapters, 5_000)?.title)
    }

    @Test
    fun aListThatDoesNotStartAtZeroOrGoesBackwardsIsNotChapters() {
        assertTrue(chaptersOf("1:00 One\n2:00 Two").isEmpty())
        assertTrue(chaptersOf("0:00 One\n3:00 Two\n2:00 Three").isEmpty())
        assertTrue(chaptersOf("no times here").isEmpty())
        assertTrue(chaptersOf(null).isEmpty())
    }

    @Test
    fun everyTimeInTheTextIsALinkToThatMoment() {
        val text = "see 0:45 and 1:02:03 please"
        val linked = linkedDescription(text, Color.Blue)
        assertEquals(text, linked.text)
        assertEquals(45_000L, linked.timeAt(text.indexOf("0:45") + 1))
        assertEquals(3_723_000L, linked.timeAt(text.indexOf("1:02:03") + 2))
        assertNull(linked.timeAt(1))
    }
}
