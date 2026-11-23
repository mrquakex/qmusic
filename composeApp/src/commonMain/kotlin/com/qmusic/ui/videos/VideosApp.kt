package com.qmusic.ui.videos

import com.qmusic.ui.motion.fadeThrough
import com.qmusic.ui.components.QeyfBarItem
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.widthIn

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
    /** Goes to the messages (their bar's button), and how many are unread there. */
    onChat: (() -> Unit)? = null,
    chatUnread: Int = 0,
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
    // Watching together reaches the player through this.
    androidx.compose.runtime.DisposableEffect(playback) {
        com.qmusic.ui.chat.TogetherVideo.playback = playback
        onDispose { if (com.qmusic.ui.chat.TogetherVideo.playback === playback) com.qmusic.ui.chat.TogetherVideo.playback = null }
    }
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
    // A few seconds of playing count as watched (the account's history), then how long it is watched (what the
    // suggestions learn from), as on YouTube.
    ReportWatching(controller, watch?.video?.id, playback)
    // Playback that keeps stalling steps down a quality by itself.
    // Only real stalls count: the wait after opening or seeking is not one.
    val rebuffers by (playback?.rebuffers ?: NoStalls).collectAsState()
    var stalls by remember { mutableStateOf(emptyList<kotlin.time.TimeMark>()) }
    LaunchedEffect(rebuffers) {
        if (rebuffers > 0) {
            val recent = stalls.filter { it.elapsedNow() < 45.seconds } + kotlin.time.TimeSource.Monotonic.markNow()
            stalls = recent
            if (recent.size >= 3) {
                controller.stepDownQuality()
                stalls = emptyList()
            }
        }
    }
    VideoCastEffect(controller, playback)
    VideoBackgroundEffect(
        active = watch != null,
        title = watch?.details?.title ?: watch?.video?.title,
        onToggle = { playback?.togglePlay() },
    )
    val playing by (playback?.isPlaying ?: NotPlaying).collectAsState()
    val ended by (playback?.ended ?: NotPlaying).collectAsState()
    LaunchedEffect(ended) { if (ended) controller.onEnded() }
    // Videos kept on the device.
    val downloadsService = rememberVideoDownloads()
    LaunchedEffect(downloadsService) { controller.attachDownloads(downloadsService) }
    // SponsorBlock: a marked part is jumped over once when the video reaches it.
    val segments by controller.segments.collectAsState()
    LaunchedEffect(segments, playback) {
        val p = playback ?: return@LaunchedEffect
        if (segments.isEmpty()) return@LaunchedEffect
        val skipped = HashSet<SkipSegment>()
        p.positionMs.collect { pos ->
            val hit = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 400 && it !in skipped } ?: return@collect
            skipped += hit
            p.seekTo(hit.endMs)
            controller.noteSkipped()
        }
    }
    // The sleep timer pauses the video when its minutes are up.
    val sleep by controller.sleep.collectAsState()
    LaunchedEffect(sleep.token) {
        if (sleep.mode == SleepMode.TIMER) {
            delay(sleep.minutes * 60_000L)
            playback?.pause()
            controller.clearSleep()
        }
    }
    val upright by controller.fullscreenUpright.collectAsState()
    FullscreenEffect(fullscreen && top is VideoRoute.Watch, upright = upright)
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
        // Scrolling a feed down hides the bars, scrolling up brings them back (as on YouTube).
        val bars = remember { mutableStateOf(true) }
        val hideOnScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < -4f) bars.value = false else if (available.y > 4f) bars.value = true
                    return Offset.Zero
                }
            }
        }
        val feedPage = top == null && tab != VideoTab.SHORTS
        LaunchedEffect(tab, routes.size) { bars.value = true }
        val miniShown = watch != null && top !is VideoRoute.Watch && top !is VideoRoute.Shorts && !(top == null && tab == VideoTab.SHORTS) && !pip
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().then(if (feedPage) Modifier.nestedScroll(hideOnScroll) else Modifier)) {
                // The page under the video shows while the video is pulled down into the small player (as on YouTube),
                // instead of black; it stays where it is once the video is small, so nothing reloads.
                val drag by controller.watchDrag.collectAsState()
                val watching = top is VideoRoute.Watch
                val below = if (watching) routes.dropLast(1).lastOrNull() else top
                if (!watching || drag > 0f) {
                    // Pages slide over one another as they open and close; the tabs fade through.
                    com.qmusic.ui.motion.PageHost(
                        target = below,
                        depth = routes.count { it !is VideoRoute.Watch },
                        contentKey = { r -> r?.let { if (it is VideoRoute.Watch) null else it } },
                        background = VideoColors.Background,
                    ) { page ->
                        when (page) {
                            // (Shorts play in the same player: not drawn under a video.)
                            null -> if (!watching || tab != VideoTab.SHORTS) {
                                androidx.compose.animation.AnimatedContent(
                                    targetState = tab,
                                    transitionSpec = { fadeThrough() },
                                    label = "videoTab",
                                ) { shownTab ->
                                    TabContent(controller, shownTab, s, signedIn, onSignIn, playback, onPauseMusic, onShare, onAccount = { accountDialog = true }, barsVisible = bars.value || !feedPage)
                                }
                            }
                            is VideoRoute.Search -> SearchResultsPage(controller, s, onSignIn)
                            is VideoRoute.Watch -> Unit
                            is VideoRoute.Channel -> ChannelPage(controller, s, onSignIn)
                            is VideoRoute.Playlist -> PlaylistPage(controller, page.title, s, onSignIn)
                            is VideoRoute.Shorts -> if (!watching) ShortsPage(controller, playback, s, true, onPauseMusic, onShare, onSignIn)
                        }
                    }
                }
                // A video opens rising from below (as on YouTube); it leaves by being pulled down into the small player.
                androidx.compose.animation.AnimatedVisibility(
                    visible = watching,
                    modifier = Modifier,
                    enter = androidx.compose.animation.slideInVertically(com.qmusic.ui.motion.Motion.enter(com.qmusic.ui.motion.Motion.LONG)) { it / 3 } +
                        androidx.compose.animation.fadeIn(com.qmusic.ui.motion.Motion.enter(com.qmusic.ui.motion.Motion.MEDIUM)),
                    exit = androidx.compose.animation.ExitTransition.None,
                ) {
                    WatchPage(controller, playback, s, onShare, onSignIn)
                }
                // The video that was pulled down floats in a corner over the page, as on YouTube.
                val w = watch
                if (w != null && miniShown) {
                    FloatingMiniPlayer(w, playback, onOpen = { controller.push(VideoRoute.Watch(w.video)) }, onClose = { controller.closeWatch() })
                }
            }
            // While the video floats small the bottom bar is away; closing the video brings it back.
            if (top !is VideoRoute.Watch && !pip && !miniShown) {
                AnimatedVisibility(visible = bars.value || !feedPage, enter = expandVertically(expandFrom = Alignment.Top), exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
                    VideoBottomBar(tab = tab.takeIf { routes.isEmpty() }, s = s, onTab = { t -> if (t == tab && routes.isEmpty()) controller.refreshCurrent() else controller.selectTab(t) }, onMusic = exit, onChat = onChat, chatUnread = chatUnread)
                }
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
private val NoStalls: StateFlow<Int> = MutableStateFlow(0)
private val NoPosition: StateFlow<Long> = MutableStateFlow(0L)

/**
 * The video that was pulled down, small and floating in a corner over the page (as on YouTube): tap to open it again,
 * play / pause and close on it, drag it anywhere; let go and it settles on the nearer side.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FloatingMiniPlayer(w: WatchState, playback: VideoPlayback?, onOpen: () -> Unit, onClose: () -> Unit) {
    val playing by (playback?.isPlaying ?: NotPlaying).collectAsState()
    val position by (playback?.positionMs ?: NoPosition).collectAsState()
    val duration by (playback?.durationMs ?: NoPosition).collectAsState()
    val density = LocalDensity.current
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var card by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val margin = with(density) { 12.dp.toPx() }
    val minX = -(area.width - card.width - 2 * margin).coerceAtLeast(0f)
    val minY = -(area.height - card.height - 2 * margin).coerceAtLeast(0f)
    Box(Modifier.matchParentSize().onSizeChanged { area = it })
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(margin.let { with(density) { it.toDp() } })
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .fillMaxWidth(0.5f)
            .widthIn(max = 300.dp)
            .aspectRatio(16f / 9f)
            .onSizeChanged { card = it }
            .shadow(14.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(InkInverse)
            .pointerInput(minX, minY) {
                detectDragGestures(
                    onDragEnd = { offset = Offset(if (offset.x < minX / 2) minX else 0f, offset.y) },
                ) { change, drag ->
                    change.consume()
                    offset = Offset((offset.x + drag.x).coerceIn(minX, 0f), (offset.y + drag.y).coerceIn(minY, 0f))
                }
            }
            .clickable(onClick = onOpen),
    ) {
        if (w.video.thumbnail != null) AsyncImage(model = w.video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (playback != null && w.quality != null) VideoPlaybackSurface(playback, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxWidth().height(44.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(InkInverse.copy(alpha = 0.55f), Color.Transparent)),
            ),
        )
        IconButton(onClick = { playback?.togglePlay() }, modifier = Modifier.align(Alignment.TopStart).size(40.dp)) {
            Icon(if (playing) QMusicIcons.Pause else QMusicIcons.Play, contentDescription = if (playing) com.qmusic.ui.A11y.pause else com.qmusic.ui.A11y.play, tint = Ink, modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
            Icon(QMusicIcons.Close, contentDescription = com.qmusic.ui.A11y.close, tint = Ink, modifier = Modifier.size(20.dp))
        }
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Ink.copy(alpha = 0.25f))) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(3.dp).background(VideoColors.Red))
        }
    }
}

@Composable
private fun TopBar(s: VideoStrings, onSearch: () -> Unit, onAccount: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(VideoColors.Background).padding(start = 12.dp, end = 4.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's written logo, as on the music and the messages.
        Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            com.qmusic.ui.theme.NuveloWordmark(letterHeight = 18.dp, color = VideoColors.Text)
        }
        IconButton(onClick = onSearch) { Icon(QMusicIcons.Search, s.searchHint, tint = VideoColors.Text) }
        IconButton(onClick = onAccount) { Icon(QMusicIcons.Account, s.account, tint = VideoColors.Text) }
    }
}

@Composable
private fun VideoBottomBar(tab: VideoTab?, s: VideoStrings, onTab: (VideoTab) -> Unit, onMusic: () -> Unit, onChat: (() -> Unit)?, chatUnread: Int) {
    // The same bar as the messages and the music, with both on it.
    com.qmusic.ui.components.QeyfBar(VideoColors.Background) {
        QeyfBarItem(s.tabHome, QMusicIcons.Home, tab == VideoTab.HOME) { onTab(VideoTab.HOME) }
        QeyfBarItem(s.shorts, VideoIcons.Shorts, tab == VideoTab.SHORTS) { onTab(VideoTab.SHORTS) }
        QeyfBarItem(s.tabSubscriptions, QMusicIcons.VideoPlay, tab == VideoTab.SUBSCRIPTIONS) { onTab(VideoTab.SUBSCRIPTIONS) }
        QeyfBarItem(s.tabLibrary, QMusicIcons.Library, tab == VideoTab.LIBRARY) { onTab(VideoTab.LIBRARY) }
        QeyfBarItem(s.backToMusic, QMusicIcons.Headphones, false, onClick = onMusic)
        if (onChat != null) QeyfBarItem(com.qmusic.ui.chat.ChatMusic.words().messages, QMusicIcons.Chat, false, count = chatUnread, onClick = onChat)
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
private fun PageBar(title: String, onBack: () -> Unit, onTitleClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(VideoColors.Background).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(QMusicIcons.Back, null, tint = VideoColors.Text) }
        Text(
            title, color = VideoColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
                .then(if (onTitleClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTitleClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
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
    barsVisible: Boolean = true,
) {
    if (tab == VideoTab.SHORTS) {
        ShortsPage(controller, playback, s, false, onPauseMusic, onShare, onSignIn)
        return
    }
    val refresh by controller.refresh.collectAsState()
    Column(modifier = Modifier.fillMaxSize().background(VideoColors.Background).statusBarsPadding()) {
        AnimatedVisibility(visible = barsVisible, enter = expandVertically(expandFrom = Alignment.Bottom), exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
            TopBar(s, onSearch = { controller.openSearchBox() }, onAccount = onAccount)
        }
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
    var downloadsView by remember { mutableStateOf(false) }
    val loader = controller.library(section)
    val state by loader.state.collectAsState()
    LaunchedEffect(signedIn, section) { if (signedIn) loader.load() }
    val chips: @Composable () -> Unit = {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibrarySection.entries) { entry ->
                val label = when (entry) {
                    LibrarySection.HISTORY -> s.history
                    LibrarySection.LIKED -> s.liked
                    LibrarySection.WATCH_LATER -> s.watchLater
                    LibrarySection.PLAYLISTS -> s.playlists
                }
                LabelChip(label, !downloadsView && entry == section) {
                    downloadsView = false
                    section = entry
                }
            }
            if (controller.canDownload) item { LabelChip(s.extras().downloads, downloadsView) { downloadsView = true } }
        }
    }
    if (downloadsView) {
        DownloadsPage(controller, s, chips)
        return
    }
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
            item(key = "sections") { chips() }
        },
    )
}

/** The videos kept on the device: they play without the network, and can be deleted here. */
@Composable
private fun DownloadsPage(controller: VideosController, s: VideoStrings, chips: @Composable () -> Unit) {
    val x = s.extras()
    val list by controller.downloads.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        chips()
        if (list.isEmpty()) {
            Text(x.noDownloads, color = VideoColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.item.id }) { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VideoRowCard(
                        d.item, s,
                        onClick = {
                            val done = list.filter { it.state == DownloadState.DONE }.map { it.item }
                            val at = done.indexOfFirst { it.id == d.item.id }
                            if (at >= 0) controller.openFrom(done, at) else controller.openFrom(listOf(d.item), 0)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                        Text(
                            when (d.state) {
                                DownloadState.DONE -> "${d.bytes / 1_048_576} MB"
                                DownloadState.DOWNLOADING -> "%${d.percent}"
                                DownloadState.QUEUED -> "…"
                                DownloadState.FAILED -> x.downloadFailed
                            },
                            color = if (d.state == DownloadState.FAILED) VideoColors.Red else VideoColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                        IconButton(onClick = { controller.removeDownload(d.item.id) }) {
                            Icon(
                                if (d.state == DownloadState.DONE) QMusicIcons.Trash else QMusicIcons.Close,
                                if (d.state == DownloadState.DONE) x.removeDownload else x.cancelDownload,
                                tint = VideoColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- search

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchBox(controller: VideosController, s: VideoStrings) {
    val query by controller.query.collectAsState()
    val suggestions by controller.suggestions.collectAsState()
    val history by controller.searchHistory.collectAsState()
    val pictures by controller.searchPictures.collectAsState()
    val mayLike by controller.mayLike.collectAsState()
    val x = s.extras()
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { controller.fillSearchPictures() }
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
                // Opened with words in it, the cursor stands at their end, ready to change them.
                var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))) }
                if (field.text != query) field = androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != query) controller.typeQuery(it.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = VideoColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(VideoColors.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.submitSearch(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { controller.typeQuery("") }) { Icon(QMusicIcons.Close, contentDescription = com.qmusic.ui.A11y.close, tint = VideoColors.Text) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (query.isBlank() && history.isNotEmpty()) {
                // The past searches as YouTube has them: each with its first video's picture; held down, it can go.
                items(history, key = { "h$it" }) { text ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { controller.submitSearch(text) }, onLongClick = { removing = text })
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QMusicIcons.History, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(20.dp))
                        Text(text, color = VideoColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SearchPicture(pictures[text.lowercase()])
                        IconButton(onClick = { controller.typeQuery(text) }, modifier = Modifier.size(44.dp)) {
                            Icon(QMusicIcons.FillQuery, contentDescription = com.qmusic.ui.A11y.fillQuery, tint = VideoColors.Text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            // "You may like": channels watched lately and not searched for yet.
            if (query.isBlank() && mayLike.isNotEmpty()) {
                item(key = "maylike-header") {
                    Text(
                        x.mayLike,
                        color = VideoColors.Text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    )
                }
                items(mayLike, key = { "m" + it.first }) { (name, picture) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { controller.submitSearch(name) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QMusicIcons.Search, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = VideoColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(x.fromWatchedChannels, color = VideoColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                        SearchPicture(picture)
                        IconButton(onClick = { controller.typeQuery(name) }, modifier = Modifier.size(44.dp)) {
                            Icon(QMusicIcons.FillQuery, contentDescription = com.qmusic.ui.A11y.fillQuery, tint = VideoColors.Text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            // At the foot of the past searches: all of them away.
            if (query.isBlank() && history.isNotEmpty()) {
                item(key = "history-clear") {
                    Text(
                        s.clearSearches, color = VideoColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(50)).clickable { controller.clearSearches() }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            items(if (query.isBlank()) emptyList() else suggestions) { text ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { controller.submitSearch(text) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.Search, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(text, color = VideoColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // A roomy target for putting the words in the box (a small one was missed and searched instead).
                    IconButton(onClick = { controller.typeQuery(text) }, modifier = Modifier.size(40.dp)) {
                        Icon(QMusicIcons.FillQuery, contentDescription = com.qmusic.ui.A11y.fillQuery, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    removing?.let { text ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = VideoColors.Surface,
            title = { Text(text, color = VideoColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(x.removeSearch, color = VideoColors.TextSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    controller.forgetSearch(text)
                    removing = null
                }) { Text(x.removeLabel, color = VideoColors.Red) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text(x.cancelLabel, color = VideoColors.Text) } },
        )
    }
}

/** A past search's picture: its first video's, small and rounded (nothing when it has none yet). */
@Composable
private fun SearchPicture(url: String?) {
    Box(Modifier.padding(start = 12.dp).width(80.dp).height(45.dp)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(VideoColors.Surface),
            )
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
        // The words searched for: a tap opens the search with them in it, to change them (as on YouTube).
        PageBar(title = query, onBack = { controller.pop() }, onTitleClick = { controller.openSearchBox(query) }, trailing = {
            IconButton(onClick = { controller.openSearchBox(query) }) { Icon(QMusicIcons.Search, s.searchHint, tint = VideoColors.Text) }
        })
        val current = loader
        val searchState = current?.state?.collectAsState()?.value
        // The search's own chips when it has them ("All", "Shorts", "Unwatched", "Watched", "Recently uploaded"...),
        // else the fixed filters.
        val searchChips = searchState?.chips.orEmpty()
        if (searchChips.isNotEmpty() && filter == null) {
            ChipRow(searchChips) { current?.selectChip(it) }
        } else {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VideosController.FILTERS) { (params, key) ->
                    LabelChip(s.filter(key), filter == params) { controller.setFilter(params) }
                }
            }
        }
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
                                color = if (c.subscribed) VideoColors.Text else InkInverse,
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
                            // The order of the videos: latest, popular, oldest.
                            if (c.feed.chips.isNotEmpty()) ChipRow(c.feed.chips) { controller.selectChannelChip(it) }
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
    val autoplay by controller.autoplay.collectAsState()
    val sponsor by controller.sponsorBlock.collectAsState()
    val x = s.extras()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!signedIn) Text(s.signInBody, color = VideoColors.TextSecondary, fontSize = 14.sp)
        SwitchRow(x.autoplay, null, autoplay != "0") { controller.setAutoplay(it) }
        SwitchRow(x.sponsorBlock, x.sponsorBlockHint, sponsor != "0") { controller.setSponsorBlock(it) }
        val feedPreview by controller.feedPreviewSetting.collectAsState()
        Text(x.feedPreview, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(x.feedPreviewHint, color = VideoColors.TextSecondary, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("2" to x.previewAlways, "1" to x.previewWifi, "0" to x.previewOff)) { (mode, label) ->
                LabelChip(label, feedPreview == mode) { controller.setFeedPreview(mode) }
            }
        }
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

@Composable
internal fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, color = VideoColors.TextSecondary, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = VideoColors.Red, checkedThumbColor = Ink),
        )
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

/**
 * Tells YouTube what the account watches, as its website does: "started" after a few seconds of playing, then the
 * stretch watched every [WATCHTIME_EVERY_MS] while it plays, and the last stretch when the video is left. Jumps (a seek)
 * start a new stretch instead of counting as watched.
 */
@Composable
internal fun ReportWatching(controller: VideosController, id: String?, playback: VideoPlayback?) {
    LaunchedEffect(id, playback) {
        if (id == null || playback == null) return@LaunchedEffect
        playback.isPlaying.first { it }
        delay(3_000)
        controller.startWatch(id)
        var from = playback.positionMs.value / 1000.0
        fun length() = playback.durationMs.value.coerceAtLeast(0L) / 1000.0
        try {
            while (true) {
                delay(WATCHTIME_EVERY_MS)
                val to = playback.positionMs.value / 1000.0
                val stretch = to - from
                // Played on (not paused, not jumped): that much was watched.
                if (playback.isPlaying.value && stretch > 0.5 && stretch < WATCHTIME_EVERY_MS / 1000.0 * 2.5) {
                    controller.watchProgress(id, from, to, length(), ended = false)
                }
                from = to
            }
        } finally {
            val to = playback.positionMs.value / 1000.0
            val stretch = to - from
            controller.watchProgress(id, from, if (stretch > 0 && stretch < WATCHTIME_EVERY_MS / 1000.0 * 2.5) to else from, length(), ended = true)
        }
    }
}

private const val WATCHTIME_EVERY_MS = 10_000L
