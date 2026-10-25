package com.qmusic.player.ringtone

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingtoneMathTest {

    private val minute = 60_000L

    @Test
    fun theFirstClipIsThirtySecondsOrTheWholeShortSong() {
        assertEquals(30_000L, RingtoneMath.defaultLength(4 * minute))
        assertEquals(12_000L, RingtoneMath.defaultLength(12_000L))
        assertEquals(2_000L, RingtoneMath.defaultLength(2_000L))
    }

    @Test
    fun movingTheStartKeepsTheClipBetweenItsLimits() {
        val clip = ClipRange(60_000L, 90_000L)
        // Dragged right up to the end: the clip keeps its shortest length instead of vanishing.
        val squeezed = RingtoneMath.moveStart(clip, 89_000L, 4 * minute)
        assertEquals(89_000L, squeezed.startMs)
        assertEquals(RingtoneMath.MIN_LENGTH_MS, squeezed.lengthMs)
        // Dragged far left: the clip may not grow past the longest length, the end gives way.
        val stretched = RingtoneMath.moveStart(clip, 10_000L, 4 * minute)
        assertEquals(10_000L, stretched.startMs)
        assertEquals(RingtoneMath.MAX_LENGTH_MS, stretched.lengthMs)
        // Never before the start of the song.
        assertEquals(0L, RingtoneMath.moveStart(clip, -5_000L, 4 * minute).startMs)
    }

    @Test
    fun movingTheEndKeepsTheClipBetweenItsLimits() {
        val clip = ClipRange(60_000L, 90_000L)
        val squeezed = RingtoneMath.moveEnd(clip, 61_000L, 4 * minute)
        assertEquals(RingtoneMath.MIN_LENGTH_MS, squeezed.lengthMs)
        assertEquals(61_000L, squeezed.endMs)
        val stretched = RingtoneMath.moveEnd(clip, 200_000L, 4 * minute)
        assertEquals(200_000L, stretched.endMs)
        assertEquals(RingtoneMath.MAX_LENGTH_MS, stretched.lengthMs)
        // Never past the end of the song.
        assertEquals(4 * minute, RingtoneMath.moveEnd(clip, 9 * minute, 4 * minute).endMs)
    }

    @Test
    fun draggingTheWholeClipKeepsItsLengthAndStaysInsideTheSong() {
        val clip = ClipRange(60_000L, 90_000L)
        assertEquals(ClipRange(100_000L, 130_000L), RingtoneMath.moveClip(clip, 100_000L, 4 * minute))
        assertEquals(ClipRange(210_000L, 240_000L), RingtoneMath.moveClip(clip, 400_000L, 4 * minute))
        assertEquals(ClipRange(0L, 30_000L), RingtoneMath.moveClip(clip, -1L, 4 * minute))
    }

    @Test
    fun theLoudestStretchIsSuggested() {
        // 60 s of quiet, 30 s of loud, 60 s of quiet; one value per 50 ms.
        val energy = FloatArray(3000) { 0.05f }
        for (i in 1200 until 1800) energy[i] = 0.5f
        val start = RingtoneMath.suggestStart(energy, 30_000L)
        // The loud block is at 60 s; the suggestion is within the snapping distance of it.
        assertTrue(abs(start - 60_000L) <= 1_500L, "start was $start")
    }

    @Test
    fun theStartLandsOnAQuietMomentNextToTheLoudestStretch() {
        val energy = FloatArray(2400) { 0.05f }
        for (i in 1000 until 1600) energy[i] = 0.6f
        // A short pause just before the loud block, 0.5 s earlier than the block starts.
        for (i in 975 until 990) energy[i] = 0.0f
        val start = RingtoneMath.suggestStart(energy, 30_000L)
        assertTrue(start in 48_000L..50_500L, "start was $start")
    }

    @Test
    fun aSongShorterThanTheClipStartsAtZero() {
        assertEquals(0L, RingtoneMath.suggestStart(FloatArray(100) { 0.3f }, 30_000L))
        assertEquals(0L, RingtoneMath.suggestStart(FloatArray(0), 30_000L))
    }

    @Test
    fun barsAreScaledSoTheLoudestIsOne() {
        val bars = RingtoneMath.bars(floatArrayOf(0.1f, 0.4f, 0.2f, 0.05f), 2)
        assertEquals(2, bars.size)
        assertEquals(1f, bars.max(), 1e-6f)
        assertTrue(bars[0] > bars[1])
        // Silence draws nothing but does not break.
        assertEquals(listOf(0f, 0f, 0f), RingtoneMath.bars(FloatArray(10), 3).toList())
        assertEquals(4, RingtoneMath.bars(FloatArray(0), 4).size)
    }

    @Test
    fun fadesStartAtSilenceEndAtSilenceAndHoldFullVolumeBetween() {
        val total = 1000L
        assertEquals(0f, RingtoneMath.fadeGain(0, total, 100, 100), 1e-6f)
        assertEquals(0f, RingtoneMath.fadeGain(total - 1, total, 100, 100), 1e-6f)
        assertEquals(1f, RingtoneMath.fadeGain(500, total, 100, 100), 1e-6f)
        assertEquals(0.5f, RingtoneMath.fadeGain(50, total, 100, 100), 1e-3f)
        // No fades asked: always full.
        assertEquals(1f, RingtoneMath.fadeGain(0, total, 0, 0), 1e-6f)
        assertEquals(1f, RingtoneMath.fadeGain(total - 1, total, 0, 0), 1e-6f)
        // A fade never gets louder as it goes on.
        var last = 0f
        for (i in 0L..100L) {
            val g = RingtoneMath.fadeGain(i, total, 100, 0)
            assertTrue(g >= last)
            last = g
        }
    }

    @Test
    fun quietClipsAreRaisedLoudOnesAreLeftAlone() {
        assertEquals(4.0, RingtoneMath.loudnessGain(0.01), 1e-9)
        assertEquals(2.5, RingtoneMath.loudnessGain(0.1), 1e-9)
        assertEquals(1.0, RingtoneMath.loudnessGain(0.4), 1e-9)
        assertEquals(1.0, RingtoneMath.loudnessGain(0.0), 1e-9)
    }

    @Test
    fun theLimiterKeepsPeaksBelowFullScaleAndLeavesTheRestAlone() {
        assertEquals(0.5f, RingtoneMath.softLimit(0.5f), 1e-6f)
        assertEquals(-0.8f, RingtoneMath.softLimit(-0.8f), 1e-6f)
        for (x in listOf(0.9f, 1.2f, 3f, 10f)) {
            val y = RingtoneMath.softLimit(x)
            assertTrue(y in 0.8f..1f, "$x -> $y")
            assertEquals(-y, RingtoneMath.softLimit(-x), 1e-6f)
        }
        // Louder in gives louder out.
        assertTrue(RingtoneMath.softLimit(1.5f) > RingtoneMath.softLimit(0.9f))
    }

    @Test
    fun shapingFadesBothChannelsTogetherAndRaisesWithoutClipping() {
        // Stereo, 1000 frames at a steady, quiet level.
        val samples = ShortArray(2000) { 3000 }
        RingtoneMath.shape(samples, channels = 2, fadeInFrames = 100, fadeOutFrames = 100, gain = 1.0)
        assertEquals(0, samples[0].toInt())
        assertEquals(0, samples[1].toInt())
        assertTrue(abs(samples[1000] - 3000) <= 2, "middle was ${samples[1000]}")
        assertEquals(0, samples[1998].toInt())
        assertEquals(samples[400], samples[401])

        val loud = ShortArray(400) { if (it % 2 == 0) 30000 else -30000 }
        RingtoneMath.shape(loud, channels = 2, fadeInFrames = 0, fadeOutFrames = 0, gain = 4.0)
        assertTrue(loud.all { abs(it.toInt()) <= 32767 })
        assertTrue(loud[0] > 30000)
    }

    @Test
    fun rmsOfASineIsAboutSeventyPercentOfItsPeak() {
        val samples = ShortArray(4800) { (kotlin.math.sin(it * 0.1) * 20000).toInt().toShort() }
        val rms = RingtoneMath.rms(samples)
        assertEquals(20000 / 32768.0 / kotlin.math.sqrt(2.0), rms, 0.01)
        assertEquals(0.0, RingtoneMath.rms(ShortArray(0)), 0.0)
    }
}
