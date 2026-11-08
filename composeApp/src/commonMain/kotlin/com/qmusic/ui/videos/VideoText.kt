package com.qmusic.ui.videos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** A part of a video that its description names with a time: "0:00 Intro", "3:15 The first idea". */
internal data class Chapter(val timeMs: Long, val title: String)

private val TIMESTAMP = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")
private val CHAPTER_LINE = Regex("""^[\s\-•·*]*((?:\d{1,2}:)?\d{1,2}:\d{2})[\s\-–—:|.)]*(.+)$""")

private fun toMs(match: MatchResult): Long {
    val hours = match.groupValues[1].toLongOrNull() ?: 0L
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    return ((hours * 60 + minutes) * 60 + seconds) * 1000
}

/** The chapters of a description (YouTube's own rule: a list of times that starts at 0:00 and has at least two). */
internal fun chaptersOf(description: String?): List<Chapter> {
    if (description.isNullOrBlank()) return emptyList()
    val found = description.lines().mapNotNull { line ->
        val m = CHAPTER_LINE.find(line.trim()) ?: return@mapNotNull null
        val time = TIMESTAMP.find(m.groupValues[1]) ?: return@mapNotNull null
        Chapter(toMs(time), m.groupValues[2].trim())
    }
    if (found.size < 2 || found.first().timeMs != 0L) return emptyList()
    // Times must go up, or it was a list of something else.
    return if (found.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs }) found else emptyList()
}

internal fun currentChapter(chapters: List<Chapter>, positionMs: Long): Chapter? = chapters.lastOrNull { it.timeMs <= positionMs }

private const val TIME_TAG = "time"

/** The description with every time in it a link; [timeAt] finds the time (in ms) under a tap. */
internal fun linkedDescription(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    TIMESTAMP.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        pushStringAnnotation(TIME_TAG, toMs(m).toString())
        pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        append(m.value)
        pop()
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}

internal fun AnnotatedString.timeAt(offset: Int): Long? =
    getStringAnnotations(TIME_TAG, offset, offset).firstOrNull()?.item?.toLongOrNull()
