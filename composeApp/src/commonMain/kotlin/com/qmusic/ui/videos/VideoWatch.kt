package com.qmusic.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.delay

/** The page of one video: the picture on top, then what it is, what the account can do with it, comments, what's next. */
@Composable
fun WatchPage(
    controller: VideosController,
    s: VideoStrings,
    onPauseMusic: () -> Unit,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watch by controller.watch.collectAsState()
    val w = watch ?: return
    key(w.video.id) {
        val playback = rememberVideoPlayback()
        Column(modifier = modifier.fillMaxSize().background(VideoColors.Background)) {
            Box(modifier = Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
                PlayerBox(w, playback, s, controller, onPauseMusic)
            }
            WatchDetails(controller, w, s, onShare, onSignIn)
        }
    }
}

@Composable
private fun PlayerBox(w: WatchState, playback: VideoPlayback?, s: VideoStrings, controller: VideosController, onPauseMusic: () -> Unit) {
    var loadedFor by remember { mutableStateOf<VideoQuality?>(null) }
    LaunchedEffect(w.quality, playback) {
        val q = w.quality ?: return@LaunchedEffect
        val p = playback ?: return@LaunchedEffect
        // Another quality of the same video carries on from where it was.
        val start = if (loadedFor != null) p.positionMs.value else 0L
        loadedFor = q
        onPauseMusic()
        p.load(q, start)
    }

    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        val isPlaying = playback?.isPlaying?.collectAsState()?.value ?: false
        val buffering = playback?.buffering?.collectAsState()?.value ?: false
        val playError = playback?.error?.collectAsState()?.value
        if (playback != null && w.quality != null) {
            VideoPlaybackSurface(playback, Modifier.fillMaxSize())
        } else if (w.video.thumbnail != null) {
            AsyncImage(model = w.video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }

        when {
            playback == null -> Overlay(s.desktopWatch)
            w.streamError || playError != null -> Overlay(s.streamError, s.retry) { controller.retryStream() }
            w.streamLoading || (buffering && !isPlaying) -> Box(Modifier.fillMaxSize(), Alignment.Center) { Loading(Modifier.size(64.dp)) }
        }
        if (playback != null && w.quality != null && !w.streamError) {
            PlayerControls(playback, w, s, controller)
        }
    }
}

@Composable
private fun Overlay(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp)
        if (action != null && onAction != null) {
            Text(
                action,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun PlayerControls(playback: VideoPlayback, w: WatchState, s: VideoStrings, controller: VideosController) {
    var visible by remember { mutableStateOf(true) }
    var qualityMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val isPlaying by playback.isPlaying.collectAsState()
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    val ended by playback.ended.collectAsState()

    // Controls fade away while playing, and stay while paused or while a menu is open.
    LaunchedEffect(visible, isPlaying, qualityMenu, speedMenu, scrubbing) {
        if (visible && isPlaying && !qualityMenu && !speedMenu && scrubbing == null) {
            delay(HIDE_CONTROLS_MS)
            visible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { visible = !visible },
    ) {
        if (visible || ended) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            IconButton(onClick = { playback.togglePlay() }, modifier = Modifier.align(Alignment.Center).size(64.dp)) {
                Icon(
                    imageVector = if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ControlText(speedLabel(speed)) { speedMenu = true }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEEDS.forEach { value ->
                            DropdownMenuItem(text = { Text(speedLabel(value)) }, onClick = {
                                speed = value
                                playback.setSpeed(value)
                                speedMenu = false
                            })
                        }
                    }
                }
                if (w.qualities.size > 1) {
                    Box {
                        ControlText(w.quality?.label ?: s.quality) { qualityMenu = true }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            w.qualities.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(if (q == w.quality) "${q.label}  ✓" else q.label) },
                                    onClick = {
                                        qualityMenu = false
                                        controller.selectQuality(q)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!w.isLive()) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(clock(scrubbing?.toLong() ?: position), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = (scrubbing ?: position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                        onValueChange = { scrubbing = it },
                        onValueChangeFinished = {
                            scrubbing?.let { playback.seekTo(it.toLong()) }
                            scrubbing = null
                        },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = VideoColors.Red, activeTrackColor = VideoColors.Red, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp),
                    )
                    Text(clock(duration), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun WatchState.isLive() = quality?.isLive == true || video.isLive || details?.isLive == true

@Composable
private fun ControlText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private const val HIDE_CONTROLS_MS = 3_000L

private fun speedLabel(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(n: Long) = if (n < 10) "0$n" else "$n"
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

@Composable
private fun WatchDetails(controller: VideosController, w: WatchState, s: VideoStrings, onShare: (String) -> Unit, onSignIn: () -> Unit) {
    var descriptionOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val details = w.details
    val title = details?.title ?: w.video.title

    LaunchedEffect(listState, w.relatedContinuation) {
        if (w.relatedContinuation == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }.collect { near -> if (near) controller.loadMoreRelated() }
    }
    LaunchedEffect(commentsOpen) { if (commentsOpen) controller.loadComments() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            Column(modifier = Modifier.fillMaxWidth().clickable { descriptionOpen = !descriptionOpen }.padding(12.dp)) {
                Text(title, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, maxLines = if (descriptionOpen) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(details?.views ?: w.video.views, details?.date ?: w.video.published).joinToString(" · ")
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(meta, color = VideoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (descriptionOpen) s.showLess else s.showMore, color = VideoColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (descriptionOpen && !details?.description.isNullOrBlank()) {
                    Text(details!!.description!!, color = VideoColors.Text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item(key = "channel") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable(enabled = (details?.channelId ?: w.video.channelId) != null) {
                        (details?.channelId ?: w.video.channelId)?.let { controller.push(VideoRoute.Channel(it, details?.channelName ?: w.video.channelName)) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(details?.channelAvatar ?: w.video.channelAvatar, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(details?.channelName ?: w.video.channelName ?: "", color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        details?.subscribers?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1) }
                    }
                }
                Text(
                    if (w.subscribed) s.subscribed else s.subscribe,
                    color = if (w.subscribed) VideoColors.Text else Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (w.subscribed) VideoColors.Chip else VideoColors.ChipSelected)
                        .clickable { controller.toggleSubscribe() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill(
                    label = details?.likes ?: s.like,
                    icon = if (w.liked) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                    active = w.liked,
                ) { controller.toggleLike() }
                ActionPill(label = if (w.saved) s.saved else s.save, icon = QMusicIcons.PlaylistAdd, active = w.saved) { controller.toggleSaved() }
                ActionPill(label = s.share, icon = QMusicIcons.Share, active = false) { onShare("https://youtu.be/${w.video.id}") }
            }
            if (w.signInPrompt) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(VideoColors.Surface).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.signInToDoThis, color = VideoColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(s.signIn, color = VideoColors.Red, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        controller.dismissSignInPrompt()
                        onSignIn()
                    })
                }
            }
        }
        item(key = "comments-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(VideoColors.Surface).clickable { commentsOpen = !commentsOpen }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (w.commentsTotal != null) "${s.comments} · ${w.commentsTotal}" else s.comments,
                    color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Icon(if (commentsOpen) QMusicIcons.ChevronUp else QMusicIcons.ChevronDown, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (commentsOpen) {
            if (w.comments.isEmpty() && w.commentsLoading) item(key = "comments-loading") { Loading() }
            if (w.comments.isEmpty() && w.commentsLoaded && !w.commentsLoading) {
                item(key = "comments-none") { Text(s.noComments, color = VideoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }
            items(w.comments, key = { "c" + it.id }) { c ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Avatar(c.avatar, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(listOfNotNull(c.author, c.published).joinToString(" · "), color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.text, color = VideoColors.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                        val line = listOfNotNull(c.likes?.let { "♥ $it" }, c.replies?.let { "↳ $it" }).joinToString("   ")
                        if (line.isNotEmpty()) Text(line, color = VideoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (w.commentsContinuation != null) {
                item(key = "comments-more") {
                    Text(
                        if (w.commentsLoading) "…" else s.loadMore,
                        color = VideoColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { controller.loadComments() }.padding(16.dp),
                    )
                }
            }
        } else {
            item(key = "related-title") {
                Text(s.related, color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
            }
            if (details == null && !w.detailsError) item(key = "related-loading") { Loading() }
            items(w.related.size, key = { i -> (w.related[i].ids().firstOrNull() ?: "r") + "#" + i }) { i ->
                when (val e = w.related[i]) {
                    is VideoEntry.Video -> VideoRowCard(e.item, s, onClick = { controller.open(e.item) })
                    is VideoEntry.Shorts -> ShortsRow(e.items, s) { controller.open(it) }
                    is VideoEntry.Channel -> ChannelResultRow(e.item) { controller.push(VideoRoute.Channel(e.item.id, e.item.name)) }
                    is VideoEntry.Playlist -> PlaylistResultRow(e.item, s) { controller.push(VideoRoute.Playlist(e.item.id, e.item.title)) }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(VideoColors.Chip).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) VideoColors.Red else VideoColors.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
