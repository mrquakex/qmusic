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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.QMusicIcons
import androidx.compose.foundation.layout.BoxWithConstraints
import com.qmusic.engine.video.VideoEntry
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

/**
 * The Videos section: YouTube itself, entered from the navigation bar and shaped like its own app (own top bar, own
 * bottom bar, own colors), with the account's real home feed, subscriptions and library.
 */
@Composable
fun VideosApp(
    controller: VideosController,
    s: VideoStrings,
    onExit: () -> Unit,
    onSignIn: () -> Unit,
    onPauseMusic: () -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab by controller.tab.collectAsState()
    val routes by controller.routes.collectAsState()
    val signedIn by controller.signedIn.collectAsState()
    val searching by controller.searching.collectAsState()
    var accountDialog by remember { mutableStateOf(false) }
    val top = routes.lastOrNull()
    val watch by controller.watch.collectAsState()
    val fullscreen by controller.fullscreen.collectAsState()
    val pip = rememberInPictureInPicture()

    // The player belongs to the section, not to the page of a video: leaving the page leaves the video playing small.
    val playback = rememberVideoPlayback()
    var loadedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(watch?.video?.id, watch?.quality, watch?.caption, playback) {
        val w = watch
        val p = playback
        if (w == null) {
            p?.pause()
            loadedId = null
            return@LaunchedEffect
        }
        val q = w.quality ?: return@LaunchedEffect
        if (p == null) return@LaunchedEffect
        // Another quality of the same video carries on from where it was.
        // A video that was left half way starts there again.
        val start = if (loadedId == w.video.id) p.positionMs.value else controller.resumeFor(w.video.id)
        loadedId = w.video.id
        onPauseMusic()
        p.load(q, start, caption = w.caption)
    }
    // Where the video is, kept every few seconds so it can be picked up again.
    LaunchedEffect(watch?.video?.id, playback) {
        val id = watch?.video?.id ?: return@LaunchedEffect
        val p = playback ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            controller.savePosition(id, p.positionMs.value, p.durationMs.value)
        }
    }
    // A few seconds of playing count as watched: YouTube puts it in the account's history.
    LaunchedEffect(watch?.video?.id, playback) {
        val id = watch?.video?.id ?: return@LaunchedEffect
        val p = playback ?: return@LaunchedEffect
        p.isPlaying.first { it }
        delay(3_000)
        controller.reportWatch(id)
    }
    // Playback that keeps stalling steps down a quality by itself.
    val buffering by (playback?.buffering ?: NotPlaying).collectAsState()
    var stalls by remember { mutableStateOf(emptyList<kotlin.time.TimeMark>()) }
    LaunchedEffect(buffering) {
        if (buffering && (playback?.positionMs?.value ?: 0L) > 3_000L) {
            val recent = stalls.filter { it.elapsedNow() < 45.seconds } + kotlin.time.TimeSource.Monotonic.markNow()
            stalls = recent
            if (recent.size >= 3) {
                controller.stepDownQuality()
                stalls = emptyList()
            }
        }
    }
    VideoBackgroundEffect(
        active = watch != null,
        title = watch?.details?.title ?: watch?.video?.title,
        onToggle = { playback?.togglePlay() },
    )
    val playing by (playback?.isPlaying ?: NotPlaying).collectAsState()
    val ended by (playback?.ended ?: NotPlaying).collectAsState()
    LaunchedEffect(ended) { if (ended) controller.playNext() }
    FullscreenEffect(fullscreen && top is VideoRoute.Watch)
    PictureInPictureEffect(watch != null && top is VideoRoute.Watch && playing)

    val exit: () -> Unit = {
        controller.closeWatch()
        onExit()
    }

    // Back closes, in order: full screen, the search box, the topmost page, a tab that is not home, and then the section.
    PlatformBackHandler(enabled = true) {
        when {
            fullscreen -> controller.setFullscreen(false)
            searching -> controller.closeSearchBox()
            controller.pop() -> Unit
            tab != VideoTab.HOME -> controller.selectTab(VideoTab.HOME)
            else -> exit()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(VideoColors.Background)) {
        // Turning the phone sideways while a video is open makes it full screen, and turning it back leaves it.
        val landscape = maxWidth > maxHeight && minOf(maxWidth, maxHeight) < 600.dp
        LaunchedEffect(landscape, top is VideoRoute.Watch) {
            if (top is VideoRoute.Watch) controller.setFullscreen(landscape)
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (top) {
                    null -> TabContent(controller, tab, s, signedIn, onSignIn, playback, onPauseMusic, onShare, onAccount = { accountDialog = true })
                    is VideoRoute.Search -> SearchResultsPage(controller, s, onSignIn)
                    is VideoRoute.Watch -> WatchPage(controller, playback, s, onShare, onSignIn)
                    is VideoRoute.Channel -> ChannelPage(controller, s, onSignIn)
                    is VideoRoute.Playlist -> PlaylistPage(controller, top.title, s, onSignIn)
                    is VideoRoute.Shorts -> ShortsPage(controller, playback, s, true, onPauseMusic, onShare, onSignIn)
                }
            }
            val w = watch
            if (w != null && top !is VideoRoute.Watch && !pip) {
                MiniWatchBar(w, playback, onOpen = { controller.push(VideoRoute.Watch(w.video)) }, onClose = { controller.closeWatch() })
            }
            if (top !is VideoRoute.Watch && !pip) {
                VideoBottomBar(tab = tab.takeIf { routes.isEmpty() }, s = s, onTab = { t -> if (t == tab && routes.isEmpty()) controller.refreshCurrent() else controller.selectTab(t) }, onMusic = exit)
            }
        }
        if (searching) SearchBox(controller, s)
        VideoSheets(controller, s, onShare, onSignIn)
        if (accountDialog) {
            AlertDialog(
                onDismissRequest = { accountDialog = false },
                containerColor = VideoColors.Surface,
                title = { Text(s.settingsTitle, color = VideoColors.Text) },
                text = { SettingsBody(controller, s, signedIn) },
                confirmButton = {
                    TextButton(onClick = {
                        accountDialog = false
                        if (signedIn) controller.signOut() else onSignIn()
                    }) { Text(if (signedIn) s.signOut else s.signIn, color = VideoColors.Red) }
                },
                dismissButton = { TextButton(onClick = { accountDialog = false }) { Text(s.close, color = VideoColors.Text) } },
            )
        }
    }
}

// ---------------------------------------------------------------------- bars

private val NotPlaying: StateFlow<Boolean> = MutableStateFlow(false)
private val NoPosition: StateFlow<Long> = MutableStateFlow(0L)

/** The video that is playing small, above the bottom bar: tap to open it again, play/pause, close. */
@Composable
private fun MiniWatchBar(w: WatchState, playback: VideoPlayback?, onOpen: () -> Unit, onClose: () -> Unit) {
    val playing by (playback?.isPlaying ?: NotPlaying).collectAsState()
    val position by (playback?.positionMs ?: NoPosition).collectAsState()
    val duration by (playback?.durationMs ?: NoPosition).collectAsState()
    Column(modifier = Modifier.fillMaxWidth().background(VideoColors.Surface).clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(104.dp).aspectRatio(16f / 9f).background(Color.Black)) {
                if (w.video.thumbnail != null) AsyncImage(model = w.video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (playback != null && w.quality != null) VideoPlaybackSurface(playback, Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(w.details?.title ?: w.video.title, color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (w.details?.channelName ?: w.video.channelName)?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { playback?.togglePlay() }) {
                Icon(if (playing) QMusicIcons.Pause else QMusicIcons.Play, null, tint = VideoColors.Text, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onClose) { Icon(QMusicIcons.Close, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp)) }
        }
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(VideoColors.Chip)) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(2.dp).background(VideoColors.Red))
        }
    }
}

@Composable
private fun TopBar(s: VideoStrings, onSearch: () -> Unit, onAccount: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(VideoColors.Background).statusBarsPadding().padding(start = 12.dp, end = 4.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.qmusic.ui.theme.QMusicLogo(size = 30.dp, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(s.section, color = VideoColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(QMusicIcons.Search, s.searchHint, tint = VideoColors.Text) }
        IconButton(onClick = onAccount) { Icon(QMusicIcons.Account, s.account, tint = VideoColors.Text) }
    }
}

@Composable
private fun VideoBottomBar(tab: VideoTab?, s: VideoStrings, onTab: (VideoTab) -> Unit, onMusic: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(VideoColors.Background).navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp),
    ) {
        BarItem(QMusicIcons.Home, s.tabHome, tab == VideoTab.HOME, Modifier.weight(1f)) { onTab(VideoTab.HOME) }
        BarItem(VideoIcons.Shorts, s.shorts, tab == VideoTab.SHORTS, Modifier.weight(1f)) { onTab(VideoTab.SHORTS) }
        BarItem(QMusicIcons.VideoPlay, s.tabSubscriptions, tab == VideoTab.SUBSCRIPTIONS, Modifier.weight(1f)) { onTab(VideoTab.SUBSCRIPTIONS) }
        BarItem(QMusicIcons.Library, s.tabLibrary, tab == VideoTab.LIBRARY, Modifier.weight(1f)) { onTab(VideoTab.LIBRARY) }
        BarItem(QMusicIcons.Headphones, s.backToMusic, false, Modifier.weight(1f), onMusic)
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) VideoColors.Text else VideoColors.TextSecondary, modifier = Modifier.size(26.dp))
        Text(label, color = if (selected) VideoColors.Text else VideoColors.TextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun PageBar(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(VideoColors.Background).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(QMusicIcons.Back, null, tint = VideoColors.Text) }
        Text(title, color = VideoColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------- tabs

@Composable
private fun TabContent(
    controller: VideosController,
    tab: VideoTab,
    s: VideoStrings,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    playback: VideoPlayback?,
    onPauseMusic: () -> Unit,
    onShare: (String) -> Unit,
    onAccount: () -> Unit,
) {
    if (tab == VideoTab.SHORTS) {
        ShortsPage(controller, playback, s, false, onPauseMusic, onShare, onSignIn)
        return
    }
    val refresh by controller.refresh.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(s, onSearch = { controller.openSearchBox() }, onAccount = onAccount)
        when (tab) {
            VideoTab.HOME -> {
                val state by controller.home.state.collectAsState()
                LaunchedEffect(signedIn) { controller.home.load() }
                FeedColumn(
                    state = state, s = s,
                    onVideo = { controller.open(it) },
                    onShort = { items, i -> controller.openShorts(items, i) },
                    onMore = { controller.openMenu(it) },
                    onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
                    onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
                    onLoadMore = { controller.home.loadMore() },
                    onRetry = { controller.home.load(force = true) },
                    onRefresh = { controller.home.load(force = true) },
                    signInMessage = s.signInTitle to s.signInBody,
                    onSignIn = onSignIn,
                    resetKey = refresh,
                    header = if (state.chips.isNotEmpty() || (!signedIn && state.entries.isNotEmpty())) {
                        {
                            if (!signedIn && state.entries.isNotEmpty()) item(key = "banner") { SignInBanner(s, onSignIn) }
                            if (state.chips.isNotEmpty()) item(key = "chips") { ChipRow(state.chips) { controller.home.selectChip(it) } }
                        }
                    } else null,
                )
            }
            VideoTab.SHORTS -> Unit
            VideoTab.SUBSCRIPTIONS -> SubscriptionsTab(controller, s, signedIn, onSignIn)
            VideoTab.LIBRARY -> LibraryTab(controller, s, signedIn, onSignIn)
        }
    }
}

@Composable
private fun SubscriptionsTab(controller: VideosController, s: VideoStrings, signedIn: Boolean, onSignIn: () -> Unit) {
    var channels by remember { mutableStateOf(false) }
    val loader = if (channels) controller.subscribedChannels else controller.subscriptions
    val state by loader.state.collectAsState()
    LaunchedEffect(signedIn, channels) { if (signedIn) loader.load() }
    FeedColumn(
        state = if (signedIn) state else state.copy(signInNeeded = true, entries = emptyList()),
        s = s,
        onVideo = { controller.open(it) },
        onShort = { items, i -> controller.openShorts(items, i) },
        onMore = { controller.openMenu(it) },
        onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
        onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
        onLoadMore = { loader.loadMore() },
        onRetry = { loader.load(force = true) },
        onRefresh = { loader.load(force = true) },
        signInMessage = s.tabSubscriptions to s.signInSubscriptions,
        onSignIn = onSignIn,
        header = {
            item(key = "sections") {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { LabelChip(s.videosTab, !channels) { channels = false } }
                    item { LabelChip(s.channelsTab, channels) { channels = true } }
                }
            }
        },
    )
}

@Composable
private fun LibraryTab(controller: VideosController, s: VideoStrings, signedIn: Boolean, onSignIn: () -> Unit) {
    var section by remember { mutableStateOf(LibrarySection.HISTORY) }
    val loader = controller.library(section)
    val state by loader.state.collectAsState()
    LaunchedEffect(signedIn, section) { if (signedIn) loader.load() }
    FeedColumn(
        state = if (signedIn) state else state.copy(signInNeeded = true, entries = emptyList()),
        s = s,
        onVideo = { item -> val items = state.entries.filterIsInstance<VideoEntry.Video>().map { it.item }; controller.openFrom(items, items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)) },
        onShort = { items, i -> controller.openShorts(items, i) },
        onMore = { controller.openMenu(it) },
        onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
        onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
        onLoadMore = { loader.loadMore() },
        onRetry = { loader.load(force = true) },
        onRefresh = { loader.load(force = true) },
        signInMessage = s.tabLibrary to s.signInLibrary,
        onSignIn = onSignIn,
        compact = section != LibrarySection.PLAYLISTS,
        header = {
            item(key = "sections") {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibrarySection.entries) { entry ->
                        val label = when (entry) {
                            LibrarySection.HISTORY -> s.history
                            LibrarySection.LIKED -> s.liked
                            LibrarySection.WATCH_LATER -> s.watchLater
                            LibrarySection.PLAYLISTS -> s.playlists
                        }
                        LabelChip(label, entry == section) { section = entry }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------- search

@Composable
private fun SearchBox(controller: VideosController, s: VideoStrings) {
    val query by controller.query.collectAsState()
    val suggestions by controller.suggestions.collectAsState()
    val history by controller.searchHistory.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize().background(VideoColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.closeSearchBox() }) { Icon(QMusicIcons.Back, null, tint = VideoColors.Text) }
            Box(
                modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(VideoColors.Chip).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(s.searchHint, color = VideoColors.TextSecondary, fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { controller.typeQuery(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = VideoColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(VideoColors.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.submitSearch(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { controller.typeQuery("") }) { Icon(QMusicIcons.Close, null, tint = VideoColors.Text) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (query.isBlank() && history.isNotEmpty()) {
                item(key = "history-header") {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.recentSearches, color = VideoColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            s.clearSearches, color = VideoColors.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { controller.clearSearches() }.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                items(history, key = { "h$it" }) { text ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { controller.submitSearch(text) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QMusicIcons.History, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(text, color = VideoColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { controller.typeQuery(text) }) { Icon(QMusicIcons.FillQuery, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { controller.forgetSearch(text) }) { Icon(QMusicIcons.Close, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            items(if (query.isBlank()) emptyList() else suggestions) { text ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { controller.submitSearch(text) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.Search, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(text, color = VideoColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(
                        QMusicIcons.FillQuery, null, tint = VideoColors.TextSecondary,
                        modifier = Modifier.size(20.dp).clickable { controller.typeQuery(text) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsPage(controller: VideosController, s: VideoStrings, onSignIn: () -> Unit) {
    val routes by controller.routes.collectAsState()
    val query = (routes.lastOrNull() as? VideoRoute.Search)?.query.orEmpty()
    val filter by controller.filter.collectAsState()
    val loader by controller.searchResults.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = query, onBack = { controller.pop() }, trailing = {
            IconButton(onClick = { controller.openSearchBox() }) { Icon(QMusicIcons.Search, s.searchHint, tint = VideoColors.Text) }
        })
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VideosController.FILTERS) { (params, key) ->
                LabelChip(s.filter(key), filter == params) { controller.setFilter(params) }
            }
        }
        val current = loader
        if (current != null) {
            val state by current.state.collectAsState()
            FeedColumn(
                state = state, s = s,
                onVideo = { controller.open(it) },
                onShort = { items, i -> controller.openShorts(items, i) },
                onMore = { controller.openMenu(it) },
                onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
                onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
                onLoadMore = { current.loadMore() },
                onRetry = { current.load(force = true) },
                onRefresh = { current.load(force = true) },
                onSignIn = onSignIn,
            )
        }
    }
}

// ---------------------------------------------------------------------- channels and playlists

@Composable
private fun ChannelPage(controller: VideosController, s: VideoStrings, onSignIn: () -> Unit) {
    val channel by controller.channel.collectAsState()
    val c = channel ?: return
    val page = c.page
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = page?.name ?: "", onBack = { controller.pop() })
        FeedColumn(
            state = c.feed, s = s,
            onVideo = { controller.open(it) },
            onShort = { items, i -> controller.openShorts(items, i) },
            onMore = { controller.openMenu(it) },
            onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
            onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
            onLoadMore = { controller.loadMoreChannel() },
            onRetry = { controller.push(VideoRoute.Channel(c.id, page?.name)) },
            onSignIn = onSignIn,
            header = if (page != null) {
                {
                    item(key = "channel-header") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (page.banner != null) {
                                AsyncImage(model = page.banner, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(90.dp))
                            }
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(page.avatar, 72.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(page.name, color = VideoColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                    val line = listOfNotNull(page.handle, page.subscribers, page.videoCount).joinToString(" · ")
                                    if (line.isNotEmpty()) Text(line, color = VideoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Text(
                                if (c.subscribed) s.subscribed else s.subscribe,
                                color = if (c.subscribed) VideoColors.Text else Color.Black,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (c.subscribed) VideoColors.Chip else VideoColors.ChipSelected)
                                    .clickable { if (controller.signedIn.value) controller.toggleChannelSubscribe() else onSignIn() }
                                    .padding(horizontal = 20.dp, vertical = 9.dp),
                            )
                            if (page.tabs.isNotEmpty()) {
                                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(page.tabs, key = { it.params }) { tab ->
                                        LabelChip(tab.title, tab.params == c.tabParams || (c.tabParams == null && tab.title == s.videosTab)) { controller.openChannelTab(tab.params) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else null,
        )
    }
}

@Composable
private fun PlaylistPage(controller: VideosController, title: String, s: VideoStrings, onSignIn: () -> Unit) {
    val loader by controller.openedPlaylist.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = title, onBack = { controller.pop() })
        val current = loader
        if (current != null) {
            val state by current.state.collectAsState()
            FeedColumn(
                state = state, s = s,
                compact = true,
                onVideo = { item -> val items = state.entries.filterIsInstance<VideoEntry.Video>().map { it.item }; controller.openFrom(items, items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)) },
                onShort = { items, i -> controller.openShorts(items, i) },
                onMore = { controller.openMenu(it) },
                onChannel = { id, name -> controller.push(VideoRoute.Channel(id, name)) },
                onPlaylist = { controller.push(VideoRoute.Playlist(it.id, it.title)) },
                onLoadMore = { current.loadMore() },
                onRetry = { current.load(force = true) },
                onSignIn = onSignIn,
            )
        }
    }
}

// ---------------------------------------------------------------------- settings and sheets

private val QUALITY_CHOICES = listOf("360", "480", "720", "1080")

@Composable
private fun SettingsBody(controller: VideosController, s: VideoStrings, signedIn: Boolean) {
    val wifi by controller.wifiQuality.collectAsState()
    val mobile by controller.mobileQuality.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!signedIn) Text(s.signInBody, color = VideoColors.TextSecondary, fontSize = 14.sp)
        Text(s.qualityWifi, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUALITY_CHOICES) { h -> LabelChip("${h}p", wifi == h) { controller.setWifiQuality(h) } }
        }
        Text(s.qualityMobile, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUALITY_CHOICES) { h -> LabelChip("${h}p", mobile == h) { controller.setMobileQuality(h) } }
        }
    }
}

/** The bottom sheets: a card's ⋮ menu, "save to a playlist", and the comments of a Short. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VideoSheets(controller: VideosController, s: VideoStrings, onShare: (String) -> Unit, onSignIn: () -> Unit) {
    val menu by controller.menu.collectAsState()
    val save by controller.saveSheet.collectAsState()
    val comments by controller.shortComments.collectAsState()

    menu?.let { item ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { controller.closeMenu() }, containerColor = VideoColors.Surface) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(item.title, color = VideoColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SheetRow(QMusicIcons.History, s.menuWatchLater) {
                    controller.closeMenu()
                    if (!controller.saveForLater(item)) onSignIn()
                }
                SheetRow(QMusicIcons.PlaylistAdd, s.menuSave) {
                    controller.closeMenu()
                    if (!controller.openSaveSheet(item.id)) onSignIn()
                }
                SheetRow(QMusicIcons.Share, s.share) {
                    controller.closeMenu()
                    onShare("https://youtu.be/${item.id}")
                }
                item.channelId?.let { id ->
                    SheetRow(QMusicIcons.Account, s.menuChannel) {
                        controller.closeMenu()
                        controller.push(VideoRoute.Channel(id, item.channelName))
                    }
                }
            }
        }
    }

    save?.let { sheet ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { controller.closeSaveSheet() }, containerColor = VideoColors.Surface) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(s.saveTo, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (sheet.loading) Loading()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sheet.playlists, key = { it.id }) { list ->
                        val saved = list.id in sheet.saved
                        SheetRow(if (saved) QMusicIcons.Check else QMusicIcons.PlaylistAdd, if (list.id == "WL") s.menuWatchLater else list.title, active = saved) {
                            controller.toggleInPlaylist(list.id)
                        }
                    }
                }
            }
        }
    }

    comments?.let { state ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { controller.closeShortComments() }, containerColor = VideoColors.Surface) {
            Column(modifier = Modifier.navigationBarsPadding().fillMaxHeight(0.7f)) {
                Text(s.comments, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (state.loading) Loading()
                if (!state.loading && state.comments.isEmpty()) Text(s.noComments, color = VideoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.comments, key = { it.id }) { c ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Avatar(c.avatar, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(listOfNotNull(c.author, c.published).joinToString(" · "), color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.text, color = VideoColors.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) VideoColors.Red else VideoColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = VideoColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
