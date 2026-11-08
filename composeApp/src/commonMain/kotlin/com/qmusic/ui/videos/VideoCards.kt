package com.qmusic.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF212121)
    val Chip = Color(0xFF272727)
    val ChipSelected = Color(0xFFF1F1F1)
    val Red = Color(0xFFFF0000)
    val Text = Color(0xFFF1F1F1)
    val TextSecondary = Color(0xFFAAAAAA)
    val Divider = Color(0xFF303030)
}

@Composable
fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(VideoColors.Surface)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun VideoThumbnail(item: VideoItem, s: VideoStrings, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(16f / 9f).background(VideoColors.Surface)) {
        if (item.thumbnail != null) {
            AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val badge = if (item.isLive) s.live else item.duration
        if (badge != null) {
            Text(
                text = badge,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (item.isLive) VideoColors.Red else Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/** "Channel · 86 M views · 1 year ago", leaving out what a card does not have. */
internal fun VideoItem.subtitle(): String =
    listOfNotNull(channelName, views, published).filter { it.isNotBlank() }.joinToString(" · ")

/** The big card of a feed: picture across the width, then avatar, title and who/how many/when. */
@Composable
fun VideoCard(item: VideoItem, s: VideoStrings, onClick: () -> Unit, onChannel: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 16.dp)) {
        VideoThumbnail(item, s, Modifier.fillMaxWidth())
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
        }
    }
}

/** The small card of "up next" and channel lists: picture on the left, text on the right. */
@Composable
fun VideoRowCard(item: VideoItem, s: VideoStrings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        VideoThumbnail(item, s, Modifier.width(168.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            val sub = item.subtitle()
            if (sub.isNotEmpty()) {
                Text(sub, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
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
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 2.dp),
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
        items(chips, key = { it.token }) { chip ->
            LabelChip(chip.text, chip.selected) { onClick(chip) }
        }
    }
}

@Composable
fun LabelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color.Black else VideoColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
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
                color = Color.Black,
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
    header: (LazyListScope.() -> Unit)? = null,
) {
    if (onRefresh == null) {
        FeedList(state, s, onVideo, onChannel, onPlaylist, onShort, onLoadMore, onRetry, modifier, listState, compact, signInMessage, onSignIn, resetKey, header)
    } else {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.loading && state.entries.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            FeedList(state, s, onVideo, onChannel, onPlaylist, onShort, onLoadMore, onRetry, Modifier, listState, compact, signInMessage, onSignIn, resetKey, header)
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
                    VideoRowCard(entry.item, s, onClick = { onVideo(entry.item) })
                } else {
                    VideoCard(
                        entry.item, s,
                        onClick = { onVideo(entry.item) },
                        onChannel = entry.item.channelId?.let { id -> { onChannel(id, entry.item.channelName) } },
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
