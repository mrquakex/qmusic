package com.qmusic.player.adapter

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.withContext
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.VideoSource
import com.qmusic.player.AudioQuality
import com.qmusic.player.PlayerController
import com.qmusic.player.RepeatMode
import com.qmusic.player.SleepTimerState
import com.qmusic.player.offline.OfflineDownloader
import com.qmusic.player.cache.CacheProvider
import com.qmusic.player.dsp.CrossfadeFilterAudioProcessor
import com.qmusic.player.dsp.EqualizerAudioProcessor
import com.qmusic.player.source.ResolvingDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dual-Player DJ Crossfade Adapter coordinating two ExoPlayer instances.
 * Implements [PlayerController] and equal-power sinusoidal crossfading with reflection delegate swapping.
 */
class CrossfadeExoPlayerAdapter(
    private val context: Context,
    private val streamUrlResolver: (suspend (String) -> String?)? = null,
    private val offline: OfflineDownloader? = null,
    private val videoResolver: (suspend (String) -> VideoSource?)? = null,
    /** How loud YouTube measured a song (dB against its reference); used by volume normalisation. */
    private val loudnessResolver: (suspend (String) -> Float?)? = null,
    /** Told when a saved stream address no longer plays, so it is not used again. */
    private val onStaleUrl: ((String) -> Unit)? = null,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Downloads first (read-only), then the streaming cache, then the network: shared by audio and video. */
    private val dataSourceFactory: CacheDataSource.Factory by lazy {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        val streamCacheFactory = CacheDataSource.Factory()
            .setCache(CacheProvider.getPlayerCache(context))
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(httpDataSourceFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        CacheDataSource.Factory()
            .setCache(CacheProvider.getDownloadCache(context))
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamCacheFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val crossfadeProcessorA = CrossfadeFilterAudioProcessor()
    private val crossfadeProcessorB = CrossfadeFilterAudioProcessor()
    private val equalizerProcessorA = EqualizerAudioProcessor()
    private val equalizerProcessorB = EqualizerAudioProcessor()

    private val playerA: ExoPlayer = createPlayer(crossfadeProcessorA, equalizerProcessorA)
    private val playerB: ExoPlayer = createPlayer(crossfadeProcessorB, equalizerProcessorB)

    @Volatile
    private var activePlayer: ExoPlayer = playerA

    private val _activePlayerFlow = MutableStateFlow<Player>(playerA)

    /** The player that is rendering right now; the video view re-attaches to it whenever it changes. */
    val activePlayerFlow: StateFlow<Player> = _activePlayerFlow.asStateFlow()

    private fun setActive(player: ExoPlayer) {
        activePlayer = player
        _activePlayerFlow.value = player
    }

    private var handOffJob: Job? = null

    val forwardingPlayer: DelegatingForwardingPlayer = DelegatingForwardingPlayer(playerA)

    private val _currentTrack = MutableStateFlow<SongItem?>(null)
    override val currentTrack: StateFlow<SongItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    override val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _buffered = MutableStateFlow(0L)
    override val bufferedPosition: StateFlow<Long> = _buffered.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    override val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    override val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _videoMode = MutableStateFlow(false)
    override val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    /** The player that is rendering right now; the video view attaches to it. */
    val videoPlayer: Player get() = activePlayer

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    override val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    /** The queue as the user built it; non-null only while shuffle is on, to restore the order afterwards. */
    private var orderedQueue: List<SongItem>? = null

    private var resolveJob: Job? = null
    private var lastManualSkipAt = 0L
    private var prefetchJob: Job? = null
    private var sleepJob: Job? = null

    /** Resolved stream URLs by video id, so the next track starts without a network round trip. */
    private val urlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > URL_CACHE_SIZE
    }

    /** Queue index we already started advancing away from, so the ticker and STATE_ENDED do not both fire. */
    private var advancedFromIndex = -1

    private var crossfadeDurationSeconds: Int = 5
    private var crossfadeJob: Job? = null
    private var tickerJob: Job? = null

    init {
        forwardingPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                recoverFromStaleStream()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = activePlayer.duration
                    if (dur > 0) _duration.value = dur
                }
                if (playbackState == Player.STATE_ENDED) advanceWhenTrackEnds()
            }
        })

        forwardingPlayer.navigator = object : DelegatingForwardingPlayer.QueueNavigator {
            override fun hasNext(): Boolean = _queueIndex.value + 1 < _queue.value.size || (_repeat.value == RepeatMode.ALL && _queue.value.isNotEmpty())
            override fun hasPrevious(): Boolean = _queue.value.isNotEmpty()
            override fun next() = this@CrossfadeExoPlayerAdapter.next()
            override fun previous() = this@CrossfadeExoPlayerAdapter.previous()
        }

        startProgressTicker()
    }

    private fun createPlayer(
        crossfadeProcessor: CrossfadeFilterAudioProcessor,
        equalizerProcessor: EqualizerAudioProcessor,
    ): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(crossfadeProcessor, equalizerProcessor))
                    .build()
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            // Start as soon as ~0.75 s of sound is in (default 2.5 s): songs begin noticeably sooner, and the
            // buffer still fills up to its usual size behind the playback.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        START_BUFFER_MS,
                        REBUFFER_MS,
                    )
                    .build(),
            )
            .build()
    }

    /** The song already retried once with a fresh address; a second failure is reported instead of looping. */
    private var retriedFor: String? = null

    /**
     * A saved address can stop working (it ran out, or the connection changed): look the song up again and
     * continue from where it stopped, once.
     */
    private fun recoverFromStaleStream() {
        val song = _currentTrack.value ?: return
        if (offline?.isDownloaded(song.id) == true || retriedFor == song.id) return
        retriedFor = song.id
        synchronized(urlCache) { urlCache.remove(song.id) }
        onStaleUrl?.invoke(song.id)
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        playAt(_queueIndex.value, startPositionMs = position)
    }

    // --- Stream quality and volume normalisation ---
    @Volatile
    private var normalize = false

    /** Loudness per song id; a song missing here has not been measured (yet). */
    private val loudness = java.util.concurrent.ConcurrentHashMap<String, Float>()

    override fun setAudioQuality(quality: AudioQuality) {
        if (com.qmusic.player.PlayerHolder.audioQuality == quality) return
        com.qmusic.player.PlayerHolder.audioQuality = quality
        // Addresses already looked up are for the old quality; the songs to come resolve again.
        synchronized(urlCache) { urlCache.clear() }
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        normalize = enabled
        applyLoudness()
        if (enabled) _currentTrack.value?.let { measure(it.id) }
    }

    private fun measure(songId: String) {
        if (!normalize || loudnessResolver == null || loudness.containsKey(songId)) return
        scope.launch {
            loudnessResolver.invoke(songId)?.let { loudness[songId] = it }
            applyLoudness()
        }
    }

    /** Each player gets the gain of the song it holds, so a crossfade blends two songs each at its right level. */
    private fun applyLoudness() {
        listOf(playerA to equalizerProcessorA, playerB to equalizerProcessorB).forEach { (player, eq) ->
            val db = player.currentMediaItem?.mediaId?.let { loudness[it] }
            eq.normalizationGainDb = if (normalize && db != null) (-db).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB) else 0f
        }
    }

    // --- Casting: while a remote player is attached, the controls and the shown state are its ---
    @Volatile
    private var remote: Player? = null

    /** Queue index of the remote player's first item. */
    private var remoteStart = 0

    /** Asked to cast from a queue index when a song is chosen while casting. */
    @Volatile
    var onRemotePlayAt: ((Int) -> Unit)? = null

    val isCasting: Boolean get() = remote != null

    /** Hands playback to [player] (whose first item is queue index [startIndex]), or takes it back with null. */
    fun attachRemote(player: Player?, startIndex: Int = 0) {
        remote = player
        remoteStart = startIndex
        if (player != null) {
            activePlayer.pause()
            forwardingPlayer.pause()
        }
    }

    /** Back from casting: plays the queue here from [index], at [positionMs]. */
    fun resumeLocally(index: Int, positionMs: Long) {
        playAt(index, startPositionMs = positionMs)
    }

    /** Mirrors the remote player into the state the app shows; false when nothing is cast. */
    private fun syncRemote(): Boolean {
        val r = remote ?: return false
        _playbackPosition.value = r.currentPosition
        if (r.duration > 0) _duration.value = r.duration
        _isPlaying.value = r.isPlaying
        val index = remoteStart + r.currentMediaItemIndex
        if (index != _queueIndex.value && index in _queue.value.indices) {
            _queueIndex.value = index
            _currentTrack.value = _queue.value[index]
        }
        return true
    }

    private fun startProgressTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                if (syncRemote()) {
                    delay(250)
                    continue
                }
                applyLoudness()
                if (activePlayer.playbackState == Player.STATE_READY || activePlayer.isPlaying) {
                    _playbackPosition.value = activePlayer.currentPosition
                    _buffered.value = activePlayer.bufferedPosition
                    val dur = activePlayer.duration
                    if (dur > 0) _duration.value = dur
                    _isPlaying.value = activePlayer.isPlaying
                    advanceWhenTrackIsEnding()
                }
                delay(250)
            }
        }
    }

    override fun play(song: SongItem) = playQueue(listOf(song), 0)

    override fun playQueue(songs: List<SongItem>, startIndex: Int) {
        if (songs.isEmpty()) return
        val start = startIndex.coerceIn(songs.indices)
        if (_shuffle.value && songs.size > 1) {
            orderedQueue = songs
            _queue.value = listOf(songs[start]) + (songs.take(start) + songs.drop(start + 1)).shuffled()
            playAt(0)
        } else {
            orderedQueue = null
            _queue.value = songs
            playAt(start)
        }
    }

    override fun appendToQueue(songs: List<SongItem>) {
        val known = _queue.value.mapTo(HashSet()) { it.id }
        val fresh = songs.filter { known.add(it.id) }
        if (fresh.isEmpty()) return
        _queue.value = _queue.value + fresh
        orderedQueue = orderedQueue?.plus(fresh)
        prefetchNext()
    }

    override fun playNext(song: SongItem) {
        val index = _queueIndex.value
        val current = _currentTrack.value
        if (index < 0 || current == null) return play(song)
        val without = _queue.value.filterIndexed { i, queued -> i == index || queued.id != song.id }
        val currentAt = without.indexOfFirst { it.id == current.id }
        _queue.value = without.toMutableList().apply { add(currentAt + 1, song) }
        _queueIndex.value = currentAt
        orderedQueue = orderedQueue?.let { ordered ->
            val rest = ordered.filter { it.id != song.id }
            val at = rest.indexOfFirst { it.id == current.id } + 1
            rest.toMutableList().apply { add(at, song) }
        }
        prefetchNext()
    }

    override fun skipTo(index: Int) {
        if (index in _queue.value.indices) playAt(index)
    }

    override fun clearQueue() {
        val current = _queue.value.getOrNull(_queueIndex.value) ?: return
        _queue.value = listOf(current)
        _queueIndex.value = 0
        orderedQueue = null
    }

    override fun moveInQueue(from: Int, to: Int) {
        val queue = _queue.value
        if (from !in queue.indices || to !in queue.indices || from == to) return
        _queue.value = queue.toMutableList().apply { add(to, removeAt(from)) }
        _queueIndex.value = com.qmusic.player.movedIndex(_queueIndex.value, from, to)
        prefetchNext()
    }

    override fun removeFromQueue(index: Int) {
        val queue = _queue.value
        if (index !in queue.indices) return
        val current = _queueIndex.value
        val removed = queue[index]
        _queue.value = queue.filterIndexed { i, _ -> i != index }
        orderedQueue = orderedQueue?.filter { it.id != removed.id }
        when {
            index < current -> _queueIndex.value = current - 1
            index == current -> if (index < _queue.value.size) {
                playAt(index)
            } else {
                activePlayer.stop()
                _isPlaying.value = false
            }
        }
    }

    override fun next() {
        remote?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem(); return }
        val queue = _queue.value
        val index = _queueIndex.value + 1
        when {
            index in queue.indices -> playAt(index)
            _repeat.value == RepeatMode.ALL && queue.isNotEmpty() -> playAt(0)
        }
    }

    override fun previous() {
        remote?.let { it.seekToPrevious(); return }
        val index = _queueIndex.value
        if (index <= 0 || activePlayer.currentPosition > RESTART_THRESHOLD_MS) {
            seekTo(0L)
        } else {
            playAt(index - 1)
        }
    }

    override fun setShuffle(enabled: Boolean) {
        if (_shuffle.value == enabled) return
        _shuffle.value = enabled
        val queue = _queue.value
        val index = _queueIndex.value
        if (index !in queue.indices) return
        val current = queue[index]
        if (enabled) {
            orderedQueue = queue
            _queue.value = queue.take(index + 1) + queue.drop(index + 1).shuffled()
        } else {
            val ordered = orderedQueue ?: return
            orderedQueue = null
            _queue.value = ordered
            _queueIndex.value = ordered.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        }
        prefetchNext()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _repeat.value = mode
    }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepTimer.value = SleepTimerState()
            return
        }
        val total = minutes * 60_000L
        _sleepTimer.value = SleepTimerState(active = true, remainingMs = total)
        sleepJob = scope.launch {
            var left = total
            while (left > 0) {
                delay(1_000)
                left -= 1_000
                _sleepTimer.value = SleepTimerState(active = true, remainingMs = left.coerceAtLeast(0))
            }
            fadeOutAndPause()
            _sleepTimer.value = SleepTimerState()
        }
    }

    override fun setSleepAtEndOfTrack() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerState(active = true, atEndOfTrack = true)
    }

    /** Eases the volume down over about three seconds so the stop is not abrupt, then restores it. */
    private suspend fun fadeOutAndPause() {
        val player = activePlayer
        for (step in FADE_STEPS downTo 1) {
            player.volume = step.toFloat() / FADE_STEPS
            delay(FADE_STEP_MS)
        }
        pause()
        player.volume = 1f
    }

    override fun setVideoMode(enabled: Boolean) {
        if (_videoMode.value == enabled) return
        _videoMode.value = enabled
        val song = _currentTrack.value ?: return
        // Only songs that have a clip change; the rest keep playing untouched.
        if (!song.hasVideo || _queueIndex.value < 0) return

        if (activePlayer.mediaItemCount == 0 || activePlayer.playbackState == Player.STATE_IDLE) {
            // Nothing is playing yet (still loading): just start it in the right mode.
            playAt(_queueIndex.value, startPositionMs = activePlayer.currentPosition)
            return
        }
        scope.launch {
            // Resolving takes a moment; the sound keeps playing untouched meanwhile.
            val source = resolveSource(song) ?: return@launch
            if (_videoMode.value == enabled && _currentTrack.value?.id == song.id) handOff(song, source)
        }
    }

    /**
     * Swaps picture <-> audio-only without a pause: the new source is prepared silently on the idle player,
     * and only once it is ready to play, at the spot the old one has reached, the two trade places.
     */
    private fun handOff(song: SongItem, source: Source) {
        handOffJob?.cancel()
        crossfadeJob?.cancel()
        val outgoing = activePlayer
        outgoing.volume = 1f
        val incoming = if (outgoing === playerA) playerB else playerA
        val incomingFade = if (incoming === playerA) crossfadeProcessorA else crossfadeProcessorB
        val outgoingFade = if (outgoing === playerA) crossfadeProcessorA else crossfadeProcessorB

        val prepared = prepared(song, source)
        incoming.stop()
        incoming.clearMediaItems()
        incomingFade.setVolume(1f)
        incoming.volume = 0f
        load(incoming, prepared, outgoing.currentPosition)
        incoming.playWhenReady = false
        incoming.prepare()

        handOffJob = scope.launch {
            val ready = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) {
                while (incoming.playbackState != Player.STATE_READY) delay(HANDOFF_POLL_MS)
                true
            }
            if (ready != true || _currentTrack.value?.id != song.id) {
                // Could not get ready in time (or the user moved on): leave the old one playing.
                incoming.stop()
                incoming.clearMediaItems()
                return@launch
            }

            val wasPlaying = outgoing.isPlaying
            if (!wasPlaying) {
                // Paused: nothing audible to bridge, swap straight away at the current spot.
                incoming.seekTo(outgoing.currentPosition)
                swapTo(incoming, outgoing, incomingFade, outgoingFade)
                return@launch
            }

            // Start the new player silently, ALREADY RUNNING, so its audio pipeline is warm; then line it up with
            // the old one to within a few milliseconds before anything becomes audible.
            incoming.playWhenReady = true
            withTimeoutOrNull(HANDOFF_SEEK_TIMEOUT_MS) {
                while (!incoming.isPlaying) delay(HANDOFF_POLL_MS)
            }
            repeat(HANDOFF_ALIGN_TRIES) {
                val drift = incoming.currentPosition - outgoing.currentPosition
                if (abs(drift) <= HANDOFF_TOLERANCE_MS) return@repeat
                incoming.seekTo(outgoing.currentPosition + HANDOFF_LEAD_MS)
                withTimeoutOrNull(HANDOFF_SEEK_TIMEOUT_MS) {
                    delay(HANDOFF_POLL_MS)
                    while (incoming.playbackState != Player.STATE_READY || !incoming.isPlaying) delay(HANDOFF_POLL_MS)
                }
            }

            // Equal-power blend of the SAME audio from both players: whatever gap or overlap is left from the
            // start-up latency is covered by it, so the sound never dips and never doubles up audibly.
            forwardingPlayer.suppressPlaybackEnded = true
            val steps = (HANDOFF_BLEND_MS / HANDOFF_BLEND_STEP_MS).toInt()
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                incoming.volume = kotlin.math.sin(t * Math.PI.toFloat() / 2f)
                outgoing.volume = kotlin.math.cos(t * Math.PI.toFloat() / 2f)
                delay(HANDOFF_BLEND_STEP_MS)
            }
            swapTo(incoming, outgoing, incomingFade, outgoingFade)
        }
    }

    private fun swapTo(
        incoming: ExoPlayer,
        outgoing: ExoPlayer,
        incomingFade: CrossfadeFilterAudioProcessor,
        outgoingFade: CrossfadeFilterAudioProcessor,
    ) {
        forwardingPlayer.suppressPlaybackEnded = true
        incoming.volume = 1f
        incoming.playWhenReady = outgoing.playWhenReady
        outgoing.volume = 0f
        forwardingPlayer.setDelegate(incoming)
        setActive(incoming)
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        outgoingFade.setVolume(1f)
        incomingFade.setVolume(1f)
        forwardingPlayer.suppressPlaybackEnded = false
    }

    /**
     * @param fadeMs how long the old song fades into the new one. Only the automatic move to the next song uses the
     * user's crossfade length; picking a song or pressing next/previous is a deliberate cut and gets a short dip
     * ([MANUAL_FADE_MS]) instead of a long overlap of two songs.
     */
    private fun playAt(
        index: Int,
        skipsLeft: Int = MAX_AUTO_SKIPS,
        startPositionMs: Long = 0L,
        fadeMs: Long = MANUAL_FADE_MS,
    ) {
        val song = _queue.value.getOrNull(index) ?: return
        // Casting: the chosen song plays on the cast device instead.
        if (remote != null) {
            onRemotePlayAt?.invoke(index)
            return
        }
        if (song.id != retriedFor) retriedFor = null // a new song gets its own retry
        resolveJob?.cancel()
        advancedFromIndex = -1
        _queueIndex.value = index
        _currentTrack.value = song
        _playbackError.value = null
        _playbackPosition.value = startPositionMs
        _buffered.value = startPositionMs
        _duration.value = (song.duration?.toLong() ?: 0L) * 1000L
        measure(song.id)

        val manual = fadeMs == MANUAL_FADE_MS
        resolveJob = scope.launch {
            // Tapping next several times in a row only changes what is selected; the song is loaded once the
            // taps stop, so nothing is resolved, fetched or half-started for the ones skipped over.
            // Only a quick series of taps waits; a single tap starts at once.
            val now = System.currentTimeMillis()
            val rapid = now - lastManualSkipAt < SKIP_RAPID_MS
            lastManualSkipAt = now
            if (manual && startPositionMs == 0L && rapid) delay(SKIP_SETTLE_MS)
            var source = resolveSource(song)
            if (source == null && manual) source = resolveSource(song) // one retry; a flaky lookup is common
            if (source == null) {
                _playbackError.value = "Could not play \"${song.title}\""
                // Only the automatic advance moves on by itself; a song the user picked is never swapped for another.
                if (!manual && skipsLeft > 0 && index + 1 < _queue.value.size) playAt(index + 1, skipsLeft - 1, fadeMs = fadeMs)
                return@launch
            }
            playStream(song, source, startPositionMs, fadeMs)
            prefetchNext()
            // Warm the picture's stream list now: switching to video then needs no extra round trip.
            if (song.hasVideo && videoResolver != null) launch { videoResolver.invoke(song.id) }
        }
    }

    /** What to play and which cache entry holds it. A downloaded song never touches the network. */
    private class Source(
        val uri: String,
        val cacheKey: String,
        /** Set for videos above 360p, whose sound is a separate stream to merge in. */
        val audioUri: String? = null,
        val isVideo: Boolean = false,
    )

    private suspend fun resolveSource(song: SongItem): Source? {
        if (_videoMode.value && song.hasVideo) {
            videoResolver?.invoke(song.id)?.let { video ->
                val itag = ITAG_PARAM.find(video.videoUrl)?.groupValues?.get(1) ?: "video"
                return Source(video.videoUrl, "${song.id}:v$itag", video.audioUrl, isVideo = true)
            }
            // No clip could be resolved: fall through and play the audio.
        }
        if (offline?.isDownloaded(song.id) == true) {
            return Source(OfflineDownloader.offlineUri(song.id), OfflineDownloader.cacheKey(song.id))
        }
        val url = resolveUrl(song.id) ?: return null
        return Source(url, "${song.id}:${ITAG_PARAM.find(url)?.groupValues?.get(1) ?: "audio"}")
    }

    private suspend fun resolveUrl(videoId: String): String? {
        urlCache[videoId]?.takeIf { !ResolvingDataSource.isUrlExpired(it) }?.let { return it }
        return streamUrlResolver?.invoke(videoId)?.also { urlCache[videoId] = it }
    }

    private fun prefetchNext() {
        val upcoming = _queue.value.drop(_queueIndex.value + 1).take(PREFETCH_AHEAD)
        if (upcoming.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch { upcoming.forEach { warm(it) } }
    }

    override fun warmUp(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        scope.launch { songs.forEach { warm(it) } }
    }

    /**
     * Looks the stream up and stores its first seconds in the cache, so when the song is played the sound is
     * already on the device and starts without a network round trip (the lookup alone is the slow part).
     */
    private suspend fun warm(song: SongItem) {
        if (offline?.isDownloaded(song.id) == true || !warmed.add(song.id)) return
        val url = resolveUrl(song.id) ?: run { warmed.remove(song.id); return }
        val key = "${song.id}:${ITAG_PARAM.find(url)?.groupValues?.get(1) ?: "audio"}"
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val spec = DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).setLength(WARM_BYTES).build()
                CacheWriter(dataSourceFactory.createDataSource(), spec, null, null).cache()
            }
        }
        if (result.isFailure) warmed.remove(song.id)
    }

    private val warmed = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun hasUpcoming(): Boolean =
        _queueIndex.value + 1 < _queue.value.size || (_repeat.value == RepeatMode.ALL && _queue.value.isNotEmpty())

    /** Starts the next track early enough for the crossfade to finish as the current one ends. */
    private fun advanceWhenTrackIsEnding() {
        val index = _queueIndex.value
        // Repeat-one and "stop after this song" are handled when the track really ends, with no overlap.
        if (index < 0 || !hasUpcoming() || advancedFromIndex == index) return
        if (_repeat.value == RepeatMode.ONE || _sleepTimer.value.atEndOfTrack) return
        if (crossfadeJob?.isActive == true) return
        val remaining = activePlayer.duration - activePlayer.currentPosition
        if (activePlayer.duration <= 0) return
        val lead = crossfadeDurationSeconds * 1000L
        if (remaining <= lead + END_LEAD_MS) {
            advancedFromIndex = index
            val queue = _queue.value
            val target = if (index + 1 in queue.indices) index + 1 else 0 // repeat-all wraps around
            playAt(target, fadeMs = lead)
        }
    }

    /** The track really ended (crossfade off, repeat-one, sleep-at-end, or a missed tick). */
    private fun advanceWhenTrackEnds() {
        val index = _queueIndex.value
        if (index < 0 || advancedFromIndex == index) return
        if (crossfadeJob?.isActive == true) return
        advancedFromIndex = index
        when {
            _sleepTimer.value.atEndOfTrack -> {
                _sleepTimer.value = SleepTimerState()
                pause()
            }

            _repeat.value == RepeatMode.ONE -> {
                advancedFromIndex = -1
                activePlayer.seekTo(0L)
                activePlayer.play()
            }

            else -> next()
        }
    }

    /** What the player is given: a plain item for audio, a merged picture + sound source for video. */
    private class Prepared(val item: MediaItem?, val mediaSource: MediaSource?)

    private fun prepared(song: SongItem, source: Source): Prepared {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artists.joinToString(", ") { it.name })
            .setArtworkUri(song.thumbnail?.let { Uri.parse(it) })
            .build()

        if (!source.isVideo) {
            return Prepared(
                item = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(Uri.parse(source.uri))
                    .setCustomCacheKey(source.cacheKey)
                    .setMediaMetadata(mediaMetadata)
                    .build(),
                mediaSource = null,
            )
        }

        val factory = ProgressiveMediaSource.Factory(dataSourceFactory)
        val picture = factory.createMediaSource(
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(Uri.parse(source.uri))
                .setCustomCacheKey(source.cacheKey)
                .setMediaMetadata(mediaMetadata)
                .build(),
        )
        val merged = source.audioUri?.let { audio ->
            MergingMediaSource(
                picture,
                factory.createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(audio)).setCustomCacheKey("${song.id}:vsound").build(),
                ),
            )
        } ?: picture
        return Prepared(item = null, mediaSource = merged)
    }

    private fun load(player: ExoPlayer, prepared: Prepared, startPositionMs: Long) {
        if (prepared.mediaSource != null) {
            player.setMediaSource(prepared.mediaSource, startPositionMs)
        } else {
            player.setMediaItem(checkNotNull(prepared.item), startPositionMs)
        }
    }

    private fun playStream(song: SongItem, source: Source, startPositionMs: Long = 0L, fadeMs: Long = MANUAL_FADE_MS) {
        val prepared = prepared(song, source)
        if (prepared.mediaSource != null) {
            // The picture belongs to one player, so a video never crossfades; the sound rides along merged.
            playImmediate(mediaSource = prepared.mediaSource, startPositionMs = startPositionMs)
            return
        }
        val mediaItem = checkNotNull(prepared.item)
        val isCurrentlyPlaying = activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_READY
        // Skipping again while a blend is still running: the players are at half volume, so a new blend would
        // jump in loudness. A clean cut is what rapid skipping needs.
        val blending = crossfadeJob?.isActive == true
        if (startPositionMs == 0L && isCurrentlyPlaying && fadeMs > 0 && crossfadeDurationSeconds > 0 && !blending) {
            startCrossfadeTransition(mediaItem, fadeMs)
        } else {
            playImmediate(mediaItem = mediaItem, startPositionMs = startPositionMs)
        }
    }

    private fun playImmediate(mediaItem: MediaItem? = null, mediaSource: MediaSource? = null, startPositionMs: Long = 0L) {
        crossfadeJob?.cancel()
        handOffJob?.cancel()
        // A cancelled blend never reaches its own cleanup, which would leave "playback ended" muted for good.
        forwardingPlayer.suppressPlaybackEnded = false
        // A skip during a crossfade must not leave the other player running underneath.
        val other = if (activePlayer === playerA) playerB else playerA
        other.stop()
        other.clearMediaItems()
        val fadeProc = if (activePlayer === playerA) crossfadeProcessorA else crossfadeProcessorB
        fadeProc.setVolume(1.0f)
        activePlayer.volume = 1.0f
        if (mediaSource != null) {
            activePlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            activePlayer.setMediaItem(checkNotNull(mediaItem), startPositionMs)
        }
        activePlayer.prepare()
        activePlayer.playWhenReady = true
        activePlayer.play()
    }

    private fun startCrossfadeTransition(newMediaItem: MediaItem, durationMs: Long) {
        crossfadeJob?.cancel()

        val outgoingPlayer = activePlayer
        val incomingPlayer = if (activePlayer === playerA) playerB else playerA
        val outgoingFadeProc = if (activePlayer === playerA) crossfadeProcessorA else crossfadeProcessorB
        val incomingFadeProc = if (activePlayer === playerA) crossfadeProcessorB else crossfadeProcessorA

        incomingPlayer.stop()
        incomingPlayer.clearMediaItems()
        // The filter sweep is part of the DJ-style blend; a quick dip does not need it.
        val sweep = durationMs >= SWEEP_MIN_MS
        incomingFadeProc.setCrossfadeProgress(0f, isFadeIn = true, enableFilterSweep = sweep)
        incomingPlayer.volume = 0f
        incomingPlayer.setMediaItem(newMediaItem)
        incomingPlayer.prepare()
        incomingPlayer.playWhenReady = true
        incomingPlayer.play()

        crossfadeJob = scope.launch {
            forwardingPlayer.suppressPlaybackEnded = true

            val totalDurationMs = durationMs
            val stepMs = 50L
            val steps = (totalDurationMs / stepMs).toInt().coerceAtLeast(1)

            var delegateSwapped = false

            for (step in 0..steps) {
                val t = step.toFloat() / steps.toFloat()

                outgoingFadeProc.setCrossfadeProgress(t, isFadeIn = false, enableFilterSweep = sweep)
                incomingFadeProc.setCrossfadeProgress(t, isFadeIn = true, enableFilterSweep = sweep)

                outgoingPlayer.volume = outgoingFadeProc.getVolume()
                incomingPlayer.volume = incomingFadeProc.getVolume()

                // Swap MediaSession delegate at midpoint
                if (!delegateSwapped && step >= steps / 2) {
                    forwardingPlayer.setDelegate(incomingPlayer)
                    setActive(incomingPlayer)
                    delegateSwapped = true
                }

                delay(stepMs)
            }

            // Finalize crossfade
            if (!delegateSwapped) {
                forwardingPlayer.setDelegate(incomingPlayer)
                setActive(incomingPlayer)
            }

            outgoingPlayer.stop()
            outgoingPlayer.clearMediaItems()
            outgoingFadeProc.setVolume(1.0f)
            incomingFadeProc.setVolume(1.0f)
            incomingPlayer.volume = 1.0f

            forwardingPlayer.suppressPlaybackEnded = false
        }
    }

    override fun pause() {
        remote?.let {
            it.pause()
            _isPlaying.value = false
            return
        }
        activePlayer.pause()
        _isPlaying.value = false
    }

    override fun resume() {
        remote?.let {
            it.play()
            _isPlaying.value = true
            return
        }
        activePlayer.play()
        _isPlaying.value = true
    }

    override fun seekTo(positionMs: Long) {
        remote?.let {
            it.seekTo(positionMs)
            _playbackPosition.value = positionMs
            return
        }
        activePlayer.seekTo(positionMs)
        _playbackPosition.value = positionMs
    }

    override fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        equalizerProcessorA.setBandGain(bandIndex, gainDb)
        equalizerProcessorB.setBandGain(bandIndex, gainDb)
    }

    override fun setCrossfadeDuration(seconds: Int) {
        crossfadeDurationSeconds = seconds.coerceIn(0, 15)
    }

    fun release() {
        crossfadeJob?.cancel()
        tickerJob?.cancel()
        playerA.release()
        playerB.release()
    }

    private companion object {
        const val URL_CACHE_SIZE = 64
        const val START_BUFFER_MS = 750
        const val REBUFFER_MS = 1_500
        const val MAX_AUTO_SKIPS = 3
        const val RESTART_THRESHOLD_MS = 3_000L
        const val END_LEAD_MS = 300L
        const val HANDOFF_TIMEOUT_MS = 10_000L
        const val HANDOFF_SEEK_TIMEOUT_MS = 2_000L
        const val HANDOFF_POLL_MS = 20L
        const val HANDOFF_LEAD_MS = 60L
        const val HANDOFF_ALIGN_TRIES = 3
        const val HANDOFF_BLEND_MS = 160L
        const val HANDOFF_BLEND_STEP_MS = 10L
        const val HANDOFF_TOLERANCE_MS = 40L
        const val MANUAL_FADE_MS = 250L
        const val MAX_CUT_DB = 12f
        const val MAX_BOOST_DB = 6f
        const val SKIP_SETTLE_MS = 180L
        const val SKIP_RAPID_MS = 700L
        const val PREFETCH_AHEAD = 2
        const val WARM_BYTES = 400_000L
        const val SWEEP_MIN_MS = 1_500L
        const val FADE_STEPS = 20
        const val FADE_STEP_MS = 150L
        val ITAG_PARAM = Regex("[?&]itag=(\\d+)")
    }
}
