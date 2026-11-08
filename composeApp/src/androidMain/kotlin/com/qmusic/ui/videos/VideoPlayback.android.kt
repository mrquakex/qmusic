package com.qmusic.ui.videos

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video section's own ExoPlayer, with the audio focus of a video (it takes the sound from the music). */
@androidx.media3.common.util.UnstableApi
internal class ExoVideoPlayback(context: Context) : VideoPlayback {
    private val appContext = context.applicationContext
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        // Starts after under a second of picture, keeps up to a minute and a half ahead, and keeps the last fifteen
        // seconds so a short seek back needs nothing new.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 90_000, 800, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(15_000, true)
                .build(),
        )
        .build()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _ended = MutableStateFlow(false)
    override val ended: StateFlow<Boolean> = _ended.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _rebuffers = MutableStateFlow(0)
    override val rebuffers: StateFlow<Int> = _rebuffers.asStateFlow()

    /** Playing had started since the last load, and when the last seek was: a wait after either is not a stall. */
    private var wasReady = false
    private var lastSeekAt = 0L

    /** What was loaded last: the quality, where it started, and whether it carried subtitles. */
    private var lastLoad: Triple<VideoQuality, Long, Boolean>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            _position.value = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration
            _duration.value = if (duration == C.TIME_UNSET) 0L else duration
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Videos come through the cache, in bounded pieces (full speed); live streams only in pieces. */
    private val dataSources = VideoStreaming.cached(appContext)
    private val liveSources = VideoStreaming.network()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_BUFFERING && wasReady && player.playWhenReady &&
                    android.os.SystemClock.elapsedRealtime() - lastSeekAt > SEEK_GRACE_MS
                ) {
                    _rebuffers.value++
                }
                if (state == Player.STATE_READY) wasReady = true
                _ended.value = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) _error.value = null
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    lastSeekAt = android.os.SystemClock.elapsedRealtime()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A subtitle track that cannot be read must not take the video with it: play again without it.
                val last = lastLoad
                if (last != null && last.third) {
                    lastLoad = null
                    load(last.first, player.currentPosition, true, null)
                    return
                }
                _error.value = error.errorCodeName
            }
        })
        handler.post(ticker)
    }

    override fun load(quality: VideoQuality, startMs: Long, autoPlay: Boolean, caption: VideoCaption?) {
        _error.value = null
        _ended.value = false
        wasReady = false
        // Subtitles go in the picture's own media item: the player then converts them the way its text renderer needs
        // (a hand-built subtitle source is refused with a runtime error).
        val track = caption?.takeIf { !quality.isLive }?.let {
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url))
                .setMimeType(it.mimeType)
                .setLanguage(it.language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val pictureItem = MediaItem.Builder().setUri(quality.videoUrl).apply { if (track != null) setSubtitleConfigurations(listOf(track)) }.build()
        val picture: MediaSource = DefaultMediaSourceFactory(if (quality.isLive) liveSources else dataSources).createMediaSource(pictureItem)
        val source: MediaSource = if (quality.audioUrl != null && !quality.isLive) {
            MergingMediaSource(picture, ProgressiveMediaSource.Factory(dataSources).createMediaSource(MediaItem.fromUri(quality.audioUrl!!)))
        } else {
            picture
        }
        lastLoad = Triple(quality, startMs, track != null)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .build()
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    override fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun seekTo(ms: Long) {
        lastSeekAt = android.os.SystemClock.elapsedRealtime()
        player.seekTo(ms.coerceAtLeast(0))
        _position.value = ms.coerceAtLeast(0)
    }

    override fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    override fun setLooping(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun preload(quality: VideoQuality) = VideoStreaming.preload(appContext, quality)

    override fun release() {
        handler.removeCallbacks(ticker)
        player.release()
    }

    private companion object {
        const val TICK_MS = 400L
        const val SEEK_GRACE_MS = 2_500L
    }
}

@Composable
actual fun rememberVideoPlayback(): VideoPlayback? {
    val context = LocalContext.current
    val playback = remember { ExoVideoPlayback(context) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}

@Composable
actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier, fill: Boolean) {
    val exo = (playback as? ExoVideoPlayback)?.player
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // The screen stays on while a video plays.
                keepScreenOn = true
                player = exo
            }
        },
        update = {
            it.player = exo
            it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { it.player = null },
    )
}
