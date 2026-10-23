package com.qmusic.player

import com.qmusic.player.dsp.BiquadFilter
import com.qmusic.player.dsp.EqualizerAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {

    @Test
    fun testBandFrequenciesCountAndValues() {
        val expectedFrequencies = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        assertEquals(10, EqualizerAudioProcessor.BAND_FREQUENCIES.size)
        for (i in expectedFrequencies.indices) {
            assertEquals(expectedFrequencies[i], EqualizerAudioProcessor.BAND_FREQUENCIES[i])
        }
    }

    @Test
    fun testBiquadPeakingFilterFrequencyResponse() {
        val sampleRate = 44100
        val centerFreq = 1000f
        val gainDb = 6.0f
        val q = 1.414f

        val filter = BiquadFilter.createPeaking(sampleRate.toFloat(), centerFreq, gainDb, q)

        // Evaluate H(e^jw) at center frequency w0 = 2 * pi * f0 / Fs
        // Gain at center frequency should be ~ +6 dB (linear ~ 1.995)
        val gainAtCenter = filter.getMagnitudeResponse(centerFreq, sampleRate.toFloat())
        val expectedLinearGain = Math.pow(10.0, gainDb / 20.0)
        assertEquals(expectedLinearGain, gainAtCenter.toDouble(), 0.05)

        // At frequencies far from center (e.g. 50 Hz and 15000 Hz), gain should be ~ 0 dB (linear 1.0)
        val gainAtLow = filter.getMagnitudeResponse(50f, sampleRate.toFloat())
        assertEquals(1.0, gainAtLow.toDouble(), 0.1)

        val gainAtHigh = filter.getMagnitudeResponse(15000f, sampleRate.toFloat())
        assertEquals(1.0, gainAtHigh.toDouble(), 0.1)
    }

    @Test
    fun testPreampHeadroomCompensation() {
        val processor = EqualizerAudioProcessor()

        // When all bands are 0 dB, preamp attenuation should be 0 dB (gain = 1.0)
        assertEquals(0f, processor.getPreampGainDb(), 0.001f)

        // If a band is boosted by +8 dB, preamp should attenuate by -8 dB to prevent digital clipping
        processor.setBandGain(5, 8.0f) // 1000 Hz band boosted
        assertEquals(-8.0f, processor.getPreampGainDb(), 0.01f)

        // If multiple bands are boosted, preamp attenuates by the maximum boost
        processor.setBandGain(6, 12.0f) // 2000 Hz band boosted
        assertEquals(-12.0f, processor.getPreampGainDb(), 0.01f)

        // If all bands are negative (cut only), no attenuation needed (preamp is 0 dB)
        for (i in EqualizerAudioProcessor.BAND_FREQUENCIES.indices) {
            processor.setBandGain(i, -3.0f)
        }
        assertEquals(0f, processor.getPreampGainDb(), 0.001f)
    }
}
