package com.qmusic.player

import com.qmusic.engine.YouTube
import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.SpotifyIds
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-featured Desktop [PlayerController] powered by JavaFX [MediaPlayer] and [DesktopDownloadController].
 *
 * Supports AAC (`audio/mp4`) streaming & caching, crossfade between two [MediaPlayer] instances,
 * 10-band equalizer, loudness normalization, playback speed, sleep timer, shuffle, repeat, and pre-warming.
 */
class DesktopPlayerController(
    private val youTube: YouTube,
    private val spotifyMatcher: suspend (SongItem) -> String? = { null },
    baseDir: File = File(System.getProperty("user.home"), ".qmusic"),
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val matchedSpotifyIds = ConcurrentHashMap<String, String>()
    private val knownSongs = ConcurrentHashMap<String, SongItem>()
    private val urlCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var audioQuality: AudioQuality = AudioQuality.HIGH

    val downloads = DesktopDownloadController(
        baseDir = baseDir,
        resolveUrl = { id -> resolveAudioUrl(id, audioQuality) },
        onStaleUrl = { id -> invalidateCacheFor(id) },
    )

    private val _currentTrack = MutableStateFlow<SongItem?>(null)
    override val currentTrack: StateFlow<SongItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    override val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    override val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    override val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    override val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    override val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _videoMode = MutableStateFlow(false)
    override val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    private var originalQueue: List<SongItem> = emptyList()
    private var restoredPendingPlay = false
    private var restoredPositionMs = 0L

    @Volatile
    private var activePlayer: MediaPlayer? = null

    @Volatile
    private var fadingPlayer: MediaPlayer? = null

    private var playJob: Job? = null
    private var fadeJob: Job? = null
    private var sleepJob: Job? = null
    private var crossfadeTriggeredForTrackId: String? = null

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var crossfadeSeconds: Int = 3

    @Volatile
    private var normalizeVolume: Boolean = true

    @Volatile
    private var currentTrackGain: Double = 1.0

    private val equalizerGains = FloatArray(10) { 0f }

    init {
        ensureJavaFxInitialized()
        startPositionTicker()
    }

    private fun rememberSongs(songs: List<SongItem>) {
        songs.forEach { knownSongs[it.id] = it }
    }

    override fun play(song: SongItem) {
        rememberSongs(listOf(song))
        originalQueue = listOf(song)
        _queue.value = listOf(song)
        playAt(0, startPositionMs = 0L, useCrossfade = false)
    }

    override fun playFrom(song: SongItem, positionMs: Long) {
        rememberSongs(listOf(song))
        originalQueue = listOf(song)
        _queue.value = listOf(song)
        playAt(0, startPositionMs = positionMs, useCrossfade = false)
    }

    override fun playQueue(songs: List<SongItem>, startIndex: Int) {
        if (songs.isEmpty()) return
        rememberSongs(songs)
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        originalQueue = songs
        if (_shuffleEnabled.value && songs.size > 1) {
            val first = songs[safeIndex]
            val rest = songs.filterIndexed { i, _ -> i != safeIndex }.shuffled()
            _queue.value = listOf(first) + rest
            playAt(0, startPositionMs = 0L, useCrossfade = false)
        } else {
            _queue.value = songs
            playAt(safeIndex, startPositionMs = 0L, useCrossfade = false)
        }
    }

    override fun appendToQueue(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        rememberSongs(songs)
        val existingIds = _queue.value.mapTo(HashSet()) { it.id }
        val fresh = songs.filter { it.id !in existingIds }
        if (fresh.isEmpty()) return
        originalQueue = originalQueue + fresh
        _queue.value = _queue.value + fresh
        if (_queueIndex.value == _queue.value.size - fresh.size - 1) {
            warmUp(listOf(fresh.first()))
        }
    }

    override fun playNext(song: SongItem) {
        rememberSongs(listOf(song))
        val q = _queue.value.toMutableList()
        val idx = _queueIndex.value
        if (q.isEmpty() || idx < 0) {
            play(song)
            return
        }
        val insertPos = (idx + 1).coerceAtMost(q.size)
        q.add(insertPos, song)
        _queue.value = q
        originalQueue = q.toList()
        warmUp(listOf(song))
    }

    override fun skipTo(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        playAt(index, startPositionMs = 0L, useCrossfade = false)
    }

    override fun removeFromQueue(index: Int) {
        val q = _queue.value.toMutableList()
        if (index !in q.indices) return
        val cur = _queueIndex.value
        q.removeAt(index)
        _queue.value = q
        originalQueue = q.toList()
        when {
            q.isEmpty() -> {
                stopAllPlayers()
                _currentTrack.value = null
                _queueIndex.value = -1
                _isPlaying.value = false
            }
            index < cur -> _queueIndex.value = cur - 1
            index == cur -> {
                val nextIdx = cur.coerceAtMost(q.lastIndex)
                playAt(nextIdx, startPositionMs = 0L, useCrossfade = false)
            }
        }
    }

    override fun moveInQueue(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        _queue.value = q
        val cur = _queueIndex.value
        _queueIndex.value = when {
            cur == from -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
    }

    override fun clearQueue() {
        val cur = _currentTrack.value ?: return
        _queue.value = listOf(cur)
        originalQueue = listOf(cur)
        _queueIndex.value = 0
    }

    override fun restoreQueue(songs: List<SongItem>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        rememberSongs(songs)
        val safeIdx = index.coerceIn(0, songs.lastIndex)
        originalQueue = songs
        _queue.value = songs
        _queueIndex.value = safeIdx
        val song = songs[safeIdx]
        _currentTrack.value = song
        val dur = (song.duration ?: 0) * 1000L
        _duration.value = dur
        _playbackPosition.value = positionMs.coerceAtLeast(0L)
        restoredPendingPlay = true
        restoredPositionMs = positionMs.coerceAtLeast(0L)
        warmUp(listOf(song))
    }

    override fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.25f, 3.0f)
        playbackSpeed = safe
        runFx { activePlayer?.rate = safe.toDouble() }
    }

    override fun next() {
        advanceToNext(fromAutoEnd = false, useCrossfade = false)
    }

    override fun previous() {
        val pos = _playbackPosition.value
        if (pos > 3_500L) {
            seekTo(0L)
            return
        }
        val q = _queue.value
        val idx = _queueIndex.value
        if (q.isEmpty()) return
        val prevIdx = when {
            idx > 0 -> idx - 1
            _repeatMode.value == RepeatMode.ALL -> q.lastIndex
            else -> 0
        }
        playAt(prevIdx, startPositionMs = 0L, useCrossfade = false)
    }

    override fun setShuffle(enabled: Boolean) {
        if (_shuffleEnabled.value == enabled) return
        _shuffleEnabled.value = enabled
        val q = _queue.value
        val cur = _currentTrack.value
        if (q.size <= 1 || cur == null) return
        if (enabled) {
            val rest = q.filter { it.id != cur.id }.shuffled()
            _queue.value = listOf(cur) + rest
            _queueIndex.value = 0
        } else {
            val restored = if (originalQueue.any { it.id == cur.id }) originalQueue else q
            _queue.value = restored
            _queueIndex.value = restored.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    override fun setVideoMode(enabled: Boolean) {
        _videoMode.value = enabled
    }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepTimer.value = SleepTimerState()
            return
        }
        val totalMs = minutes * 60_000L
        _sleepTimer.value = SleepTimerState(active = true, remainingMs = totalMs, atEndOfTrack = false)
        sleepJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining = (remaining - 1_000L).coerceAtLeast(0L)
                _sleepTimer.value = SleepTimerState(active = remaining > 0L, remainingMs = remaining, atEndOfTrack = false)
            }
            if (remaining <= 0L) {
                _sleepTimer.value = SleepTimerState()
                pause()
            }
        }
    }

    override fun setSleepAtEndOfTrack() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerState(active = true, remainingMs = 0L, atEndOfTrack = true)
    }

    override fun pause() {
        _isPlaying.value = false
        runFx { activePlayer?.pause() }
    }

    override fun resume() {
        if (restoredPendingPlay && activePlayer == null) {
            val idx = _queueIndex.value
            val pos = restoredPositionMs
            restoredPendingPlay = false
            if (idx in _queue.value.indices) {
                playAt(idx, startPositionMs = pos, useCrossfade = false)
                return
            }
        }
        _isPlaying.value = true
        runFx { activePlayer?.play() }
    }

    override fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        _playbackPosition.value = safe
        if (restoredPendingPlay && activePlayer == null) {
            restoredPositionMs = safe
            return
        }
        runFx { activePlayer?.seek(Duration.millis(safe.toDouble())) }
    }

    override fun warmUp(songs: List<SongItem>) {
        rememberSongs(songs)
        songs.take(2).forEach { song ->
            ioScope.launch {
                runCatching { downloads.getOrFetchPlayableFile(song.id) }
            }
        }
    }

    override fun setAudioQuality(quality: AudioQuality) {
        audioQuality = quality
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        normalizeVolume = enabled
        val vol = effectiveTargetVolume()
        runFx { activePlayer?.volume = vol }
    }

    override fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in equalizerGains.indices) {
            equalizerGains[bandIndex] = gainDb.coerceIn(-24f, 12f)
            runFx {
                activePlayer?.let { applyEqualizer(it) }
            }
        }
    }

    override fun setCrossfadeDuration(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, 12)
    }

    private fun playAt(index: Int, startPositionMs: Long, useCrossfade: Boolean) {
        val q = _queue.value
        val song = q.getOrNull(index) ?: return
        restoredPendingPlay = false
        crossfadeTriggeredForTrackId = null
        _queueIndex.value = index
        _currentTrack.value = song
        _playbackError.value = null
        val estimatedDurationMs = ((song.duration ?: 0) * 1000L).coerceAtLeast(0L)
        _duration.value = estimatedDurationMs
        _playbackPosition.value = startPositionMs
        _bufferedPosition.value = 0L
        _isPlaying.value = true

        // Pre-warm the next song in queue
        q.getOrNull(index + 1)?.let { nextSong -> warmUp(listOf(nextSong)) }

        playJob?.cancel()
        playJob = scope.launch {
            try {
                val file = downloads.getOrFetchPlayableFile(song.id) { fraction ->
                    val dur = _duration.value.takeIf { it > 0L } ?: 180_000L
                    _bufferedPosition.value = (dur * fraction).toLong()
                }
                val loudnessJob = ioScope.async {
                    resolveYouTubeId(song.id)?.let { youTube.loudnessDb(it).getOrNull() }
                }
                val loudnessDb = runCatching { loudnessJob.await() }.getOrNull()
                currentTrackGain = if (loudnessDb != null && loudnessDb > 0f) {
                    10.0.pow((-loudnessDb.toDouble() / 20.0)).coerceIn(0.25, 1.0)
                } else {
                    1.0
                }
                startMediaPlayer(song, file, startPositionMs, useCrossfade && crossfadeSeconds > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                _isPlaying.value = false
                _playbackError.value = e.message ?: "Şarkı çalınamadı"
            }
        }
    }

    private fun startMediaPlayer(
        song: SongItem,
        file: File,
        startPositionMs: Long,
        withCrossfade: Boolean,
    ) {
        runFx {
            val previous = activePlayer
            fadingPlayer?.let { oldFading ->
                runCatching {
                    oldFading.stop()
                    oldFading.dispose()
                }
                fadingPlayer = null
            }

            val media = Media(file.toURI().toString())
            val player = MediaPlayer(media)
            activePlayer = player

            val targetVol = effectiveTargetVolume()
            player.rate = playbackSpeed.toDouble()
            player.volume = if (withCrossfade && previous != null) 0.0 else targetVol
            applyEqualizer(player)

            player.setOnReady {
                if (activePlayer !== player) return@setOnReady
                val totalMs = player.totalDuration?.toMillis()?.toLong() ?: 0L
                if (totalMs > 0L) {
                    _duration.value = totalMs
                    _bufferedPosition.value = totalMs
                }
                applyEqualizer(player)
                if (startPositionMs > 0L) {
                    player.seek(Duration.millis(startPositionMs.toDouble()))
                }
                if (_isPlaying.value) {
                    player.play()
                }
            }

            player.setOnEndOfMedia {
                if (activePlayer !== player) return@setOnEndOfMedia
                scope.launch {
                    onTrackFinished(song.id)
                }
            }

            player.setOnError {
                if (activePlayer !== player) return@setOnError
                val err = player.error?.message ?: "Ses oynatma hatası"
                _playbackError.value = err
                _isPlaying.value = false
            }

            if (withCrossfade && previous != null) {
                fadingPlayer = previous
                fadeJob?.cancel()
                fadeJob = scope.launch {
                    val steps = 20
                    val stepDelayMs = (crossfadeSeconds * 1000L / steps).coerceAtLeast(40L)
                    for (step in 1..steps) {
                        val frac = step.toDouble() / steps.toDouble()
                        val target = effectiveTargetVolume()
                        runFx {
                            if (activePlayer === player) player.volume = target * frac
                            if (fadingPlayer === previous) previous.volume = target * (1.0 - frac)
                        }
                        delay(stepDelayMs)
                    }
                    runFx {
                        if (fadingPlayer === previous) {
                            runCatching {
                                previous.stop()
                                previous.dispose()
                            }
                            fadingPlayer = null
                        }
                    }
                }
            } else if (previous != null) {
                runCatching {
                    previous.stop()
                    previous.dispose()
                }
            }
        }
    }

    private fun onTrackFinished(trackId: String) {
        if (crossfadeTriggeredForTrackId == trackId) return
        if (_sleepTimer.value.active && _sleepTimer.value.atEndOfTrack) {
            _sleepTimer.value = SleepTimerState()
            pause()
            return
        }
        advanceToNext(fromAutoEnd = true, useCrossfade = false)
    }

    private fun advanceToNext(fromAutoEnd: Boolean, useCrossfade: Boolean) {
        val q = _queue.value
        val idx = _queueIndex.value
        if (q.isEmpty() || idx < 0) return

        if (fromAutoEnd && _repeatMode.value == RepeatMode.ONE) {
            playAt(idx, startPositionMs = 0L, useCrossfade = false)
            return
        }

        val nextIdx = when {
            idx + 1 < q.size -> idx + 1
            _repeatMode.value == RepeatMode.ALL -> 0
            else -> -1
        }
        if (nextIdx >= 0) {
            playAt(nextIdx, startPositionMs = 0L, useCrossfade = useCrossfade)
        } else {
            _isPlaying.value = false
            seekTo(0L)
            pause()
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                delay(250L)
                val player = activePlayer
                if (player != null && _isPlaying.value) {
                    runFx {
                        val pos = player.currentTime?.toMillis()?.toLong() ?: 0L
                        val dur = player.totalDuration?.toMillis()?.toLong() ?: 0L
                        if (pos >= 0L) _playbackPosition.value = pos
                        if (dur > 0L) {
                            _duration.value = dur
                            if (_bufferedPosition.value < dur) _bufferedPosition.value = dur
                        }
                        // Trigger crossfade when approaching the end of the track
                        val cfMs = crossfadeSeconds * 1000L
                        val curTrack = _currentTrack.value
                        if (cfMs > 0L &&
                            dur > cfMs + 2_000L &&
                            dur - pos in 250L..cfMs &&
                            curTrack != null &&
                            crossfadeTriggeredForTrackId != curTrack.id &&
                            !(_sleepTimer.value.active && _sleepTimer.value.atEndOfTrack)
                        ) {
                            val hasNext = _queueIndex.value + 1 < _queue.value.size || _repeatMode.value != RepeatMode.OFF
                            if (hasNext) {
                                crossfadeTriggeredForTrackId = curTrack.id
                                scope.launch {
                                    advanceToNext(fromAutoEnd = true, useCrossfade = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun effectiveTargetVolume(): Double =
        if (normalizeVolume) currentTrackGain.coerceIn(0.25, 1.0) else 1.0

    private fun applyEqualizer(player: MediaPlayer) {
        runCatching {
            val eq = player.audioEqualizer ?: return
            eq.isEnabled = equalizerGains.any { it != 0f }
            val bands = eq.bands
            val count = minOf(bands.size, equalizerGains.size)
            for (i in 0 until count) {
                bands[i].gain = equalizerGains[i].toDouble().coerceIn(-24.0, 12.0)
            }
        }
    }

    private fun stopAllPlayers() {
        playJob?.cancel()
        fadeJob?.cancel()
        runFx {
            activePlayer?.let {
                runCatching {
                    it.stop()
                    it.dispose()
                }
            }
            activePlayer = null
            fadingPlayer?.let {
                runCatching {
                    it.stop()
                    it.dispose()
                }
            }
            fadingPlayer = null
        }
    }

    private suspend fun resolveYouTubeId(id: String): String? {
        if (!SpotifyIds.isSpotify(id)) return id
        matchedSpotifyIds[id]?.let { return it }
        val song = knownSongs[id] ?: _queue.value.firstOrNull { it.id == id } ?: return null
        return spotifyMatcher(song)?.also { matchedSpotifyIds[id] = it }
    }

    private suspend fun resolveAudioUrl(id: String, quality: AudioQuality): String? {
        val key = "${quality.name}:$id"
        urlCache[key]?.let { return it }
        val ytId = resolveYouTubeId(id) ?: return null
        val streams = youTube.resolveStreamUrls(ytId)
            .getOrNull()
            ?.filter { !it.isDrm && it.mimeType.startsWith("audio") }
            .orEmpty()
        // Prefer audio/mp4 (AAC in M4A) for native JavaFX / Windows Media Foundation playback
        val mp4Streams = streams.filter { it.mimeType.startsWith("audio/mp4") }
        val pool = mp4Streams.ifEmpty { streams }
        val pick = when (quality) {
            AudioQuality.HIGH -> pool.maxByOrNull(AudioStream::bitrate)
            AudioQuality.LOW -> pool.minByOrNull(AudioStream::bitrate)
            AudioQuality.NORMAL -> pool.minByOrNull { abs(it.bitrate - 128_000) }
        }
        return pick?.url?.also { urlCache[key] = it }
    }

    private fun invalidateCacheFor(id: String) {
        AudioQuality.entries.forEach { q -> urlCache.remove("${q.name}:$id") }
        youTube.invalidateStreamCache(id)
        matchedSpotifyIds[id]?.let { youTube.invalidateStreamCache(it) }
    }

    private companion object {
        private val fxInitialized = AtomicBoolean(false)

        fun ensureJavaFxInitialized() {
            if (fxInitialized.compareAndSet(false, true)) {
                runCatching {
                    Platform.startup {
                        Platform.setImplicitExit(false)
                    }
                }.onFailure {
                    runCatching { Platform.setImplicitExit(false) }
                }
            }
        }

        fun runFx(action: () -> Unit) {
            ensureJavaFxInitialized()
            if (Platform.isFxApplicationThread()) {
                runCatching(action)
            } else {
                runCatching { Platform.runLater { runCatching(action) } }
            }
        }
    }
}
