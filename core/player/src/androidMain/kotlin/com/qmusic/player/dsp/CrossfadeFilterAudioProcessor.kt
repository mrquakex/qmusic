package com.qmusic.player.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Crossfade AudioProcessor applying equal-power sinusoidal curve volume scaling
 * and dynamic filter sweeps for seamless DJ mixing.
 *
 * Curve:
 * - Fade out: sqrt(0.5 * (1 + cos(pi * t)))
 * - Fade in:  sqrt(0.5 * (1 - cos(pi * t)))
 */
class CrossfadeFilterAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var volumeFactor: Float = 1.0f

    @Volatile
    private var isFilteringEnabled: Boolean = false

    // Cascaded LPF / HPF for dual-stage frequency sweeps per channel
    private var lpfFilters: Array<Array<BiquadFilter>> = emptyArray()
    private var hpfFilters: Array<Array<BiquadFilter>> = emptyArray()

    /**
     * Sets current linear volume factor directly (0.0f .. 1.0f).
     */
    fun setVolume(volume: Float) {
        this.volumeFactor = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = volumeFactor

    /**
     * Sets crossfade transition progress t in [0.0, 1.0].
     * Computes equal-power sinusoidal volume:
     * - Fade Out: sqrt(0.5 * (1 + cos(pi * t)))
     * - Fade In:  sqrt(0.5 * (1 - cos(pi * t)))
     *
     * Also dynamically sweeps filter cutoffs:
     * - Fade Out: LPF sweeps from 20,000 Hz down to 300 Hz
     * - Fade In:  HPF sweeps from 10,000 Hz down to 20 Hz (opening up the spectrum)
     */
    fun setCrossfadeProgress(t: Float, isFadeIn: Boolean, enableFilterSweep: Boolean = false) {
        val clampedT = t.coerceIn(0f, 1f)
        val vol = if (isFadeIn) {
            sqrt(0.5 * (1.0 - cos(PI * clampedT))).toFloat()
        } else {
            sqrt(0.5 * (1.0 + cos(PI * clampedT))).toFloat()
        }
        this.volumeFactor = vol
        this.isFilteringEnabled = enableFilterSweep

        if (enableFilterSweep) {
            updateSweepFilters(clampedT, isFadeIn)
        }
    }

    private fun updateSweepFilters(t: Float, isFadeIn: Boolean) {
        val sampleRate = if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET && inputAudioFormat.sampleRate > 0) {
            inputAudioFormat.sampleRate.toFloat()
        } else {
            44100f
        }

        val channelCount = if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET && inputAudioFormat.channelCount > 0) {
            inputAudioFormat.channelCount
        } else {
            2
        }

        if (isFadeIn) {
            // HPF sweep from 8000 Hz down to 20 Hz
            val cutoff = (8000f * (1f - t) + 20f).coerceIn(20f, sampleRate / 2f - 100f)
            ensureFilterArrays(channelCount, sampleRate)
            for (ch in 0 until channelCount) {
                val hpf = BiquadFilter.createHighPass(sampleRate, cutoff)
                hpfFilters[ch][0].updateCoefficients(hpf)
                hpfFilters[ch][1].updateCoefficients(hpf)
            }
        } else {
            // LPF sweep from 20000 Hz down to 300 Hz
            val cutoff = (20000f * (1f - t) + 300f).coerceIn(100f, sampleRate / 2f - 100f)
            ensureFilterArrays(channelCount, sampleRate)
            for (ch in 0 until channelCount) {
                val lpf = BiquadFilter.createLowPass(sampleRate, cutoff)
                lpfFilters[ch][0].updateCoefficients(lpf)
                lpfFilters[ch][1].updateCoefficients(lpf)
            }
        }
    }

    private fun ensureFilterArrays(channelCount: Int, sampleRate: Float) {
        if (lpfFilters.size != channelCount) {
            lpfFilters = Array(channelCount) {
                Array(2) { BiquadFilter.createLowPass(sampleRate, 20000f) }
            }
            hpfFilters = Array(channelCount) {
                Array(2) { BiquadFilter.createHighPass(sampleRate, 20f) }
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sampleRate = inputAudioFormat.sampleRate.toFloat()
        val channelCount = inputAudioFormat.channelCount
        ensureFilterArrays(channelCount, sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        output.order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        val vol = volumeFactor

        // If volume is 1.0 and no filter sweep, bypass
        if (vol >= 0.999f && !isFilteringEnabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        // If volume is muted, fill output with silence
        if (vol <= 0.0001f) {
            val zeroBytes = ByteArray(remaining)
            output.put(zeroBytes)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        if (encoding == C.ENCODING_PCM_16BIT) {
            val sampleCount = remaining / 2
            val frameCount = sampleCount / channelCount

            for (f in 0 until frameCount) {
                for (ch in 0 until channelCount) {
                    val rawSample = inputBuffer.short
                    var sampleFloat = (rawSample.toFloat() / 32768f) * vol

                    if (isFilteringEnabled && ch < lpfFilters.size) {
                        sampleFloat = lpfFilters[ch][0].process(sampleFloat)
                        sampleFloat = lpfFilters[ch][1].process(sampleFloat)
                    }

                    val clamped = (sampleFloat * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                    output.putShort(clamped)
                }
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            val sampleCount = remaining / 4
            val frameCount = sampleCount / channelCount

            for (f in 0 until frameCount) {
                for (ch in 0 until channelCount) {
                    val rawSample = inputBuffer.float
                    var sampleFloat = rawSample * vol

                    if (isFilteringEnabled && ch < lpfFilters.size) {
                        sampleFloat = lpfFilters[ch][0].process(sampleFloat)
                        sampleFloat = lpfFilters[ch][1].process(sampleFloat)
                    }

                    output.putFloat(sampleFloat.coerceIn(-1.0f, 1.0f))
                }
            }
        } else {
            output.put(inputBuffer)
        }

        output.flip()
    }

    override fun onFlush() {
        for (filters in lpfFilters) {
            for (f in filters) f.reset()
        }
        for (filters in hpfFilters) {
            for (f in filters) f.reset()
        }
    }

    override fun onReset() {
        onFlush()
        lpfFilters = emptyArray()
        hpfFilters = emptyArray()
        volumeFactor = 1.0f
        isFilteringEnabled = false
    }
}
