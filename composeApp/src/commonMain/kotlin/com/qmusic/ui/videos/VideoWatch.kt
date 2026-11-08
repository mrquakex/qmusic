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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.BoxWithConstraints
import com.qmusic.engine.video.VideoComment
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
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/** The page of one video: the picture on top, then what it is, what the account can do with it, comments, what's next. */
@Composable
fun WatchPage(
    controller: VideosController,
    playback: VideoPlayback?,
    s: VideoStrings,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watch by controller.watch.collectAsState()
    val fullscreen by controller.fullscreen.collectAsState()
    val pip = rememberInPictureInPicture()
    val w = watch ?: return
    val seek: (Long) -> Unit = { playback?.seekTo(it) }
    when {
        // The floating window shows the picture and nothing else.
        pip -> Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            if (playback != null) VideoPlaybackSurface(playback, Modifier.fillMaxSize())
        }
        fullscreen -> {
            var total by remember { mutableStateOf(0f) }
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                total += dy
                            },
                            onDragEnd = { if (total > SWIPE_PX) controller.setFullscreen(false) },
                        )
                    },
            ) {
                PlayerBox(w, playback, s, controller, true, Modifier.fillMaxSize())
            }
        }
        else -> BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // Dragging the picture down shrinks the video into the small player, as on YouTube.
            val offset = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            var height by remember { mutableStateOf(1f) }
            var total by remember { mutableStateOf(0f) }
            val wide = maxWidth >= WIDE_WATCH && maxHeight >= 600.dp
            val player: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { total = 0f },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    total += dy
                                    scope.launch { offset.snapTo(total.coerceAtLeast(0f)) }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        // Up opens full screen, down shrinks the video into the small player.
                                        if (total < -SWIPE_PX) {
                                            offset.snapTo(0f)
                                            controller.setFullscreen(true)
                                        } else if (offset.value > height * 0.18f) {
                                            offset.animateTo(height, tween(150))
                                            controller.pop()
                                            offset.snapTo(0f)
                                        } else {
                                            offset.animateTo(0f, tween(150))
                                        }
                                    }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f, tween(150)) } },
                            )
                        },
                ) {
                    PlayerBox(w, playback, s, controller, false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
            }
            val layer = Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    translationY = offset.value
                    alpha = 1f - (offset.value / height).coerceIn(0f, 1f) * 0.6f
                }
                .background(VideoColors.Background)
            if (wide) {
                // A tablet: the video and what is about it on the left, what comes next down the right.
                Row(modifier = layer) {
                    Column(modifier = Modifier.weight(1.7f)) {
                        player()
                        WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = false)
                    }
                    RelatedColumn(controller, w, s, Modifier.weight(1f).statusBarsPadding())
                }
            } else {
                Column(modifier = layer) {
                    player()
                    WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = true)
                }
            }
        }
    }
}

@Composable
private fun PlayerBox(w: WatchState, playback: VideoPlayback?, s: VideoStrings, controller: VideosController, fullscreen: Boolean, modifier: Modifier) {
    Box(modifier = modifier.background(Color.Black)) {
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
            PlayerControls(playback, w, s, controller, fullscreen)
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

internal fun WatchState.isLive() = quality?.isLive == true || video.isLive || details?.isLive == true

private const val SWIPE_PX = 120f

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(n: Long) = if (n < 10) "0$n" else "$n"
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

private val WIDE_WATCH = 840.dp

@Composable
private fun WatchDetails(
    controller: VideosController,
    w: WatchState,
    s: VideoStrings,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    onSeek: (Long) -> Unit,
    showRelated: Boolean,
) {
    var descriptionOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val details = w.details
    val title = details?.title ?: w.video.title
    val signedIn by controller.signedIn.collectAsState()

    if (showRelated) {
        LaunchedEffect(listState, w.relatedContinuation) {
            if (w.relatedContinuation == null) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 4
            }.collect { near -> if (near) controller.loadMoreRelated() }
        }
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
            }
        }
        if (descriptionOpen && !details?.description.isNullOrBlank()) {
            item(key = "description") {
                val description = details!!.description!!
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    val chapters = remember(description) { chaptersOf(description) }
                    if (chapters.isNotEmpty()) {
                        Text(s.chapters, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        chapters.forEach { chapter ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { onSeek(chapter.timeMs) }.padding(vertical = 5.dp)) {
                                Text(clock(chapter.timeMs), color = VideoColors.Link, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                                Text(chapter.title, color = VideoColors.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    // Every time in the description is a link that jumps there.
                    val linked = remember(description) { linkedDescription(description, VideoColors.Link) }
                    androidx.compose.foundation.text.ClickableText(
                        text = linked,
                        style = androidx.compose.ui.text.TextStyle(color = VideoColors.Text, fontSize = 13.sp, lineHeight = 19.sp),
                        onClick = { offset -> linked.timeAt(offset)?.let(onSeek) },
                    )
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
                ActionPill(label = if (w.saved) s.saved else s.save, icon = QMusicIcons.PlaylistAdd, active = w.saved) {
                    if (!controller.openSaveSheet(w.video.id)) onSignIn()
                }
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
            item(key = "comment-input") { CommentInput(w, s, signedIn, onSignIn) { controller.postComment(it) } }
            if (w.comments.isEmpty() && w.commentsLoading) item(key = "comments-loading") { Loading() }
            if (w.comments.isEmpty() && w.commentsLoaded && !w.commentsLoading) {
                item(key = "comments-none") { Text(s.noComments, color = VideoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }
            items(w.comments, key = { "c" + it.id }) { c -> CommentRow(c, w, controller, s, small = false) }
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
        } else if (showRelated) {
            relatedItems(controller, w, s)
        }
    }
}

/** What comes next down the side of a wide screen. */
@Composable
private fun RelatedColumn(controller: VideosController, w: WatchState, s: VideoStrings, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, w.relatedContinuation) {
        if (w.relatedContinuation == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }.collect { near -> if (near) controller.loadMoreRelated() }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) { relatedItems(controller, w, s) }
}

/** The rest of the list the video was opened from, then what YouTube suggests next. */
private fun LazyListScope.relatedItems(controller: VideosController, w: WatchState, s: VideoStrings) {
    if (w.queue.isNotEmpty()) {
        item(key = "queue-title") {
            Text(
                "${s.queueTitle} · ${w.queueIndex + 1}/${w.queue.size}",
                color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        items(w.queue.size - (w.queueIndex + 1).coerceAtMost(w.queue.size), key = { "q" + w.queue[w.queueIndex + 1 + it].id }) { i ->
            val index = w.queueIndex + 1 + i
            VideoRowCard(w.queue[index], s, onClick = { controller.openFrom(w.queue, index) })
        }
    }
    item(key = "related-title") {
        Text(s.related, color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
    }
    if (w.details == null && !w.detailsError) item(key = "related-loading") { Loading() }
    items(w.related.size, key = { i -> (w.related[i].ids().firstOrNull() ?: "r") + "#" + i }) { i ->
        when (val e = w.related[i]) {
            is VideoEntry.Video -> VideoRowCard(e.item, s, onClick = { controller.open(e.item) }, onMore = { controller.openMenu(e.item) })
            is VideoEntry.Shorts -> ShortsRow(e.items, s) { items, index -> controller.openShorts(items, index) }
            is VideoEntry.Channel -> ChannelResultRow(e.item) { controller.push(VideoRoute.Channel(e.item.id, e.item.name)) }
            is VideoEntry.Playlist -> PlaylistResultRow(e.item, s) { controller.push(VideoRoute.Playlist(e.item.id, e.item.title)) }
        }
    }
}

/** The box that takes a comment: it asks for a sign-in when there is no account. */
@Composable
private fun CommentInput(w: WatchState, s: VideoStrings, signedIn: Boolean, onSignIn: () -> Unit, onPost: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(VideoColors.Chip).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).then(if (signedIn) Modifier else Modifier.clickable(onClick = onSignIn)),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(s.writeComment, color = VideoColors.TextSecondary, fontSize = 14.sp)
            if (signedIn) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = VideoColors.Text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(VideoColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (signedIn && text.isNotBlank()) {
            Text(
                if (w.posting) "…" else s.post,
                color = VideoColors.Link, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !w.posting) { if (onPost(text)) text = "" }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** One comment, with its replies opened under it when they were asked for. */
@Composable
private fun CommentRow(c: VideoComment, w: WatchState, controller: VideosController, s: VideoStrings, small: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (small) 56.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.avatar, if (small) 24.dp else 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(listOfNotNull(c.author, c.published).joinToString(" · "), color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.text, color = VideoColors.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                c.likes?.let { Text("♥ $it", color = VideoColors.TextSecondary, fontSize = 12.sp) }
                val opened = w.replies.containsKey(c.id)
                if (c.replies != null && c.repliesToken != null) {
                    Text(
                        text = when {
                            c.id in w.repliesLoading -> "…"
                            opened -> s.hideReplies
                            else -> "↳ " + s.repliesLabel(c.replies!!)
                        },
                        color = VideoColors.Link, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { if (opened) controller.hideReplies(c.id) else controller.loadReplies(c) },
                    )
                }
            }
        }
    }
    w.replies[c.id]?.forEach { reply -> CommentRow(reply, w, controller, s, small = true) }
    if (w.replies.containsKey(c.id) && w.repliesNext[c.id] != null) {
        Text(
            if (c.id in w.repliesLoading) "…" else s.loadMore,
            color = VideoColors.Link, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 8.dp).clickable { controller.loadReplies(c) },
        )
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
