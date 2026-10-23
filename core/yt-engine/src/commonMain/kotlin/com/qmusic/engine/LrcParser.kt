package com.qmusic.engine

import com.qmusic.engine.models.LyricLine

/** Reads the common ".lrc" format: `[mm:ss.xx] words`, where a line may carry several time tags. */
object LrcParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (match in tags) {
                val (minutes, seconds, fraction) = match.destructured
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                lines += LyricLine(minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }
}
