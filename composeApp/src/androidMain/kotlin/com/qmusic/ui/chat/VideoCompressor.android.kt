package com.qmusic.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Makes a video small enough to send quickly: 720p (the shorter side), H.264 at ~2.5 Mbit/s, AAC sound, on the
 * phone's hardware encoder (Media3 Transformer). A video already that small is sent as it is.
 */
internal object VideoCompressor {
    private const val TARGET_SHORT_SIDE = 720
    private const val TARGET_LONG_SIDE = 1280
    private const val TARGET_BITRATE = 2_500_000

    /** The smaller (and optionally clipped to [startMs]..[endMs]) copy in the cache, or null when not needed or failed. */
    suspend fun compress(
        context: Context,
        location: String,
        maxMs: Long = 0L,
        startMs: Long = 0L,
        endMs: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): File? {
        val uri = if (location.startsWith("/")) Uri.fromFile(File(location)) else Uri.parse(location)
        val (shortSide, bitrate, durationMs) = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    Triple(if (maxOf(w, h) > TARGET_LONG_SIDE) Int.MAX_VALUE else minOf(w, h), b, d)
                } finally {
                    runCatching { r.release() }
                }
            }.getOrDefault(Triple(0, 0, 0L))
        }
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(0L)
        val clipStart: Long
        val clipEnd: Long?
        if (safeStart == 0L && safeEnd == 0L) {
            clipStart = 0L
            clipEnd = if (maxMs > 0L && (durationMs <= 0L || durationMs > maxMs)) maxMs else null
        } else {
            if (durationMs > 0L && safeStart >= durationMs) return null
            if (safeEnd > 0L && safeEnd <= safeStart) return null
            clipStart = safeStart
            val windowEnd = when {
                safeEnd > 0L && maxMs > 0L -> minOf(safeEnd, safeStart + maxMs)
                safeEnd > 0L -> safeEnd
                maxMs > 0L -> safeStart + maxMs
                else -> 0L
            }
            val boundedEnd = if (windowEnd > 0L && durationMs > 0L) minOf(windowEnd, durationMs) else windowEnd
            clipEnd = if (boundedEnd > safeStart && (durationMs <= 0L || boundedEnd < durationMs)) boundedEnd else null
        }
        val needsClip = clipStart > 0L || clipEnd != null
        if (!needsClip && shortSide in 1..TARGET_SHORT_SIDE && bitrate in 1..(TARGET_BITRATE * 3 / 2)) return null
        val out = File(File(context.cacheDir, "video_out").apply { mkdirs() }, "v_${System.currentTimeMillis()}.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var poll: Runnable? = null
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(TARGET_BITRATE).build())
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            poll?.let { handler.removeCallbacks(it) }
                            if (cont.isActive) cont.resume(out)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            poll?.let { handler.removeCallbacks(it) }
                            out.delete()
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                    .build()
                // Fits inside 1280×1280: a landscape clip becomes 1280×720, an upright one 720×1280.
                val effects = if (shortSide > TARGET_SHORT_SIDE || shortSide == 0) {
                    Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(TARGET_LONG_SIDE, TARGET_LONG_SIDE, Presentation.LAYOUT_SCALE_TO_FIT)))
                } else {
                    Effects.EMPTY
                }
                // [startMs]..[endMs] (or [maxMs] from the start): clipped when shorter than the whole source.
                val media = if (needsClip) {
                    val clip = MediaItem.ClippingConfiguration.Builder().apply {
                        if (clipStart > 0L) setStartPositionMs(clipStart)
                        if (clipEnd != null) setEndPositionMs(clipEnd)
                    }.build()
                    MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
                } else {
                    MediaItem.fromUri(uri)
                }
                val item = EditedMediaItem.Builder(media).setEffects(effects).build()
                transformer.start(item, out.absolutePath)
                // How far it is, read from the transformer's own thread (the main one) while it works.
                val holder = ProgressHolder()
                val pollTask = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        handler.postDelayed(this, 300)
                    }
                }
                poll = pollTask
                handler.post(pollTask)
                cont.invokeOnCancellation {
                    handler.removeCallbacks(pollTask)
                    transformer.cancel()
                    out.delete()
                }
            }
        }
    }
}
