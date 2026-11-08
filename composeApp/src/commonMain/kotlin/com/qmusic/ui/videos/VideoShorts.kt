package com.qmusic.ui.videos

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
            Box(Modifier.fillMaxSize(), Alignment.Center) { Loading(Modifier.size(64.dp)) }
        }
        if (failed) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(s.streamError, color = Color.White, fontSize = 14.sp) }
        if (active && quality != null && !isPlaying && !buffering && !failed) {
            Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), Alignment.Center) {
                Icon(QMusicIcons.Play, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // A dark foot under the text, so it reads on any picture.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(
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
                Text(item.channelName ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ShortAction(if (item.id in liked) QMusicIcons.HeartFilled else QMusicIcons.Heart, s.like, tint = if (item.id in liked) VideoColors.Red else Color.White) {
                if (!controller.toggleLikeId(item.id)) onSignIn()
            }
            ShortAction(QMusicIcons.Share, s.share) { onShare("https://youtube.com/shorts/${item.id}") }
        }
        if (active && duration > 0) {
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(VideoColors.Red))
            }
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
