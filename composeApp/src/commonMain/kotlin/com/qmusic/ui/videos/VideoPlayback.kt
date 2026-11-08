package com.qmusic.ui.videos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.StateFlow

/** The player of the Videos section: one picture (and its sound) at a time, separate from the music player. */
interface VideoPlayback {
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val buffering: StateFlow<Boolean>
    val ended: StateFlow<Boolean>

    /** Why it cannot play (null while it can). */
    val error: StateFlow<String?>

    /** Plays [quality] from [startMs]; picture and sound are merged when they are separate streams. */
    fun load(quality: VideoQuality, startMs: Long = 0, autoPlay: Boolean = true)
    fun togglePlay()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun release()
}

/** A player that lives as long as the page that remembers it; null where this device cannot play video. */
@Composable
expect fun rememberVideoPlayback(): VideoPlayback?

/** Draws what [playback] plays. */
@Composable
expect fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier)
