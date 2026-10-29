package com.qmusic.player

import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** [remainingMs] counts down to pause; with [atEndOfTrack] playback stops when the current song finishes instead. */
data class SleepTimerState(
    val active: Boolean = false,
    val remainingMs: Long = 0L,
    val atEndOfTrack: Boolean = false,
)

interface PlayerController {
    val currentTrack: StateFlow<SongItem?>
    val isPlaying: StateFlow<Boolean>
    val playbackPosition: StateFlow<Long>
    val duration: StateFlow<Long>

    /** How far the current song is loaded (ms): the lighter part of the seek bar, like YouTube Music's. */
    val bufferedPosition: StateFlow<Long>

    /** The songs that play after, and before, [currentTrack]. */
    val queue: StateFlow<List<SongItem>>

    /** Position of [currentTrack] inside [queue], or -1 when nothing is queued. */
    val queueIndex: StateFlow<Int>

    /** Set when a track could not be resolved or played; cleared as soon as another one starts. */
    val playbackError: StateFlow<String?>

    val shuffleEnabled: StateFlow<Boolean>
    val repeatMode: StateFlow<RepeatMode>
    val sleepTimer: StateFlow<SleepTimerState>

    /** True while songs that have a music video play it (picture and sound) instead of the audio alone. */
    val videoMode: StateFlow<Boolean>

    /** Plays a single song; the queue becomes just that song. */
    fun play(song: SongItem)

    /** Plays [song] on its own starting at [positionMs] (loaded there directly, not from the start and then moved). */
    fun playFrom(song: SongItem, positionMs: Long) {
        play(song)
        seekTo(positionMs)
    }

    /** Replaces the queue with [songs] and starts playing at [startIndex]. */
    fun playQueue(songs: List<SongItem>, startIndex: Int = 0)

    /** Adds [songs] to the end of the queue without interrupting playback. */
    fun appendToQueue(songs: List<SongItem>)

    /** Puts [song] right after the current one. */
    fun playNext(song: SongItem)

    /** Jumps to the song at [index] of [queue]. */
    fun skipTo(index: Int)

    fun removeFromQueue(index: Int)

    /** Moves the song at [from] to position [to] of the queue; the song that is playing keeps playing. */
    fun moveInQueue(from: Int, to: Int)

    /** Empties the queue but for the song that is playing. */
    fun clearQueue() = Unit

    /** Shows [songs] as the queue at [index] and [positionMs] without playing; the first play starts there. */
    fun restoreQueue(songs: List<SongItem>, index: Int, positionMs: Long) = Unit

    /** Playback speed (1 = normal), for podcasts and long listens. */
    fun setPlaybackSpeed(speed: Float) = Unit

    fun next()

    /** Restarts the current song when more than a few seconds in, otherwise goes to the previous one. */
    fun previous()

    /** Shuffles what plays after the current song; turning it off restores the original order. */
    fun setShuffle(enabled: Boolean)

    fun setRepeatMode(mode: RepeatMode)

    /** Plays the music video of songs that have one while [enabled]; the current song restarts at the same spot. */
    fun setVideoMode(enabled: Boolean)

    /** Pauses after [minutes]; 0 cancels the timer. */
    fun setSleepTimer(minutes: Int)

    /** Pauses when the current song ends. */
    fun setSleepAtEndOfTrack()

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)

    /** A hint that these songs are likely to be played next: their streams can be looked up and started early. */
    fun warmUp(songs: List<SongItem>) = Unit

    /** The stream quality for what is resolved from now on (Wi-Fi and mobile data are chosen by the caller). */
    fun setAudioQuality(quality: AudioQuality) = Unit

    /** Plays every song at the same perceived loudness, using the loudness YouTube measured for it. */
    fun setNormalizeVolume(enabled: Boolean) = Unit
    fun setVolume(volume: Float) = Unit
    fun setEqualizerBand(bandIndex: Int, gainDb: Float)
    fun setCrossfadeDuration(seconds: Int)
}
