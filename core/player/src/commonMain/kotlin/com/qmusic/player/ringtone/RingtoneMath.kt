package com.qmusic.player.ringtone

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/** The part of a song that becomes the ringtone, in milliseconds from the start of the song. */
data class ClipRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * The numbers behind a ringtone, kept apart from the audio code so they can be checked on their own: how the clip is
 * bounded, which stretch of a song is the best one to start with, and how the sound is faded and brought up.
 */
object RingtoneMath {
    /** Shortest and longest clip, and the length offered first (what phones usually play before voicemail picks up). */
    const val MIN_LENGTH_MS = 3_000L
    const val MAX_LENGTH_MS = 40_000L
    const val DEFAULT_LENGTH_MS = 30_000L

    /** The loudness picture of a song has one value for each stretch of this many milliseconds. */
    const val WINDOW_MS = 50

    /** How far the start may move to land on a quiet moment between phrases. */
    private const val SNAP_RADIUS_MS = 1_500

    /** Loudness aimed for when "louder" is on (RMS, about -12 dBFS) and the most a clip is ever raised (+12 dB). */
    private const val TARGET_RMS = 0.25
    private const val MAX_GAIN = 4.0

    /** Above this level the limiter starts to round peaks off instead of letting them clip. */
    private const val KNEE = 0.8f

    // ------------------------------------------------------------------ the clip

    /** The first clip offered for a song of [durationMs]: the whole song when it is short, else [DEFAULT_LENGTH_MS]. */
    fun defaultLength(durationMs: Long): Long = minOf(DEFAULT_LENGTH_MS, durationMs).coerceAtLeast(minOf(MIN_LENGTH_MS, durationMs))

    /** The start moved to [newStart]; the end follows when the length would go past the limits. */
    fun moveStart(range: ClipRange, newStart: Long, durationMs: Long): ClipRange {
        val minLength = minOf(MIN_LENGTH_MS, durationMs)
        val start = newStart.coerceIn(0L, (durationMs - minLength).coerceAtLeast(0L))
        var end = range.endMs
        if (end - start < minLength) end = start + minLength
        if (end - start > MAX_LENGTH_MS) end = start + MAX_LENGTH_MS
        return ClipRange(start, end.coerceAtMost(durationMs))
    }

    /** The end moved to [newEnd]; the start follows when the length would go past the limits. */
    fun moveEnd(range: ClipRange, newEnd: Long, durationMs: Long): ClipRange {
        val minLength = minOf(MIN_LENGTH_MS, durationMs)
        val end = newEnd.coerceIn(minLength, durationMs)
        var start = range.startMs
        if (end - start < minLength) start = end - minLength
        if (end - start > MAX_LENGTH_MS) start = end - MAX_LENGTH_MS
        return ClipRange(start.coerceAtLeast(0L), end)
    }

    /** The whole clip moved so it starts at [newStart], keeping its length. */
    fun moveClip(range: ClipRange, newStart: Long, durationMs: Long): ClipRange {
        val start = newStart.coerceIn(0L, (durationMs - range.lengthMs).coerceAtLeast(0L))
        return ClipRange(start, start + range.lengthMs)
    }

    // ------------------------------------------------------------------ finding the part to use

    /**
     * Where a clip of [lengthMs] should start: the stretch with the most energy (the chorus, most of the time),
     * pulled to the quietest moment close to it, so the cut falls between phrases and not in the middle of a word.
     * [energy] has one value per [WINDOW_MS].
     */
    fun suggestStart(energy: FloatArray, lengthMs: Long): Long {
        val length = (lengthMs / WINDOW_MS).toInt().coerceAtLeast(1)
        if (energy.size <= length) return 0L
        val sums = DoubleArray(energy.size + 1)
        for (i in energy.indices) sums[i + 1] = sums[i] + energy[i]
        var best = 0
        var bestSum = -1.0
        for (i in 0..energy.size - length) {
            val sum = sums[i + length] - sums[i]
            if (sum > bestSum) {
                bestSum = sum
                best = i
            }
        }
        val snapped = snapToQuiet(energy, best).coerceAtMost(energy.size - length)
        return snapped.toLong() * WINDOW_MS
    }

    /** The index near [index] (within [SNAP_RADIUS_MS]) where the song is quietest; [index] itself when it is flat. */
    fun snapToQuiet(energy: FloatArray, index: Int): Int {
        if (energy.isEmpty()) return index
        val radius = SNAP_RADIUS_MS / WINDOW_MS
        val from = (index - radius).coerceAtLeast(1)
        val to = (index + radius).coerceAtMost(energy.size - 2)
        if (from > to) return index.coerceIn(0, energy.size - 1)
        var best = index.coerceIn(from, to)
        var bestLevel = level(energy, best)
        for (i in from..to) {
            val l = level(energy, i)
            // Quieter wins; on a tie the one nearer the original spot.
            if (l < bestLevel - 1e-6f || (abs(l - bestLevel) <= 1e-6f && abs(i - index) < abs(best - index))) {
                best = i
                bestLevel = l
            }
        }
        return best
    }

    /** Three stretches averaged, so one gap between two beats does not look like a pause. */
    private fun level(energy: FloatArray, i: Int): Float = (energy[i - 1] + energy[i] + energy[i + 1]) / 3f

    /** [count] bars for drawing the song: the loudest of each stretch, scaled so the loudest bar is 1. */
    fun bars(energy: FloatArray, count: Int): FloatArray {
        if (energy.isEmpty() || count <= 0) return FloatArray(maxOf(count, 0))
        val out = FloatArray(count)
        for (b in 0 until count) {
            val from = (b.toLong() * energy.size / count).toInt()
            val to = ((b + 1).toLong() * energy.size / count).toInt().coerceAtLeast(from + 1).coerceAtMost(energy.size)
            var peak = 0f
            for (i in from until to) peak = maxOf(peak, energy[i])
            out[b] = peak
        }
        val max = out.max()
        if (max <= 0f) return out
        // A gentle curve, so quiet passages still show as something.
        for (b in out.indices) out[b] = (out[b] / max).toDouble().pow(0.6).toFloat()
        return out
    }

    // ------------------------------------------------------------------ the sound

    /** The volume (0..1) of frame [index] of a clip of [total] frames, with a fade in at the start and out at the end. */
    fun fadeGain(index: Long, total: Long, fadeInFrames: Long, fadeOutFrames: Long): Float {
        var gain = 1f
        if (fadeInFrames > 0 && index < fadeInFrames) gain *= smooth(index.toFloat() / fadeInFrames)
        val fromEnd = total - 1 - index
        if (fadeOutFrames > 0 && fromEnd < fadeOutFrames) gain *= smooth(fromEnd.toFloat() / fadeOutFrames)
        return gain
    }

    /** An S-shaped curve: no click at the start of the fade and no sudden stop at its end. */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** How much to raise a clip whose loudness is [rms] (0..1): up to the target, never down, never absurdly. */
    fun loudnessGain(rms: Double): Double {
        if (rms <= 1e-6) return 1.0
        return (TARGET_RMS / rms).coerceIn(1.0, MAX_GAIN)
    }

    /** Peaks above the knee are rounded off towards 1 instead of clipping, so a raised clip stays clean. */
    fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val limited = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -limited else limited
    }

    /** Loudness (RMS, 0..1) of interleaved 16-bit [samples]. */
    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    /**
     * Fades and raises [samples] (interleaved, [channels] wide) in place. The fades are [fadeInFrames] and
     * [fadeOutFrames] frames long; [gain] above 1 also turns the limiter on.
     */
    fun shape(samples: ShortArray, channels: Int, fadeInFrames: Long, fadeOutFrames: Long, gain: Double) {
        val frames = samples.size / channels
        val limit = gain > 1.0
        for (f in 0 until frames) {
            val fade = fadeGain(f.toLong(), frames.toLong(), fadeInFrames, fadeOutFrames)
            for (c in 0 until channels) {
                val i = f * channels + c
                var v = samples[i] / 32768f * fade * gain.toFloat()
                if (limit) v = softLimit(v)
                samples[i] = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }
}
