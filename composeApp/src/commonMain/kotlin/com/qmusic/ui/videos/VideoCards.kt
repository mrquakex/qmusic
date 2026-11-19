package com.qmusic.ui.videos

import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.qmusic.ui.theme.QMusicIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.video.VideoChannel
import com.qmusic.engine.video.VideoChip
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoPlaylist
import kotlinx.coroutines.flow.distinctUntilChanged

/** The Videos section looks like YouTube, not like the music app: its own blacks, greys and red. */
object VideoColors {
    // Light like YouTube's light theme, dark like its dark one.
    val Background: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFFFFFFF) else Color(0xFF0F0F0F)
    val Surface: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFF2F2F2) else Color(0xFF212121)
    val Chip: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFF2F2F2) else Color(0xFF272727)
    val ChipSelected: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFF0F0F0F) else Color(0xFFF1F1F1)
    val OnChipSelected: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFFFFFFF) else Color(0xFF0F0F0F)
    val Red = Color(0xFFFF0000)
    val Text: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFF0F0F0F) else Color(0xFFF1F1F1)
    val TextSecondary: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFF606060) else Color(0xFFAAAAAA)
    val Divider: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFE5E5E5) else Color(0xFF303030)
    val Link = Color(0xFF3EA6FF)

    /** Qeyf's own warm colours (the opening's glow): the seek bar runs from one to the other. */
    val QeyfOrange = Color(0xFFFF8A3D)
    val QeyfPink = Color(0xFFFF3D7F)

    /** The parts SponsorBlock skips, marked on the seek bar. */
    val Sponsor = Color(0xFFFFD54F)
}

@Composable
fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(VideoColors.Surface)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun VideoThumbnail(item: VideoItem, s: VideoStrings, modifier: Modifier = Modifier, preview: Boolean = false) {
    Box(modifier = modifier.aspectRatio(16f / 9f).background(VideoColors.Surface)) {
        // Playing silently in the list: the player's picture under the thumbnail, which steps aside once the first
        // frame is there (the picture needs its surface to be drawn at all).
        val showing by FeedPreview.showing.collectAsState()
        if (preview) FeedPreviewSurface(Modifier.fillMaxSize())
        if (item.thumbnail != null && !(preview && showing)) {
            AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // How far it was watched, as on YouTube: a red line along the foot of the picture. YouTube's own figure (from
        // the account, wherever it was watched), else where it was left in this app.
        val local by VideoResume.positions.collectAsState()
        val watched = item.watchedPercent?.let { it / 100f }
            ?: local[item.id]?.let { at -> durationMs(item.duration)?.let { len -> at.toFloat() / len } }
        if (watched != null && watched > 0f && !item.isLive) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(watched.coerceIn(0.02f, 1f)).background(VideoColors.Red))
            }
        }
        val badge = if (item.isLive) s.live else item.duration
        if (badge != null) {
            Text(
                text = badge,
                // White on dark in both themes: it sits on a picture.
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (item.isLive) VideoColors.Red else Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/** Where videos were left in this app (id to milliseconds), for the red line on their pictures. */
object VideoResume {
    val positions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
}

/** "3:54" or "1:02:03" in milliseconds; null when it is not a length. */
internal fun durationMs(text: String?): Long? {
    val parts = text?.split(':')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
}

/** "Channel · 86 M views · 1 year ago", leaving out what a card does not have. */
internal fun VideoItem.subtitle(): String =
    listOfNotNull(channelName, views, published).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * Videos made ready ahead: a card touched (before the tap is over), or the first ones of a list as it appears, get
 * their streams looked up and their first seconds fetched. The controller and the phone's player fill the hooks.
 */
object VideoWarm {
    /** A video about to be opened (a finger on its card). */
    var touch: (VideoItem) -> Unit = {}
    /** A list just shown: its first videos. */
    var list: (List<VideoEntry>) -> Unit = {}
    /** The first bytes of a chosen stream (picture and sound) into the player's cache. */
    var bytes: (com.qmusic.engine.video.VideoQuality) -> Unit = {}
    /** Nothing open: the player itself loads the touched video, silent and still, so the tap only starts it. */
    var prepare: (com.qmusic.engine.video.VideoQuality) -> Unit = {}
}

/** Gets [item] ready as soon as a finger lands on it; the touch goes on to the click as before. */
fun Modifier.warmVideoOnPress(item: VideoItem): Modifier = pointerInput(item.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        VideoWarm.touch(item)
    }
}

/** The big card of a feed: picture across the width, then avatar, title and who/how many/when. */
@Composable
fun VideoCard(item: VideoItem, s: VideoStrings, onClick: () -> Unit, onChannel: (() -> Unit)?, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null, previewing: Boolean = false) {
    Column(modifier = modifier.fillMaxWidth().warmVideoOnPress(item).clickable(onClick = onClick).padding(bottom = 16.dp)) {
        VideoThumbnail(item, s, Modifier.fillMaxWidth(), preview = previewing)
        Row(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (item.channelAvatar != null || item.channelId != null) {
                Avatar(
                    item.channelAvatar,
                    36.dp,
                    Modifier.clickable(enabled = onChannel != null) { onChannel?.invoke() },
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, color = VideoColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                val sub = item.subtitle()
                if (sub.isNotEmpty()) {
                    Text(sub, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (onMore != null) MoreButton(onMore)
        }
    }
}

/** The ⋮ at the end of a card. */
@Composable
fun MoreButton(onClick: () -> Unit) {
    Icon(
        QMusicIcons.More, null, tint = VideoColors.TextSecondary,
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick).padding(4.dp),
    )
}

/** The small card of "up next" and channel lists: picture on the left, text on the right. */
@Composable
fun VideoRowCard(item: VideoItem, s: VideoStrings, onClick: () -> Unit, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth().pressScale(0.98f).warmVideoOnPress(item).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        VideoThumbnail(item, s, Modifier.width(168.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            val sub = item.subtitle()
            if (sub.isNotEmpty()) {
                Text(sub, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (onMore != null) MoreButton(onMore)
    }
}

@Composable
fun ShortsRow(items: List<VideoItem>, s: VideoStrings, onClick: (items: List<VideoItem>, index: Int) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(s.shorts, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.size, key = { items[it].id }) { index ->
                val item = items[index]
                Column(modifier = Modifier.width(150.dp).clickable { onClick(items, index) }) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(VideoColors.Surface)) {
                        if (item.thumbnail != null) AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(item.title, color = VideoColors.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    item.views?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun ChannelResultRow(channel: VideoChannel, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatar, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.name, color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(channel.handle, channel.subscribers).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            channel.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PlaylistResultRow(playlist: VideoPlaylist, s: VideoStrings, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(modifier = Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(VideoColors.Surface)) {
            if (playlist.thumbnail != null) AsyncImage(model = playlist.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            playlist.videoCount?.let {
                Text(
                    s.videosCount(it),
                    color = Ink,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(InkInverse.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.title, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            playlist.channelName?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}

@Composable
fun ChipRow(chips: List<VideoChip>, onClick: (VideoChip) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.text + "|" + it.token }) { chip ->
            LabelChip(chip.text, chip.selected) { onClick(chip) }
        }
    }
}

@Composable
fun LabelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) InkInverse else VideoColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .pressScale(0.94f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) VideoColors.ChipSelected else VideoColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun MessageBlock(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (body != null) Text(body, color = VideoColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Text(
                action,
                color = InkInverse,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(VideoColors.ChipSelected).clickable(onClick = onAction).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun SignInBanner(s: VideoStrings, onSignIn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(VideoColors.Surface).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.signInBar, color = VideoColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(s.signIn, color = VideoColors.Red, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSignIn).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** A grey stand-in for a card while a list loads: the page keeps its shape instead of jumping when the cards arrive. */
@Composable
fun SkeletonCard() {
    val pulse by rememberInfiniteTransition().animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).graphicsLayer { alpha = pulse }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(VideoColors.Surface))
        Row(modifier = Modifier.padding(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(VideoColors.Surface))
            Spacer(Modifier.width(12.dp))
            Column {
                Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(VideoColors.Surface))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(VideoColors.Surface))
            }
        }
    }
}

/** Waiting over a picture: a white ring on a soft dark disc, seen over anything (as YouTube's). */
@Composable
fun PlayerSpinner() {
    Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), Alignment.Center) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
    }
}

/** How long a list stays still before its video starts previewing. */
private const val PREVIEW_SETTLE_MS = 800L

/** A row of a list (picture on the left, lines on the right) not loaded yet. */
@Composable
fun SkeletonRow() {
    val pulse by rememberInfiniteTransition().animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).graphicsLayer { alpha = pulse }) {
        Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(VideoColors.Surface))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Box(Modifier.fillMaxWidth(0.95f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(VideoColors.Surface))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(VideoColors.Surface))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(VideoColors.Surface))
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VideoColors.Red, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/**
 * Any list of the section, and pull down at the top to load it afresh when [onRefresh] is given.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedColumn(
    state: FeedState,
    s: VideoStrings,
    onVideo: (VideoItem) -> Unit,
    onChannel: (id: String, name: String?) -> Unit,
    onPlaylist: (VideoPlaylist) -> Unit,
    onShort: (items: List<VideoItem>, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    compact: Boolean = false,
    signInMessage: Pair<String, String>? = null,
    onSignIn: (() -> Unit)? = null,
    resetKey: Int? = null,
    onRefresh: (() -> Unit)? = null,
    onMore: ((VideoItem) -> Unit)? = null,
    header: (LazyListScope.() -> Unit)? = null,
) {
    if (onRefresh == null) {
        FeedList(state, s, onVideo, onChannel, onPlaylist, onShort, onLoadMore, onRetry, modifier, listState, compact, signInMessage, onSignIn, resetKey, onMore, header)
    } else {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.loading && state.entries.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            FeedList(state, s, onVideo, onChannel, onPlaylist, onShort, onLoadMore, onRetry, Modifier, listState, compact, signInMessage, onSignIn, resetKey, onMore, header)
        }
    }
}

/**
 * Any list of the section: cards in the order YouTube gave them, more as the end nears, and the states a list has
 * (loading, failed, empty, needs an account).
 */
@Composable
private fun FeedList(
    state: FeedState,
    s: VideoStrings,
    onVideo: (VideoItem) -> Unit,
    onChannel: (id: String, name: String?) -> Unit,
    onPlaylist: (VideoPlaylist) -> Unit,
    onShort: (items: List<VideoItem>, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    compact: Boolean = false,
    signInMessage: Pair<String, String>? = null,
    onSignIn: (() -> Unit)? = null,
    resetKey: Int? = null,
    onMore: ((VideoItem) -> Unit)? = null,
    header: (LazyListScope.() -> Unit)? = null,
) {
    LaunchedEffect(resetKey) { if (resetKey != null && resetKey > 0) listState.scrollToItem(0) }
    LaunchedEffect(listState, state.continuation) {
        if (state.continuation == null) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }.distinctUntilChanged().collect { near -> if (near) onLoadMore() }
    }
    val previewId by FeedPreview.playing.collectAsState()
    if (!compact) {
        LaunchedEffect(listState, state.entries) {
            val videoByKey = state.entries.mapIndexedNotNull { i, e -> (e as? VideoEntry.Video)?.let { ((e.ids().firstOrNull() ?: "e") + "#" + i) to it.item } }.toMap()
            snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
                if (scrolling) {
                    FeedPreview.stopAll()
                    return@collectLatest
                }
                kotlinx.coroutines.delay(PREVIEW_SETTLE_MS)
                if (!FeedPreview.allowed()) {
                    FeedPreview.stopAll()
                    return@collectLatest
                }
                val info = listState.layoutInfo
                val pictureHeight = info.viewportSize.width * 9 / 16
                val item = info.visibleItemsInfo.firstNotNullOfOrNull { visible ->
                    val video = videoByKey[visible.key as? String] ?: return@firstNotNullOfOrNull null
                    video.takeIf { visible.offset >= info.viewportStartOffset && visible.offset + pictureHeight <= info.viewportEndOffset && !it.isLive }
                } ?: return@collectLatest
                if (FeedPreview.playing.value == item.id) return@collectLatest
                FeedPreview.stopAll()
                val quality = FeedPreview.resolve(item) ?: return@collectLatest
                if (listState.isScrollInProgress || !FeedPreview.allowed()) return@collectLatest
                FeedPreview.playing.value = item.id
                FeedPreview.play(quality)
            }
        }
        DisposableEffect(Unit) { onDispose { FeedPreview.stopAll() } }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        header?.invoke(this)
        when {
            state.signInNeeded && state.entries.isEmpty() && signInMessage != null ->
                item(key = "signin") { MessageBlock(signInMessage.first, signInMessage.second, s.signIn, onSignIn) }
            state.loading && state.entries.isEmpty() -> items(3, key = { "skeleton$it" }) { SkeletonCard() }
            state.error && state.entries.isEmpty() -> item(key = "error") { MessageBlock(s.loadError, action = s.retry, onAction = onRetry) }
            state.loaded && state.entries.isEmpty() -> item(key = "empty") { MessageBlock(s.nothingHere) }
        }
        items(state.entries.size, key = { i -> state.entries[i].let { e -> (e.ids().firstOrNull() ?: "e") + "#" + i } }) { index ->
            when (val entry = state.entries[index]) {
                is VideoEntry.Video -> if (compact) {
                    VideoRowCard(entry.item, s, onClick = { onVideo(entry.item) }, onMore = onMore?.let { m -> { m(entry.item) } })
                } else {
                    VideoCard(
                        entry.item, s,
                        onClick = { onVideo(entry.item) },
                        onChannel = entry.item.channelId?.let { id -> { onChannel(id, entry.item.channelName) } },
                        onMore = onMore?.let { m -> { m(entry.item) } },
                        previewing = previewId == entry.item.id,
                    )
                }
                is VideoEntry.Channel -> ChannelResultRow(entry.item) { onChannel(entry.item.id, entry.item.name) }
                is VideoEntry.Playlist -> PlaylistResultRow(entry.item, s) { onPlaylist(entry.item) }
                is VideoEntry.Shorts -> ShortsRow(entry.items, s, onClick = onShort)
            }
        }
        if (state.loadingMore) item(key = "more") { Loading() }
    }
}
