package com.qmusic.player

import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fallback implementation of [PlayerController]: it follows the queue rules but plays nothing.
 */
class NoOpPlayerController : PlayerController {
    private val _currentTrack = MutableStateFlow<SongItem?>(null)
    override val currentTrack: StateFlow<SongItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    override val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    override val bufferedPosition: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    override val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    override val playbackError: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _sleep = MutableStateFlow(SleepTimerState())
    override val sleepTimer: StateFlow<SleepTimerState> = _sleep.asStateFlow()

    private val _videoMode = MutableStateFlow(false)
    override val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    override fun setVideoMode(enabled: Boolean) {
        _videoMode.value = enabled
    }

    var crossfadeSeconds: Int = 3
        private set

    val equalizerGains: FloatArray = FloatArray(10) { 0f }

    override fun play(song: SongItem) = playQueue(listOf(song), 0)

    override fun playQueue(songs: List<SongItem>, startIndex: Int) {
        if (songs.isEmpty()) return
        _queue.value = songs
        start(startIndex.coerceIn(songs.indices))
    }

    override fun appendToQueue(songs: List<SongItem>) {
        val known = _queue.value.mapTo(HashSet()) { it.id }
        _queue.value = _queue.value + songs.filter { known.add(it.id) }
    }

    override fun playNext(song: SongItem) {
        val index = _queueIndex.value
        if (index < 0) return play(song)
        val without = _queue.value.filterIndexed { i, s -> i == index || s.id != song.id }
        val at = without.indexOfFirst { it.id == _currentTrack.value?.id } + 1
        _queue.value = without.toMutableList().apply { add(at, song) }
        _queueIndex.value = without.indexOfFirst { it.id == _currentTrack.value?.id }
    }

    override fun skipTo(index: Int) {
        if (index in _queue.value.indices) start(index)
    }

    override fun clearQueue() {
        val current = _queue.value.getOrNull(_queueIndex.value) ?: return
        _queue.value = listOf(current)
        _queueIndex.value = 0
    }

    override fun moveInQueue(from: Int, to: Int) {
        val queue = _queue.value
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val moved = queue.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = moved
        _queueIndex.value = movedIndex(_queueIndex.value, from, to)
    }

    override fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return
        val current = _queueIndex.value
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        when {
            index < current -> _queueIndex.value = current - 1
            index == current -> if (index < _queue.value.size) start(index) else _isPlaying.value = false
        }
    }

    override fun next() {
        val index = _queueIndex.value + 1
        when {
            index < _queue.value.size -> start(index)
            _repeat.value == RepeatMode.ALL && _queue.value.isNotEmpty() -> start(0)
        }
    }

    override fun previous() {
        if (_playbackPosition.value > RESTART_THRESHOLD_MS || _queueIndex.value <= 0) {
            _playbackPosition.value = 0L
        } else {
            start(_queueIndex.value - 1)
        }
    }

    override fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _repeat.value = mode
    }

    override fun setSleepTimer(minutes: Int) {
        _sleep.value = if (minutes <= 0) SleepTimerState() else SleepTimerState(true, minutes * 60_000L)
    }

    override fun setSleepAtEndOfTrack() {
        _sleep.value = SleepTimerState(active = true, atEndOfTrack = true)
    }

    private fun start(index: Int) {
        val song = _queue.value[index]
        _queueIndex.value = index
        _currentTrack.value = song
        _isPlaying.value = true
        _duration.value = (song.duration?.toLong() ?: 180L) * 1000L
        _playbackPosition.value = 0L
    }

    override fun pause() {
        _isPlaying.value = false
    }

    override fun resume() {
        _isPlaying.value = true
    }

    override fun seekTo(positionMs: Long) {
        _playbackPosition.value = positionMs
    }

    override fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in equalizerGains.indices) {
            equalizerGains[bandIndex] = gainDb
        }
    }

    override fun setCrossfadeDuration(seconds: Int) {
        crossfadeSeconds = seconds
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
