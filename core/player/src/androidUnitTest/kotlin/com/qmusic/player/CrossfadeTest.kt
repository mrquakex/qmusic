package com.qmusic.player

import com.qmusic.player.dsp.CrossfadeFilterAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class CrossfadeTest {

    @Test
    fun testEqualPowerSinusoidalCurve() {
        val processor = CrossfadeFilterAudioProcessor()

        // At t = 0
        processor.setCrossfadeProgress(0f, isFadeIn = false)
        assertEquals(1.0f, processor.getVolume(), 0.001f)

        processor.setCrossfadeProgress(0f, isFadeIn = true)
        assertEquals(0.0f, processor.getVolume(), 0.001f)

        // At t = 0.5 (Midpoint)
        // sqrt(0.5 * (1 + cos(pi/2))) = sqrt(0.5) ~ 0.707106
        val expectedMidpoint = sqrt(0.5).toFloat()

        processor.setCrossfadeProgress(0.5f, isFadeIn = false)
        val fadeOutMid = processor.getVolume()
        assertEquals(expectedMidpoint, fadeOutMid, 0.01f)

        processor.setCrossfadeProgress(0.5f, isFadeIn = true)
        val fadeInMid = processor.getVolume()
        assertEquals(expectedMidpoint, fadeInMid, 0.01f)

        // Equal power property: fadeOut^2 + fadeIn^2 == 1.0
        val totalPower = fadeOutMid * fadeOutMid + fadeInMid * fadeInMid
        assertEquals(1.0f, totalPower, 0.01f)

        // At t = 1.0
        processor.setCrossfadeProgress(1.0f, isFadeIn = false)
        assertEquals(0.0f, processor.getVolume(), 0.001f)

        processor.setCrossfadeProgress(1.0f, isFadeIn = true)
        assertEquals(1.0f, processor.getVolume(), 0.001f)
    }

    @Test
    fun testClamping() {
        val processor = CrossfadeFilterAudioProcessor()

        processor.setCrossfadeProgress(-0.5f, isFadeIn = false)
        assertEquals(1.0f, processor.getVolume(), 0.001f)

        processor.setCrossfadeProgress(1.5f, isFadeIn = false)
        assertEquals(0.0f, processor.getVolume(), 0.001f)
    }
}
