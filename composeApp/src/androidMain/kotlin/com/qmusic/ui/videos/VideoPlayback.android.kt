package com.qmusic.ui.videos

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
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
        // A hardware decoder that cannot take a stream (a high profile, an odd film encode) hands it to the next one
        // instead of stopping the video.
        .setRenderersFactory(DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true))
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
    private val _picture = MutableStateFlow(false)
    override val showingPicture: StateFlow<Boolean> = _picture.asStateFlow()
    private val _rebuffers = MutableStateFlow(0)
    override val rebuffers: StateFlow<Int> = _rebuffers.asStateFlow()

    /** Playing had started since the last load, and when the last seek was: a wait after either is not a stall. */
    private var wasReady = false
    private var lastSeekAt = 0L

    private data class LoadedRequest(
        val quality: VideoQuality,
        val startMs: Long,
        val hasSubtitle: Boolean,
        val preferredAudioLanguage: String?,
        val preferredAudioLabel: String?,
    )

    /** What was loaded last: the quality, where it started, whether it carried subtitles, and preferred audio. */
    private var lastLoad: LoadedRequest? = null
    private var pendingAudioLanguage: String? = null
    private var pendingAudioLabel: String? = null

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

    init {
        // The first seconds of a video about to be opened go into the cache this player reads.
        VideoWarm.bytes = { quality -> VideoStreaming.preload(appContext, quality) }
        VideoWarm.prepare = { quality -> prepareAhead(quality) }
        FeedPreviewPlayer.install(appContext)
    }

    /** Loaded ahead (a card touched, nothing open): the stream it holds, until opened or let go. */
    private var ahead: VideoQuality? = null
    private val dropAhead = Runnable {
        if (ahead != null) {
            ahead = null
            lastLoad = null
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun prepareAhead(quality: VideoQuality) {
        if (quality.isLive || player.playWhenReady && player.mediaItemCount > 0) return
        load(quality, 0L, autoPlay = false, caption = null)
        ahead = quality
        handler.removeCallbacks(dropAhead)
        // Touched but not opened (a scroll, a slip): the decoders and the buffer are let go again.
        handler.postDelayed(dropAhead, AHEAD_KEEP_MS)
    }
    private val liveSources = VideoStreaming.network()

    /**
     * A film server that drops a segment now and then is asked again a few more times before the video gives up. Only
     * for films (streams that need their own headers): a YouTube address that has gone stale must fail fast, so a fresh
     * one is looked up instead of waiting through the retries.
     */
    private val filmLoadErrors = DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)
    private val defaultLoadErrors = DefaultLoadErrorHandlingPolicy()

    init {
        VideoBackground.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (VideoBackground.player === player) {
                    VideoBackground.isPlaying.value = isPlaying
                    VideoBackground.onStateChanged?.invoke()
                }
            }

            override fun onRenderedFirstFrame() {
                _picture.value = true
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

            override fun onTracksChanged(tracks: Tracks) {
                applyAudioTrackSelection(tracks, pendingAudioLanguage, pendingAudioLabel)
            }

            override fun onPlayerError(error: PlaybackException) {
                // A subtitle track that cannot be read must not take the video with it: play again without it.
                val last = lastLoad
                if (last != null && last.hasSubtitle) {
                    lastLoad = null
                    load(
                        quality = last.quality,
                        startMs = player.currentPosition,
                        autoPlay = true,
                        caption = null,
                        preferredAudioLanguage = last.preferredAudioLanguage,
                        preferredAudioLabel = last.preferredAudioLabel,
                    )
                    return
                }
                _error.value = error.errorCodeName
            }
        })
        handler.post(ticker)
    }

    override fun load(
        quality: VideoQuality,
        startMs: Long,
        autoPlay: Boolean,
        caption: VideoCaption?,
        preferredAudioLanguage: String?,
        preferredAudioLabel: String?,
    ) {
        handler.removeCallbacks(dropAhead)
        val wasAhead = ahead
        ahead = null
        pendingAudioLanguage = preferredAudioLanguage
        pendingAudioLabel = preferredAudioLabel
        // Opened what was loaded ahead: it only has to start.
        if (wasAhead == quality && startMs == 0L && caption == null && preferredAudioLanguage == null && player.mediaItemCount > 0 && player.playerError == null) {
            player.playWhenReady = autoPlay
            return
        }
        // Another quality (or subtitles) of the same video keeps its picture; a new video waits for its first frame.
        val videoOf = { url: String -> runCatching { android.net.Uri.parse(url).getQueryParameter("id") }.getOrNull() ?: url }
        if (lastLoad == null || videoOf(lastLoad!!.quality.videoUrl) != videoOf(quality.videoUrl)) _picture.value = false
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
        val isHls = quality.isLive || MovieHtmlParser.isHlsCandidateUrl(quality.videoUrl)
        val isDash = quality.videoUrl.contains(".mpd", ignoreCase = true)

        val pictureItem = MediaItem.Builder()
            .setUri(quality.videoUrl)
            .apply {
                if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (isDash) setMimeType(MimeTypes.APPLICATION_MPD)
                if (track != null) setSubtitleConfigurations(listOf(track))
            }
            .build()
        val videoFactory = when {
            quality.headers.isNotEmpty() -> VideoStreaming.networkWithHeaders(quality.headers)
            isHls -> liveSources
            else -> dataSources
        }
        val loadErrors = if (quality.headers.isNotEmpty()) filmLoadErrors else defaultLoadErrors
        val picture: MediaSource = DefaultMediaSourceFactory(videoFactory)
            .setLoadErrorHandlingPolicy(loadErrors)
            .createMediaSource(pictureItem)
        val separateAudioUrl = quality.audioUrl?.takeIf { it.isNotBlank() && !quality.isLive }
        val source: MediaSource = if (separateAudioUrl != null) {
            val audioIsHls = MovieHtmlParser.isHlsCandidateUrl(separateAudioUrl) || isHls
            val audioFactory = when {
                quality.headers.isNotEmpty() -> VideoStreaming.networkWithHeaders(quality.headers)
                audioIsHls -> liveSources
                else -> dataSources
            }
            val audioItem = MediaItem.Builder()
                .setUri(separateAudioUrl)
                .apply { if (audioIsHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            val audioSource: MediaSource = if (audioIsHls) {
                HlsMediaSource.Factory(audioFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(loadErrors)
                    .createMediaSource(audioItem)
            } else {
                ProgressiveMediaSource.Factory(audioFactory).setLoadErrorHandlingPolicy(loadErrors).createMediaSource(audioItem)
            }
            // Align period time offsets and clip durations so separate HLS/DASH/progressive audio & video stay in sync.
            MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, picture, audioSource)
        } else {
            picture
        }
        lastLoad = LoadedRequest(quality, startMs, track != null, preferredAudioLanguage, preferredAudioLabel)
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        val langCandidates = languageCandidates(preferredAudioLanguage)
        if (langCandidates.isNotEmpty()) {
            paramsBuilder.setPreferredAudioLanguages(*langCandidates)
        } else {
            paramsBuilder.setPreferredAudioLanguage(null)
        }
        player.trackSelectionParameters = paramsBuilder.build()
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    override fun selectAudioTrack(language: String?, label: String?) {
        pendingAudioLanguage = language
        pendingAudioLabel = label
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        val langCandidates = languageCandidates(language)
        if (langCandidates.isNotEmpty()) {
            paramsBuilder.setPreferredAudioLanguages(*langCandidates)
        } else {
            paramsBuilder.setPreferredAudioLanguage(null)
        }
        player.trackSelectionParameters = paramsBuilder.build()
        applyAudioTrackSelection(player.currentTracks, language, label)
    }

    private fun applyAudioTrackSelection(tracks: Tracks, language: String?, label: String?) {
        val targetLang = MovieHtmlParser.normalizeHlsLanguage(language, label)
        val targetLabel = label?.trim()?.lowercase()?.removeSuffix(" (orijinal)")?.removeSuffix(" (original)")?.trim()
        if (targetLang == null && targetLabel.isNullOrEmpty()) return

        var bestGroup: Tracks.Group? = null
        var bestIndex = -1
        var bestScore = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                val fmtLang = MovieHtmlParser.normalizeHlsLanguage(fmt.language, fmt.label)
                val fmtLabel = fmt.label?.trim()?.lowercase().orEmpty()
                val langMatches = targetLang != null && fmtLang == targetLang
                val labelMatches = !targetLabel.isNullOrEmpty() && (
                    fmtLabel == targetLabel ||
                        fmtLabel.contains(targetLabel) ||
                        targetLabel.contains(fmtLabel).takeIf { fmtLabel.length >= 2 } == true
                    )
                val score = when {
                    langMatches && labelMatches -> 3
                    labelMatches -> 2
                    langMatches -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                    bestIndex = i
                }
            }
        }
        if (bestGroup != null && bestIndex >= 0 && !bestGroup.isTrackSelected(bestIndex)) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(bestGroup.mediaTrackGroup, bestIndex))
                .build()
        }
    }

    private fun languageCandidates(language: String?): Array<String> =
        when (MovieHtmlParser.normalizeHlsLanguage(language, null)) {
            "tr" -> arrayOf("tr", "tur", "tr-TR")
            "en" -> arrayOf("en", "eng", "en-US", "en-GB")
            else -> language?.trim()?.takeIf { it.isNotEmpty() }?.let { arrayOf(it) } ?: emptyArray()
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
        if (VideoBackground.player === player) {
            VideoBackground.player = null
            VideoBackground.isPlaying.value = false
            VideoBackground.onStateChanged?.invoke()
        }
        player.release()
    }

    private companion object {
        const val TICK_MS = 400L
        const val SEEK_GRACE_MS = 2_500L
        const val AHEAD_KEEP_MS = 8_000L
        const val LOAD_RETRIES = 6
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
    val captionScale by VideoCaptionStyle.scale.collectAsState()
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
                subtitleView?.let { v -> styleCaptions(v, captionScale) }
            }
        },
        update = {
            it.player = exo
            it.subtitleView?.let { v -> styleCaptions(v, captionScale) }
            it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { it.player = null },
    )
}

/** Subtitles readable on any picture: white letters with a dark outline on a soft dark band, at the chosen size. */
@androidx.media3.common.util.UnstableApi
private fun styleCaptions(view: SubtitleView, scale: Float) {
    view.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            0x88000000.toInt(),
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        ),
    )
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
}
