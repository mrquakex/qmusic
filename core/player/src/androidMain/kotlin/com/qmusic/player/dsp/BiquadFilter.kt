package com.qmusic.player.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Biquad filter implementation based on Robert Bristow-Johnson (RBJ) Audio EQ Cookbook.
 * Uses Direct Form I difference equation for numerical stability.
 */
class BiquadFilter(
    var b0: Float = 1f,
    var b1: Float = 0f,
    var b2: Float = 0f,
    var a1: Float = 0f,
    var a2: Float = 0f,
) {
    private var x1: Float = 0f
    private var x2: Float = 0f
    private var y1: Float = 0f
    private var y2: Float = 0f

    /**
     * Resets internal delay line states.
     */
    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    /**
     * Processes a single audio sample through Direct Form I difference equation:
     * y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
     */
    fun process(sample: Float): Float {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = out
        return out
    }

    /**
     * Calculates the magnitude response |H(e^jw)| of the filter at the given frequency.
     */
    fun getMagnitudeResponse(freq: Float, sampleRate: Float): Float {
        val w = 2.0 * PI * (freq.toDouble() / sampleRate.toDouble())
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = b0.toDouble() + b1.toDouble() * cosW + b2.toDouble() * cos2W
        val numIm = -(b1.toDouble() * sinW + b2.toDouble() * sin2W)
        val denRe = 1.0 + a1.toDouble() * cosW + a2.toDouble() * cos2W
        val denIm = -(a1.toDouble() * sinW + a2.toDouble() * sin2W)

        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = denRe * denRe + denIm * denIm
        return sqrt(numMagSq / denMagSq).toFloat()
    }

    /**
     * Copies filter coefficients from another filter without clearing state.
     */
    fun updateCoefficients(other: BiquadFilter) {
        this.b0 = other.b0
        this.b1 = other.b1
        this.b2 = other.b2
        this.a1 = other.a1
        this.a2 = other.a2
    }

    companion object {
        /**
         * Creates a Peaking EQ filter using RBJ Cookbook equations.
         *
         * @param sampleRate Sampling rate in Hz
         * @param frequency Center frequency f0 in Hz
         * @param gainDb Gain in dB (+/- gain)
         * @param q Filter quality factor (Q = 1.414 by default for 1 octave)
         */
        fun createPeaking(
            sampleRate: Float,
            frequency: Float,
            gainDb: Float,
            q: Float = 1.4142f,
        ): BiquadFilter {
            if (gainDb == 0f) {
                return BiquadFilter(1f, 0f, 0f, 0f, 0f)
            }

            val nyquist = sampleRate / 2.0f
            val f0 = frequency.coerceIn(10f, nyquist - 100f)

            val aLinear = 10.0.pow((gainDb / 40.0).toDouble())
            val w0 = 2.0 * PI * (f0 / sampleRate)
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0Raw = 1.0 + alpha * aLinear
            val b1Raw = -2.0 * cosW0
            val b2Raw = 1.0 - alpha * aLinear
            val a0Raw = 1.0 + alpha / aLinear
            val a1Raw = -2.0 * cosW0
            val a2Raw = 1.0 - alpha / aLinear

            val b0 = (b0Raw / a0Raw).toFloat()
            val b1 = (b1Raw / a0Raw).toFloat()
            val b2 = (b2Raw / a0Raw).toFloat()
            val a1 = (a1Raw / a0Raw).toFloat()
            val a2 = (a2Raw / a0Raw).toFloat()

            return BiquadFilter(b0, b1, b2, a1, a2)
        }

        /**
         * Creates a Low-Pass Filter (LPF) using RBJ Cookbook equations.
         */
        fun createLowPass(
            sampleRate: Float,
            cutoffFreq: Float,
            q: Float = 0.7071f,
        ): BiquadFilter {
            val nyquist = sampleRate / 2.0f
            val f0 = cutoffFreq.coerceIn(10f, nyquist - 100f)
            val w0 = 2.0 * PI * (f0 / sampleRate)
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0Raw = (1.0 - cosW0) / 2.0
            val b1Raw = 1.0 - cosW0
            val b2Raw = (1.0 - cosW0) / 2.0
            val a0Raw = 1.0 + alpha
            val a1Raw = -2.0 * cosW0
            val a2Raw = 1.0 - alpha

            return BiquadFilter(
                (b0Raw / a0Raw).toFloat(),
                (b1Raw / a0Raw).toFloat(),
                (b2Raw / a0Raw).toFloat(),
                (a1Raw / a0Raw).toFloat(),
                (a2Raw / a0Raw).toFloat(),
            )
        }

        /**
         * Creates a High-Pass Filter (HPF) using RBJ Cookbook equations.
         */
        fun createHighPass(
            sampleRate: Float,
            cutoffFreq: Float,
            q: Float = 0.7071f,
        ): BiquadFilter {
            val nyquist = sampleRate / 2.0f
            val f0 = cutoffFreq.coerceIn(10f, nyquist - 100f)
            val w0 = 2.0 * PI * (f0 / sampleRate)
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0Raw = (1.0 + cosW0) / 2.0
            val b1Raw = -(1.0 + cosW0)
            val b2Raw = (1.0 + cosW0) / 2.0
            val a0Raw = 1.0 + alpha
            val a1Raw = -2.0 * cosW0
            val a2Raw = 1.0 - alpha

            return BiquadFilter(
                (b0Raw / a0Raw).toFloat(),
                (b1Raw / a0Raw).toFloat(),
                (b2Raw / a0Raw).toFloat(),
                (a1Raw / a0Raw).toFloat(),
                (a2Raw / a0Raw).toFloat(),
            )
        }
    }
}
