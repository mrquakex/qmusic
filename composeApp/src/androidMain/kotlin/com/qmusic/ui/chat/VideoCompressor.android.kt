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

    /** The smaller copy (in the cache), or null when it is not needed or could not be made. */
    suspend fun compress(context: Context, location: String, onProgress: (Float) -> Unit): File? {
        val uri = if (location.startsWith("/")) Uri.fromFile(File(location)) else Uri.parse(location)
        val (shortSide, bitrate) = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    (if (maxOf(w, h) > TARGET_LONG_SIDE) Int.MAX_VALUE else minOf(w, h)) to b
                } finally {
                    runCatching { r.release() }
                }
            }.getOrDefault(0 to 0)
        }
        if (shortSide in 1..TARGET_SHORT_SIDE && bitrate in 1..(TARGET_BITRATE * 3 / 2)) return null
        val out = File(File(context.cacheDir, "video_out").apply { mkdirs() }, "v_${System.currentTimeMillis()}.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
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
                            if (cont.isActive) cont.resume(out)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
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
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
                transformer.start(item, out.absolutePath)
                // How far it is, read from the transformer's own thread (the main one) while it works.
                val holder = ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        handler.postDelayed(this, 300)
                    }
                }
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    out.delete()
                }
            }
        }
    }
}
