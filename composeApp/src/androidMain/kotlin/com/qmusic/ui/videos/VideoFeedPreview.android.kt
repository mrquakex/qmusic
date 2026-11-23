package com.qmusic.ui.videos

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * The list's silent preview player: one small ExoPlayer of its own, never with sound and never taking the audio
 * focus (music playing goes on), reading the light (360p at most) picture through the same cache as the videos.
 * A preview plays [PREVIEW_MS] at most, then stops, as YouTube's does.
 */
@androidx.media3.common.util.UnstableApi
internal object FeedPreviewPlayer {
    private var player: ExoPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeUp = Runnable { FeedPreview.stopAll() }
    private const val PREVIEW_MS = 30_000L

    /** Twice a second while it plays: the line and the time left on the card. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val length = p.duration
            if (length > 0) {
                FeedPreview.progress.value = (p.currentPosition.toFloat() / length).coerceIn(0f, 1f)
                FeedPreview.remainingMs.value = (length - p.currentPosition).coerceAtLeast(0)
            }
            if (FeedPreview.playing.value != null) handler.postDelayed(this, 500)
        }
    }

    private val mediaAttributes = androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build()

    /** Silent: it takes nothing from the music. With sound: it takes the sound as a video does (the music pauses). */
    private fun applyMuted(p: ExoPlayer, muted: Boolean) {
        p.volume = if (muted) 0f else 1f
        p.setAudioAttributes(mediaAttributes, /* handleAudioFocus = */ !muted)
    }

    fun get(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .also { p ->
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    FeedPreview.showing.value = true
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    FeedPreview.stopAll()
                }
            })
            player = p
        }

    fun install(context: Context) {
        val app = context.applicationContext
        FeedPreview.play = { quality ->
            val p = get(app)
            FeedPreview.showing.value = false
            FeedPreview.progress.value = 0f
            FeedPreview.remainingMs.value = 0L
            p.setMediaSource(ProgressiveMediaSource.Factory(VideoStreaming.cached(app)).createMediaSource(MediaItem.fromUri(quality.videoUrl)))
            applyMuted(p, FeedPreview.muted.value)
            p.prepare()
            p.playWhenReady = true
            handler.removeCallbacks(timeUp)
            handler.removeCallbacks(tick)
            // With its sound on it is being watched: no 30-second stop.
            if (FeedPreview.muted.value) handler.postDelayed(timeUp, PREVIEW_MS)
            handler.post(tick)
        }
        FeedPreview.stop = {
            handler.removeCallbacks(timeUp)
            handler.removeCallbacks(tick)
            player?.let {
                it.stop()
                it.clearMediaItems()
                // Its sound let go: the music goes on.
                applyMuted(it, true)
            }
        }
        FeedPreview.position = { player?.currentPosition ?: 0L }
        FeedPreview.applyMuted = { muted ->
            player?.let { p ->
                applyMuted(p, muted)
                handler.removeCallbacks(timeUp)
                if (muted && FeedPreview.playing.value != null) handler.postDelayed(timeUp, PREVIEW_MS)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun FeedPreviewSurface(modifier: Modifier) {
    // The app put away: the preview stops (no picture to watch, no data to spend).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) FeedPreview.stopAll()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = FeedPreviewPlayer.get(context)
            }
        },
        onRelease = { it.player = null },
    )
}
