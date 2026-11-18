package com.qmusic.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.theme.QMusicIcons

/**
 * Shorts the way YouTube shows them: one upright clip fills the screen, swipe up for the next, tap to pause, and the
 * like and share buttons down the right side. Only the clip on screen is loaded; it plays over and over.
 */
@Composable
fun ShortsPage(
    controller: VideosController,
    playback: VideoPlayback?,
    s: VideoStrings,
    showBack: Boolean,
    onPauseMusic: () -> Unit,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.shorts.collectAsState()
    // A clip of the section owns the player now: a video that was playing small is done.
    LaunchedEffect(Unit) { controller.closeWatch() }
    DisposableEffect(playback) {
        onDispose {
            playback?.setLooping(false)
            playback?.pause()
        }
    }
    if (state.items.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            if (state.loading) Loading() else MessageBlock(s.nothingHere)
        }
        return
    }
    val pager = rememberPagerState(initialPage = state.index.coerceIn(0, state.items.lastIndex)) { state.items.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { controller.setShortIndex(it) } }
    // The first seconds of the next two clips are read ahead: a swipe then shows the picture at once.
    LaunchedEffect(pager.currentPage, state.items.size) {
        val from = pager.currentPage
        for (i in from + 1..from + 2) {
            val next = state.items.getOrNull(i) ?: continue
            controller.streamFor(next.id)?.let { playback?.preload(it) }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { state.items[it].id }) { page ->
            ShortItem(
                item = state.items[page],
                active = pager.currentPage == page,
                controller = controller,
                playback = playback,
                s = s,
                onPauseMusic = onPauseMusic,
                onShare = onShare,
                onSignIn = onSignIn,
            )
        }
        if (showBack) {
            IconButton(onClick = { controller.pop() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(QMusicIcons.Back, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun ShortItem(
    item: VideoItem,
    active: Boolean,
    controller: VideosController,
    playback: VideoPlayback?,
    s: VideoStrings,
    onPauseMusic: () -> Unit,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    var quality by remember(item.id) { mutableStateOf<VideoQuality?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val liked by controller.likedIds.collectAsState()
    val subscribedIds by controller.subscribedChannelIds.collectAsState()
    if (active && playback != null) {
        LaunchedEffect(item.id) {
            failed = false
            val q = controller.streamFor(item.id)
            quality = q
            if (q == null) {
                failed = true
            } else {
                onPauseMusic()
                playback.setLooping(true)
                playback.load(q)
            }
        }
    }
    val isPlaying = playback?.isPlaying?.collectAsState()?.value ?: false
    val buffering = playback?.buffering?.collectAsState()?.value ?: false
    val position = playback?.positionMs?.collectAsState()?.value ?: 0L
    val duration = playback?.durationMs?.collectAsState()?.value ?: 0L
    var seekHeld by remember(item.id) { mutableStateOf<Float?>(null) }
    // The clip's small pictures, for winding it.
    var preview by remember(item.id) { mutableStateOf<com.qmusic.engine.models.PreviewFrames?>(null) }
    if (active) LaunchedEffect(item.id) { preview = controller.shortPreview(item.id) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (active) playback?.togglePlay() },
    ) {
        if (item.thumbnail != null && !(active && quality != null && isPlaying)) {
            AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (active && quality != null && playback != null) VideoPlaybackSurface(playback, Modifier.fillMaxSize(), fill = true)

        if (active && (quality == null && !failed || buffering && !isPlaying)) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { PlayerSpinner() }
        }
        if (failed) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(s.streamError, color = Color.White, fontSize = 14.sp) }
        if (active && quality != null && !isPlaying && !buffering && !failed) {
            Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), Alignment.Center) {
                Icon(QMusicIcons.Play, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // A dark foot under the text, so it reads on any picture. Hidden, with the text and the buttons, while the
        // line is held: only the picture and the time are on screen then.
        if (seekHeld == null) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        if (seekHeld == null) Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.78f).padding(start = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = item.channelId != null) {
                    item.channelId?.let { controller.push(VideoRoute.Channel(it, item.channelName)) }
                },
            ) {
                Avatar(item.channelAvatar, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(item.channelName ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                item.channelId?.let { channelId ->
                    val subscribed = channelId in subscribedIds
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (subscribed) s.subscribed else s.subscribe,
                        color = if (subscribed) Color.White else Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (subscribed) Color.White.copy(alpha = 0.25f) else Color.White)
                            .clickable { if (!controller.toggleSubscribeChannel(channelId)) onSignIn() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Text(item.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (seekHeld == null) Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ShortAction(if (item.id in liked) QMusicIcons.HeartFilled else QMusicIcons.Heart, s.like, tint = if (item.id in liked) VideoColors.Red else Color.White) {
                if (!controller.toggleLikeId(item.id)) onSignIn()
            }
            ShortAction(VideoIcons.Comment, s.comments) { controller.openShortComments(item) }
            ShortAction(QMusicIcons.Share, s.share) { onShare("https://youtube.com/shorts/${item.id}") }
        }
        if (active && duration > 0 && playback != null) {
            seekHeld?.let { f -> ShortSeekPreview(f, duration, preview) }
            ShortSeekBar(position, duration, playback, seekHeld, { seekHeld = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ShortAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Winding a clip, as on YouTube: the picture dims a little, the clip's picture of that moment floats over the finger
 * with the time big under it.
 */
@Composable
private fun ShortSeekPreview(fraction: Float, duration: Long, preview: com.qmusic.engine.models.PreviewFrames?) {
    val at = (fraction * duration).toLong()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) {
        val frame = preview?.frameAt(at)
        // Upright clips have upright pictures: a frame of the same shape, as wide as a fifth of the screen at least.
        val frameWidth = maxOf(maxWidth * 0.26f, 96.dp)
        val frameHeight = if (preview != null && preview.frameWidth > 0) frameWidth * (preview.frameHeight.toFloat() / preview.frameWidth) else 0.dp
        val columnWidth = maxOf(frameWidth, 120.dp)
        val x = (maxWidth * fraction - columnWidth / 2).coerceIn(0.dp, (maxWidth - columnWidth).coerceAtLeast(0.dp))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = x).padding(bottom = 34.dp).width(columnWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (preview != null && frame != null) {
                ShortFrame(preview, frame, frameWidth, frameHeight)
                Spacer(Modifier.height(10.dp))
            }
            Text(clock(at), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** One small picture, cut out of YouTube's sheet of them. */
@Composable
private fun ShortFrame(
    preview: com.qmusic.engine.models.PreviewFrames,
    frame: com.qmusic.engine.models.PreviewFrames.Frame,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = with(density) { width.toPx() } / preview.frameWidth
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(1.5.dp, Color.White, RoundedCornerShape(10.dp)),
    ) {
        AsyncImage(
            model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(frame.sheetUrl)
                .size(coil3.size.Size.ORIGINAL)
                .build(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.FixedScale(scale),
            alignment = Alignment.TopStart,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { androidx.compose.ui.unit.IntOffset((-frame.left * scale).toInt(), (-frame.top * scale).toInt()) }
                .requiredSize(
                    with(density) { (preview.frameWidth * preview.framesPerRow * scale).toDp() },
                    with(density) { (preview.frameHeight * preview.framesPerColumn * scale).toDp() },
                ),
        )
    }
}

/**
 * The thin line along the foot of a clip, as on YouTube: how far the clip is; a tap on it jumps there, a drag along it
 * winds the clip (it lands where the finger is let go). Held, the line thickens and a knob sits under the finger.
 */
@Composable
private fun ShortSeekBar(position: Long, duration: Long, playback: VideoPlayback, held: Float?, onHeld: (Float?) -> Unit, modifier: Modifier) {
    val fraction = held ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    // The gesture outlives this composition's values: it reads the latest ones.
    val latestHeld by androidx.compose.runtime.rememberUpdatedState(held)
    val latestDuration by androidx.compose.runtime.rememberUpdatedState(duration)
    val thickness by androidx.compose.animation.core.animateDpAsState(if (held != null) 4.dp else 2.dp, label = "shortLine")
    val knob by androidx.compose.animation.core.animateDpAsState(if (held != null) 7.dp else 0.dp, label = "shortKnob")
    // A tall touch area around the thin line (the line alone is too thin to hit); the line runs along its foot.
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { at -> playback.seekTo((at.x / size.width * latestDuration).toLong().coerceIn(0, latestDuration)) }
            }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        last = (at.x / size.width).coerceIn(0f, 1f)
                        onHeld(last)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        onHeld(last)
                    },
                    onDragEnd = {
                        val to = latestHeld ?: last
                        playback.seekTo((to * latestDuration).toLong().coerceIn(0, latestDuration))
                        onHeld(null)
                    },
                    onDragCancel = { onHeld(null) },
                )
            },
    ) {
        val h = thickness.toPx()
        val y = size.height - 8.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h))
        drawRect(VideoColors.Red, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * fraction, h))
        if (knob > 0.dp) drawCircle(VideoColors.Red, radius = knob.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * fraction, y))
    }
}
