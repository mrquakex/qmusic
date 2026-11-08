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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** The video section's own ExoPlayer, with the audio focus of a video (it takes the sound from the music). */
internal class ExoVideoPlayback(context: Context) : VideoPlayback {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
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

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            _position.value = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration
            _duration.value = if (duration == C.TIME_UNSET) 0L else duration
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val dataSources = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                _ended.value = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) _error.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                _error.value = error.errorCodeName
            }
        })
        handler.post(ticker)
    }

    override fun load(quality: VideoQuality, startMs: Long, autoPlay: Boolean) {
        _error.value = null
        _ended.value = false
        val source: MediaSource = if (quality.audioUrl != null && !quality.isLive) {
            MergingMediaSource(
                ProgressiveMediaSource.Factory(dataSources).createMediaSource(MediaItem.fromUri(quality.videoUrl)),
                ProgressiveMediaSource.Factory(dataSources).createMediaSource(MediaItem.fromUri(quality.audioUrl!!)),
            )
        } else {
            DefaultMediaSourceFactory(dataSources).createMediaSource(MediaItem.fromUri(quality.videoUrl))
        }
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
        player.seekTo(ms.coerceAtLeast(0))
        _position.value = ms.coerceAtLeast(0)
    }

    override fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    override fun release() {
        handler.removeCallbacks(ticker)
        player.release()
    }

    private companion object {
        const val TICK_MS = 400L
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
actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier) {
    val exo = (playback as? ExoVideoPlayback)?.player
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = exo
            }
        },
        update = { it.player = exo },
        onRelease = { it.player = null },
    )
}
