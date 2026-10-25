package com.qmusic.player.ringtone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/** What reading a song's sound told: how long it is and how loud each [RingtoneMath.WINDOW_MS] of it is (0..1). */
class AudioShape(val durationMs: Long, val energy: FloatArray, val sampleRate: Int, val channels: Int)

/** A block of interleaved 16-bit sound and the time (microseconds) its first frame sits at in the song. */
internal class PcmBlock(val samples: ShortArray, val ptsUs: Long)

/** The song was asked to stop: used to leave the long loops below cleanly. */
class RingtoneCancelled : IOException("Cancelled")

/**
 * Reads a song file (AAC in mp4, or Opus in webm: whatever YouTube gave) into sound, finds how loud it is along its
 * length, and writes a trimmed, faded copy as AAC in an m4a file, which every phone plays as a ringtone.
 * Everything here blocks: call it away from the main thread.
 */
object RingtoneCodec {

    /** Reads the whole song once: its length and its loudness over time (for drawing it and picking the best part). */
    fun analyze(file: File, isActive: () -> Boolean = { true }, onProgress: (Float) -> Unit = {}): AudioShape {
        val startedAt = System.nanoTime()
        PcmReader(file).use { reader ->
            val energy = FloatList()
            var frames = 0L
            var sum = 0L
            var taken = 0
            var position = 0
            val totalUs = reader.durationUs
            while (true) {
                if (!isActive()) throw RingtoneCancelled()
                val block = reader.next() ?: break
                val channels = reader.channels
                val samples = block.samples
                val count = samples.size / channels
                val windowFrames = reader.sampleRate * RingtoneMath.WINDOW_MS / 1000
                // Every few frames is plenty for a loudness picture (a window holds thousands), and it keeps the
                // loop light: this runs over the whole song.
                var f = 0
                while (f < count) {
                    val mixed = when (channels) {
                        2 -> (samples[f * 2] + samples[f * 2 + 1]) shr 1
                        1 -> samples[f].toInt()
                        else -> mixDown(samples, f, channels)
                    }
                    sum += (mixed * mixed).toLong()
                    taken++
                    f += ANALYSIS_STRIDE
                    position += ANALYSIS_STRIDE
                    if (position >= windowFrames) {
                        energy.add((sqrt(sum.toDouble() / taken) / 32768.0).toFloat())
                        sum = 0L
                        taken = 0
                        position = 0
                    }
                }
                frames += count
                if (totalUs > 0) onProgress((block.ptsUs.toFloat() / totalUs).coerceIn(0f, 1f))
            }
            if (taken > 0) energy.add((sqrt(sum.toDouble() / taken) / 32768.0).toFloat())
            check(frames > 0) { "No sound in this file" }
            Log.d(TAG, "Read ${frames / reader.sampleRate} s of sound in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            return AudioShape(frames * 1000 / reader.sampleRate, energy.toArray(), reader.sampleRate, reader.channels)
        }
    }

    /** One frame of more than two channels, mixed to one. */
    private fun mixDown(samples: ShortArray, frame: Int, channels: Int): Int {
        var mixed = 0
        for (c in 0 until channels) mixed += samples[frame * channels + c]
        return mixed / channels
    }

    /**
     * Cuts [range] out of the song, fades its ends ([fadeInMs], [fadeOutMs]), brings it up when [louder], and writes
     * it to [output] (an .m4a). The song is read from the nearest point before the start, not from the beginning.
     */
    fun export(
        file: File,
        range: ClipRange,
        fadeInMs: Long,
        fadeOutMs: Long,
        louder: Boolean,
        output: File,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ) {
        val startedAt = System.nanoTime()
        val startUs = range.startMs * 1000
        val endUs = range.endMs * 1000
        val clip = ShortList()
        var sampleRate = 0
        var channels = 0
        PcmReader(file, startUs).use { reader ->
            while (true) {
                if (!isActive()) throw RingtoneCancelled()
                val block = reader.next() ?: break
                sampleRate = reader.sampleRate
                channels = reader.channels
                val frames = block.samples.size / channels
                val blockEndUs = block.ptsUs + frames * 1_000_000L / sampleRate
                if (blockEndUs <= startUs) continue
                if (block.ptsUs >= endUs) break
                // The part of this block that lies inside the range.
                val skip = if (block.ptsUs < startUs) ((startUs - block.ptsUs) * sampleRate / 1_000_000L).toInt() else 0
                val last = min(frames.toLong(), (endUs - block.ptsUs) * sampleRate / 1_000_000L).toInt()
                val keep = last - skip
                if (keep > 0) clip.addAll(block.samples, skip * channels, keep * channels)
                onProgress(READ_SHARE * ((block.ptsUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                if (blockEndUs >= endUs) break
            }
        }
        check(clip.size > 0 && sampleRate > 0) { "Nothing to cut" }
        val cutAt = System.nanoTime()

        var samples = clip.toArray()
        if (channels > 2) {
            // The first two channels are left and right; the rest (centre, surround) are not needed on a phone.
            samples = ShortArray(samples.size / channels * 2) { i -> samples[i / 2 * channels + i % 2] }
            channels = 2
        }
        val frames = samples.size / channels
        val gain = if (louder) RingtoneMath.loudnessGain(RingtoneMath.rms(samples)) else 1.0
        RingtoneMath.shape(
            samples = samples,
            channels = channels,
            fadeInFrames = fadeInMs * sampleRate / 1000,
            fadeOutFrames = fadeOutMs * sampleRate / 1000,
            gain = gain,
        )
        check(frames > 0)
        val shapedAt = System.nanoTime()
        encode(samples, sampleRate, channels, output, isActive) { onProgress(READ_SHARE + (1f - READ_SHARE) * it) }
        Log.d(
            TAG,
            "Ringtone: cut ${(cutAt - startedAt) / 1_000_000} ms, shaped ${(shapedAt - cutAt) / 1_000_000} ms, " +
                "encoded ${(System.nanoTime() - shapedAt) / 1_000_000} ms (${clip.size / channels / sampleRate} s, gain $gain)",
        )
    }

    /** Writes [pcm] (interleaved 16-bit) as AAC-LC in an mp4 container. */
    private fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, output: File, isActive: () -> Boolean, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var track = -1
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!isActive()) throw RingtoneCancelled()
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        buffer.clear()
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val room = buffer.capacity() / 2 / channels * channels
                        val count = min(pcm.size - offset, room)
                        val ptsUs = offset / channels * 1_000_000L / sampleRate
                        if (count <= 0) {
                            encoder.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.asShortBuffer().put(pcm, offset, count)
                            encoder.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
                            offset += count
                            onProgress(offset.toFloat() / pcm.size)
                        }
                    }
                }
                val out = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    out >= 0 -> {
                        idle = 0
                        val buffer = encoder.getOutputBuffer(out)!!
                        // The codec's set-up data is already in the format given to the muxer.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        encoder.releaseOutputBuffer(out, false)
                    }

                    else -> if (inputDone && ++idle > STALL_LIMIT) throw IOException("The encoder stopped answering")
                }
            }
            check(muxerStarted) { "Nothing was encoded" }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private const val TAG = "QMRingtone"
    private const val TIMEOUT_US = 10_000L
    private const val STALL_LIMIT = 500

    /** Which frames the loudness picture looks at: one in this many. */
    private const val ANALYSIS_STRIDE = 4

    /** The share of the export's progress that is reading and cutting (the rest is encoding). */
    private const val READ_SHARE = 0.4f

    /** A song's sound as MediaCodec gives it: blocks of 16-bit samples in order, from [startUs] on. */
    internal class PcmReader(file: File, startUs: Long = 0L) : AutoCloseable {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private var floatSamples = false
        private var idle = 0

        var sampleRate: Int
            private set
        var channels: Int
            private set
        val durationUs: Long

        init {
            var created: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IOException("No sound in this file")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                created = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                created.configure(format, null, null, 0)
                created.start()
                codec = created
            } catch (failure: Throwable) {
                runCatching { created?.release() }
                runCatching { extractor.release() }
                throw failure
            }
        }

        /** The next block of sound, or null once the song has ended. */
        fun next(): PcmBlock? {
            while (!outputDone) {
                // Keep the decoder busy: as many packets as it will take, without waiting for the sound of each.
                while (!inputDone && feed()) Unit
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        idle = 0
                        val block = if (info.size > 0) read(codec.getOutputBuffer(index)!!) else null
                        val pts = info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(index, false)
                        if (block != null) return PcmBlock(block, pts)
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatSamples = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }

                    else -> if (inputDone && ++idle > STALL_LIMIT) throw IOException("The decoder stopped answering")
                }
            }
            return null
        }

        /** Hands the decoder the next packet; false when it has no room for one right now. */
        private fun feed(): Boolean {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return false
            val buffer = codec.getInputBuffer(index)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            return true
        }

        private fun read(buffer: java.nio.ByteBuffer): ShortArray {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.order(ByteOrder.nativeOrder())
            if (floatSamples) {
                val floats = buffer.asFloatBuffer()
                return ShortArray(floats.remaining()) { (floats.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            }
            val shorts = buffer.asShortBuffer()
            return ShortArray(shorts.remaining()).also { shorts.get(it) }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    /** A growing list of floats without boxing each one. */
    private class FloatList {
        private var data = FloatArray(4096)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** A growing list of shorts without boxing each one. */
    private class ShortList {
        private var data = ShortArray(1 shl 18)
        var size = 0
            private set

        fun addAll(source: ShortArray, from: Int, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
            System.arraycopy(source, from, data, size, count)
            size += count
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
