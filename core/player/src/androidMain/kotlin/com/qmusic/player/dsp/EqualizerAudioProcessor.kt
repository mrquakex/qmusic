package com.qmusic.player.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow

/**
 * 10-Band Software Equalizer AudioProcessor for Media3.
 * Uses RBJ peaking biquad filters with preamp gain compensation to prevent digital clipping.
 */
class EqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val BAND_COUNT = 10
    }

    private val bandGains = FloatArray(BAND_COUNT) { 0f }
    var isEnabled: Boolean = true

    // Multi-channel filters: channelIndex -> Array of 10 BiquadFilters
    private var channelFilters: Array<Array<BiquadFilter>> = emptyArray()

    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            bandGains[bandIndex] = gainDb.coerceIn(-15f, 15f)
            updateFilters()
        }
    }

    @Synchronized
    fun getBandGain(bandIndex: Int): Float {
        return if (bandIndex in 0 until BAND_COUNT) bandGains[bandIndex] else 0f
    }

    @Synchronized
    fun getPreampGainDb(): Float {
        val maxGain = bandGains.maxOrNull() ?: 0f
        return if (maxGain > 0f) -maxGain else 0f
    }

    @Synchronized
    private fun getPreampLinear(): Float {
        val gainDb = getPreampGainDb()
        return 10.0.pow((gainDb / 20.0).toDouble()).toFloat()
    }

    @Synchronized
    private fun updateFilters() {
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

        if (channelFilters.size != channelCount) {
            channelFilters = Array(channelCount) {
                Array(BAND_COUNT) { bandIdx ->
                    BiquadFilter.createPeaking(
                        sampleRate = sampleRate,
                        frequency = BAND_FREQUENCIES[bandIdx].toFloat(),
                        gainDb = bandGains[bandIdx],
                    )
                }
            }
        } else {
            for (ch in 0 until channelCount) {
                for (b in 0 until BAND_COUNT) {
                    val updated = BiquadFilter.createPeaking(
                        sampleRate = sampleRate,
                        frequency = BAND_FREQUENCIES[b].toFloat(),
                        gainDb = bandGains[b],
                    )
                    channelFilters[ch][b].updateCoefficients(updated)
                }
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        updateFilters()
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

        // If disabled or all gains flat and no preamp needed, fast passthrough
        val hasEffect = isEnabled && (bandGains.any { it != 0f } || getPreampGainDb() != 0f)
        if (!hasEffect || channelFilters.size != channelCount) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val preamp = getPreampLinear()

        if (encoding == C.ENCODING_PCM_16BIT) {
            val sampleCount = remaining / 2
            val frameCount = sampleCount / channelCount

            for (f in 0 until frameCount) {
                for (ch in 0 until channelCount) {
                    val rawSample = inputBuffer.short
                    var sampleFloat = (rawSample.toFloat() / 32768f) * preamp

                    val filters = channelFilters[ch]
                    for (b in 0 until BAND_COUNT) {
                        sampleFloat = filters[b].process(sampleFloat)
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
                    var sampleFloat = rawSample * preamp

                    val filters = channelFilters[ch]
                    for (b in 0 until BAND_COUNT) {
                        sampleFloat = filters[b].process(sampleFloat)
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
        for (filters in channelFilters) {
            for (f in filters) {
                f.reset()
            }
        }
    }

    override fun onReset() {
        onFlush()
        channelFilters = emptyArray()
    }
}
