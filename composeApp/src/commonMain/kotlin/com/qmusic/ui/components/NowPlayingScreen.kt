package com.qmusic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.player.RepeatMode
import com.qmusic.player.SleepTimerState
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicLogo
import com.qmusic.ui.viewmodel.LyricsState
import kotlinx.coroutines.launch

/**
 * The full-screen player, laid out like YouTube Music's: a Song / Video switch at the top, the cover (or the music
 * video itself, when the song has one), title and artist, a row of quick actions, the seek bar, the transport
 * controls and an "Up next" handle at the bottom. Swipe sideways for lyrics and the queue.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    track: SongItem,
    isPlaying: Boolean,
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    durationMs: Long,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    shuffle: Boolean,
    repeat: RepeatMode,
    sleepTimer: SleepTimerState,
    queue: List<SongItem>,
    queueIndex: Int,
    lyrics: LyricsState,
    crossfadeSeconds: Int,
    videoMode: Boolean,
    videoPreferred: Boolean,
    onSetVideoPreferred: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSheetDrag: (Float) -> Unit,
    onSheetDragStopped: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onMore: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onArtistClick: (() -> Unit)?,
    onToggleDownload: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onSleepAtEndOfTrack: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    onLoadLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    /** Controls fade in over the last part of the drag, like YouTube Music's. */
    contentAlpha: () -> Float = { 1f },
    /** The real cover only shows once the sheet has settled; a morphing cover stands in while dragging. */
    coverAlpha: () -> Float = { 1f },
    onCoverPositioned: (Rect) -> Unit = {},
    /** The sheet's backdrop fades in over the first part of the drag so the mini player shows through. */
    backdropAlpha: () -> Float = { 1f },
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var showSleepDialog by remember { mutableStateOf(false) }
    val artwork = track.thumbnail ?: track.thumbnails.lastOrNull()?.url
    val showVideo = track.hasVideo && videoMode

    LaunchedEffect(pagerState.currentPage, track.id) {
        if (pagerState.currentPage == 1) onLoadLyrics()
    }

    val dragState = rememberDraggableState(onSheetDrag)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha() }
                .background(BgRoot),
        ) {
        // Backdrop: a small copy of the cover, blown up and blurred, so every song brings its own colors.
        if (artwork != null) {
            AsyncImage(
                model = artwork.upscaleThumbnail(256),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .alpha(0.55f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.30f),
                        0.55f to BgRoot.copy(alpha = 0.82f),
                        1f to BgRoot,
                    ),
                ),
        )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            TopBar(
                hasVideo = track.hasVideo,
                videoSelected = videoPreferred,
                onSelectVideo = onSetVideoPreferred,
                onDismiss = {
                    // From the lyrics / queue pages the chevron first returns to the song page.
                    if (pagerState.currentPage != 0) {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    } else {
                        onDismiss()
                    }
                },
                onMore = onMore,
                modifier = Modifier
                    .graphicsLayer { alpha = contentAlpha() }
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { onSheetDragStopped(it) }),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                when (page) {
                    0 -> MediaPage(
                        trackId = track.id,
                        artwork = artwork,
                        title = track.title,
                        isPlaying = isPlaying,
                        showVideo = showVideo,
                        coverAlpha = coverAlpha,
                        onCoverPositioned = onCoverPositioned,
                        modifier = Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { onSheetDragStopped(it) }),
                    )

                    1 -> LyricsPage(lyrics)
                    else -> QueuePage(
                        queue = queue,
                        queueIndex = queueIndex,
                        isPlaying = isPlaying,
                        visible = pagerState.currentPage == 2,
                        onClick = onQueueItemClick,
                        onRemove = onQueueItemRemove,
                    )
                }
            }

            Column(modifier = Modifier.graphicsLayer { alpha = contentAlpha() }) {
                Spacer(Modifier.height(12.dp))
    
                TrackInfo(
                    track = track,
                    onArtistClick = onArtistClick,
                )
    
                Spacer(Modifier.height(10.dp))
    
                ChipsRow(
                    isFavorite = isFavorite,
                    lyricsOpen = pagerState.currentPage == 1,
                    isDownloaded = isDownloaded,
                    downloadProgress = downloadProgress,
                    sleepTimer = sleepTimer,
                    crossfadeSeconds = crossfadeSeconds,
                    onToggleFavorite = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite()
                    },
                    onLyrics = {
                        scope.launch { pagerState.animateScrollToPage(if (pagerState.currentPage == 1) 0 else 1) }
                    },
                    onAddToPlaylist = onAddToPlaylist,
                    onDownload = onToggleDownload,
                    onSleep = { showSleepDialog = true },
                    onEqualizer = onOpenEqualizer,
                )
    
                SeekBar(
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    fullyStored = isDownloaded,
                    durationMs = if (durationMs > 0) durationMs else (track.duration ?: 0) * 1000L,
                    onSeekTo = onSeekTo,
                )
    
                Controls(
                    isPlaying = isPlaying,
                    shuffle = shuffle,
                    repeat = repeat,
                    onTogglePlayPause = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTogglePlayPause()
                    },
                    onPrevious = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPrevious()
                    },
                    onNext = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNext()
                    },
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                )
    
                UpNextHandle(
                    next = queue.getOrNull(queueIndex + 1),
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                )
            }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            active = sleepTimer.active,
            onMinutes = {
                onSetSleepTimer(it)
                showSleepDialog = false
            },
            onEndOfTrack = {
                onSleepAtEndOfTrack()
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false },
        )
    }
}

/** Chevron, the Song / Video switch (only for songs that have a clip) and the "more" menu. */
@Composable
private fun TopBar(
    hasVideo: Boolean,
    videoSelected: Boolean,
    onSelectVideo: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = QMusicIcons.ChevronDown,
                contentDescription = "Collapse player",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (hasVideo) {
                SongVideoSwitch(videoSelected = videoSelected, onSelectVideo = onSelectVideo)
            } else {
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        IconButton(onClick = onMore) {
            Icon(
                imageVector = QMusicIcons.More,
                contentDescription = "More",
                tint = Color.White,
            )
        }
    }
}

/** The headphones / video pill of YouTube Music: one side is filled, the other is a tap target. */
@Composable
private fun SongVideoSwitch(videoSelected: Boolean, onSelectVideo: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
    ) {
        SwitchSide(QMusicIcons.Headphones, "Song", selected = !videoSelected) { onSelectVideo(false) }
        SwitchSide(QMusicIcons.VideoPlay, "Video", selected = videoSelected) { onSelectVideo(true) }
    }
}

@Composable
private fun SwitchSide(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The cover, or the clip over its cover while the picture loads. */
@Composable
private fun MediaPage(
    trackId: String,
    artwork: String?,
    title: String,
    isPlaying: Boolean,
    showVideo: Boolean,
    coverAlpha: () -> Float,
    onCoverPositioned: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The cover breathes: it eases down while paused and springs back when the music starts.
    val scale by animateFloatAsState(
        targetValue = if (isPlaying || showVideo) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "artworkScale",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (showVideo) 16f / 9f else 1f)
                .onGloballyPositioned { onCoverPositioned(it.boundsInRoot()) }
                .graphicsLayer {
                    alpha = coverAlpha()
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 48f
                    shape = RoundedCornerShape(if (showVideo) 12.dp else 24.dp)
                    clip = true
                }
                .background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                AsyncImage(
                    model = artwork.upscaleThumbnail(800),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                QMusicLogo(size = 96.dp, tint = Color.White.copy(alpha = 0.8f))
            }
            if (showVideo) {
                VideoSurface(trackKey = trackId, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LyricsPage(state: LyricsState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            LyricsState.Idle, LyricsState.Loading -> CircularProgressIndicator(color = Color.White)

            LyricsState.Unavailable -> Text(
                text = "No lyrics for this song",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
            )

            is LyricsState.Available -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.lyrics.text,
                    style = MaterialTheme.typography.titleLarge.copy(lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                state.lyrics.source?.let { source ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun QueuePage(
    queue: List<SongItem>,
    queueIndex: Int,
    isPlaying: Boolean,
    visible: Boolean,
    onClick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(visible, queueIndex) {
        if (visible && queueIndex in queue.indices) listState.animateScrollToItem((queueIndex - 1).coerceAtLeast(0))
    }

    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The queue is empty", color = Color.White.copy(alpha = 0.6f))
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(queue, key = { index, song -> "$index:${song.id}" }) { index, song ->
            val current = index == queueIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable { onClick(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant),
                ) {
                    val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current) Color.White else Color.White.copy(alpha = if (index < queueIndex) 0.45f else 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (current) {
                    Icon(
                        imageVector = if (isPlaying) QMusicIcons.Volume else QMusicIcons.Pause,
                        contentDescription = "Playing now",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = QMusicIcons.Close,
                            contentDescription = "Remove from queue",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackInfo(
    track: SongItem,
    onArtistClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        Text(
            text = track.artists.joinToString { it.name }.ifEmpty { "Unknown artist" },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier,
        )
    }
}

/** Pill-shaped quick actions in one scrollable row, as on YouTube Music. */
@Composable
private fun ChipsRow(
    isFavorite: Boolean,
    lyricsOpen: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    sleepTimer: SleepTimerState,
    crossfadeSeconds: Int,
    onToggleFavorite: () -> Unit,
    onLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSleep: () -> Unit,
    onEqualizer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionChip(
            icon = if (isFavorite) QMusicIcons.HeartFilled else QMusicIcons.Heart,
            label = if (isFavorite) "Liked" else "Like",
            active = isFavorite,
            onClick = onToggleFavorite,
        )
        ActionChip(QMusicIcons.Lyrics, "Lyrics", active = lyricsOpen, onClick = onLyrics)
        ActionChip(QMusicIcons.PlaylistAdd, "Save", active = false, onClick = onAddToPlaylist)
        if (downloadProgress != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        } else {
            ActionChip(
                icon = if (isDownloaded) QMusicIcons.DownloadDone else QMusicIcons.Download,
                label = if (isDownloaded) "Downloaded" else "Download",
                active = isDownloaded,
                onClick = onDownload,
            )
        }
        ActionChip(
            icon = QMusicIcons.Moon,
            label = when {
                !sleepTimer.active -> "Sleep"
                sleepTimer.atEndOfTrack -> "End of song"
                else -> formatTime(sleepTimer.remainingMs)
            },
            active = sleepTimer.active,
            onClick = onSleep,
        )
        ActionChip(
            icon = QMusicIcons.Tune,
            label = if (crossfadeSeconds > 0) "EQ · Fade ${crossfadeSeconds}s" else "Equalizer",
            active = false,
            onClick = onEqualizer,
        )
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/**
 * YouTube Music's progress bar: a thin line with the played part in white, the part already loaded in a lighter
 * tone, and a small dot that grows while it is held. Tapping anywhere on it jumps there. The position ticks every
 * 250 ms, so it is read inside the drawing only and nothing above this bar recomposes.
 */
@Composable
private fun SeekBar(
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    fullyStored: Boolean,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val position = positionMs()
    val played = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragFraction else played
    val loaded = when {
        durationMs <= 0 -> 0f
        fullyStored -> 1f
        else -> (bufferedMs().toFloat() / durationMs).coerceIn(shown, 1f)
    }
    val thumbRadius by animateFloatAsState(if (dragging) 9f else 6f, label = "thumb")

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeekTo((dragFraction * durationMs).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        dragFraction = (dragFraction + delta / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val inset = 8.dp.toPx()
            val width = size.width - inset * 2
            val centerY = size.height / 2
            val stroke = 3.dp.toPx()
            val cap = CornerRadius(stroke / 2, stroke / 2)
            fun track(fraction: Float, color: Color) {
                if (fraction <= 0f) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, centerY - stroke / 2),
                    size = Size(width * fraction, stroke),
                    cornerRadius = cap,
                )
            }
            track(1f, Color.White.copy(alpha = 0.16f))
            track(loaded, Color.White.copy(alpha = 0.34f))
            track(shown, Color.White)
            if (durationMs > 0) drawCircle(Color.White, radius = thumbRadius.dp.toPx(), center = Offset(inset + width * shown, centerY))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val elapsed = if (dragging) (dragFraction * durationMs).toLong() else position
            Text(formatTime(elapsed), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Controls(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeButton(
            icon = QMusicIcons.Shuffle,
            description = if (shuffle) "Shuffle on" else "Shuffle off",
            active = shuffle,
            onClick = onToggleShuffle,
        )
        IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(QMusicIcons.Previous, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(76.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = BgRoot),
        ) {
            Icon(
                imageVector = if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(QMusicIcons.Next, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        ModeButton(
            icon = if (repeat == RepeatMode.ONE) QMusicIcons.RepeatOne else QMusicIcons.Repeat,
            description = when (repeat) {
                RepeatMode.OFF -> "Repeat off"
                RepeatMode.ALL -> "Repeat all"
                RepeatMode.ONE -> "Repeat one"
            },
            active = repeat != RepeatMode.OFF,
            onClick = onCycleRepeat,
        )
    }
}

/** A toggle that glows white with a dot under it while on. */
@Composable
private fun ModeButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color.Transparent),
        )
    }
}

/** A grab bar with the next song's title, as at the bottom of YouTube Music's player. */
@Composable
private fun UpNextHandle(next: SongItem?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(top = 10.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = next?.let { "Up next · ${it.title}" } ?: "Up next",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SleepTimerDialog(
    active: Boolean,
    onMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onMinutes(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                TextButton(onClick = onEndOfTrack, modifier = Modifier.fillMaxWidth()) {
                    Text("End of this song", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                if (active) {
                    TextButton(onClick = { onMinutes(0) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn off timer", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = (total % 60).toString().padStart(2, '0')
    val minutes = total / 60
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
