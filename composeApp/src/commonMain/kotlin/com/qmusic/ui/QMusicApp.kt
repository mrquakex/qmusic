package com.qmusic.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.strings.forService
import com.qmusic.ui.strings.TurkishStrings
import com.qmusic.ui.auth.YouTubeLoginView
import com.qmusic.ui.components.AddToPlaylistDialog
import com.qmusic.ui.components.EqualizerDialog
import com.qmusic.ui.components.MorphingCover
import com.qmusic.ui.components.upscaleThumbnail
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.qmusic.ui.components.MiniPlayerBar
import com.qmusic.ui.components.NavBarItem
import com.qmusic.ui.components.QMusicNavigationBar
import com.qmusic.ui.components.NowPlayingScreen
import com.qmusic.ui.components.QMusicSplash
import com.qmusic.ui.components.SongActionsSheet
import com.qmusic.ui.components.SpotifyImportDialog
import com.qmusic.ui.screens.ArtistScreen
import com.qmusic.engine.models.CollectionKind
import com.qmusic.ui.screens.CollectionScreen
import com.qmusic.ui.screens.ExploreScreen
import com.qmusic.ui.screens.ForYouScreen
import com.qmusic.ui.screens.StatsScreen
import com.qmusic.ui.screens.HomeScreen
import com.qmusic.ui.screens.LibraryScreen
import com.qmusic.ui.screens.LocalPlaylistScreen
import com.qmusic.ui.screens.SearchScreen
import com.qmusic.ui.screens.SettingsScreen
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.QMusicHeaderLogo
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicTheme
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.Route
import com.qmusic.ui.viewmodel.ScreenTab

private const val FLICK_VELOCITY = 900f

/** Vertical padding above the mini player's cover, to find the bar's top edge from the cover's. */
private const val MINI_PADDING_DP = 8f

/** From here the real cover of the full player takes over from the morphing one. */
private const val COVER_HANDOVER = 0.995f
private const val SETTLE_AFTER_MS = 700L

data class NavigationTabItem(
    val tab: ScreenTab,
    val title: String,
    val icon: ImageVector,
)

@Composable
fun QMusicApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val amoled by viewModel.amoledTheme.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val light = themeMode == "light" || (themeMode == "system" && !systemDark)
    // Set before the first frame is drawn, so the app never flashes the other theme.
    if (com.qmusic.ui.theme.lightTheme != light) com.qmusic.ui.theme.lightTheme = light
    androidx.compose.runtime.SideEffect { com.qmusic.ui.theme.amoledTheme = amoled }
    SystemBarsTheme(light)
    QMusicTheme {

        val language by viewModel.language.collectAsState()
        val accountMode by viewModel.accountMode.collectAsState()
        val spotifySignedIn by viewModel.spotifySignedIn.collectAsState()
        val showSpotifyLoginDialog by viewModel.showSpotifyLoginDialog.collectAsState()
        val strings = (if (language == "en") EnglishStrings else TurkishStrings)
            .forService(accountMode == com.qmusic.settings.AccountMode.SPOTIFY)

        CompositionLocalProvider(LocalStrings provides strings) {
            if (com.qmusic.ui.tv.TvScreen.isTv && accountMode == com.qmusic.settings.AccountMode.UNSET) {
                LaunchedEffect(Unit) { viewModel.chooseAccount(com.qmusic.settings.AccountMode.NONE) }
            }
            // First launch: which account brings the music (the sign-in pages open over it).
            if (accountMode == com.qmusic.settings.AccountMode.UNSET) {
                val ytLogin by viewModel.showYouTubeLoginDialog.collectAsState()
                val desktopActions = com.qmusic.ui.components.LocalDesktopWindowActions.current
                PlatformBackHandler(enabled = ytLogin) { viewModel.setShowYouTubeLoginDialog(false) }
                PlatformBackHandler(enabled = showSpotifyLoginDialog) { viewModel.setShowSpotifyLoginDialog(false) }
                Box(modifier = modifier.fillMaxSize()) {
                    com.qmusic.ui.screens.WelcomeScreen(
                        onYouTube = { viewModel.chooseAccount(com.qmusic.settings.AccountMode.YOUTUBE) },
                        onSpotify = { viewModel.chooseAccount(com.qmusic.settings.AccountMode.SPOTIFY) },
                        onWithoutAccount = { viewModel.chooseAccount(com.qmusic.settings.AccountMode.NONE) },
                    )
                    if (ytLogin) {
                        YouTubeLoginView(
                            onCookiesCaptured = { cookies -> viewModel.onYouTubeCookiesCaptured(cookies) },
                            onDismiss = { viewModel.setShowYouTubeLoginDialog(false) },
                        )
                    }
                    if (showSpotifyLoginDialog) {
                        com.qmusic.ui.auth.SpotifyLoginView(
                            onSignedIn = { viewModel.onSpotifySignedIn() },
                            onDismiss = { viewModel.setShowSpotifyLoginDialog(false) },
                        )
                    }
                    if (desktopActions != null) {
                        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                            desktopActions.dragArea {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    com.qmusic.ui.components.DesktopWindowCaptionButtons(desktopActions)
                                }
                            }
                        }
                    }
                }
                return@CompositionLocalProvider
            }
            val navTabs = remember(strings) {
                listOfNotNull(
                    NavigationTabItem(ScreenTab.HOME, strings.tabHome, QMusicIcons.Home),
                    NavigationTabItem(ScreenTab.FOR_YOU, strings.tabForYou, QMusicIcons.Samples).takeIf { FOR_YOU_SHOWN },
                    NavigationTabItem(ScreenTab.SEARCH, strings.tabSearch, QMusicIcons.Search),
                    NavigationTabItem(ScreenTab.LIBRARY, strings.tabLibrary, QMusicIcons.Library),
                    NavigationTabItem(ScreenTab.SETTINGS, strings.tabSettings, QMusicIcons.Settings),
                )
            }
            // "Sana Özel" is hidden for now: a phone that was left on it opens on the home page.
            LaunchedEffect(Unit) {
                viewModel.currentTab.collect { tab -> if (!FOR_YOU_SHOWN && tab == ScreenTab.FOR_YOU) viewModel.selectTab(ScreenTab.HOME) }
            }

            // The Videos section (YouTube itself) is entered from the navigation bar and takes the whole screen, like
            // another app; the music keeps playing underneath (or is paused by the first video).
            var videosOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            var videoLogin by remember { mutableStateOf(false) }
            val videoScope = rememberCoroutineScope()
            val videosController = remember {
                com.qmusic.ui.videos.VideosController(
                    service = com.qmusic.engine.video.YouTubeVideos(),
                    scope = videoScope,
                    settings = viewModel.settings,
                    musicSession = { viewModel.youTubeAuth },
                    isUnmetered = { viewModel.isOnUnmetered() },
                )
            }
            // Messages: full screen like the videos, entered from the button on the home page (or a notification).
            var chatOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            val chatBackend = com.qmusic.ui.chat.rememberChatBackend()
            val chatScope = rememberCoroutineScope()
            val chatController = remember(chatBackend) { chatBackend?.let { com.qmusic.ui.chat.ChatController(it, chatScope) } }
            // The music side, for statuses: finding a song, and the address of its sound to play it in the status.
            LaunchedEffect(chatController) {
                val controller = chatController ?: return@LaunchedEffect
                controller.searchSongs = { query ->
                    viewModel.youTube.search(query, com.qmusic.engine.models.SearchFilter.SONGS).getOrNull()?.items.orEmpty().take(25).map { song ->
                        com.qmusic.ui.chat.SharedItem(com.qmusic.ui.chat.ShareKind.SONG, song.id, song.title, song.artists.joinToString(", ") { it.name }, song.thumbnail)
                    }
                }
                controller.songAudio = { id ->
                    viewModel.youTube.resolveStreamUrls(id).getOrNull()?.firstOrNull { !it.isDrm }?.url
                }
            }
            // Listening / watching together: the music player and the video player as the session sees them.
            // (They are the TV's players too, when a phone plays on it: see TvScreen.)
            val togetherMusic = remember {
                object : com.qmusic.ui.chat.TogetherPlayer {
                    override fun current() = viewModel.currentTrack.value?.let { song ->
                        com.qmusic.ui.chat.SharedItem(com.qmusic.ui.chat.ShareKind.SONG, song.id, song.title, song.artists.joinToString(", ") { it.name }, song.thumbnail)
                    }
                    override fun isPlaying() = viewModel.isPlaying.value
                    override fun positionMs() = viewModel.playbackPosition.value
                    override fun open(item: com.qmusic.ui.chat.SharedItem, positionMs: Long, play: Boolean) {
                        viewModel.playAt(
                            com.qmusic.engine.models.SongItem(
                                id = item.id,
                                title = item.title,
                                artists = item.subtitle.takeIf { it.isNotBlank() }?.let { listOf(com.qmusic.engine.models.Artist(it)) }.orEmpty(),
                                thumbnail = item.thumbnail,
                            ),
                            positionMs,
                        )
                        if (!play) viewModel.pause()
                    }
                    override fun seekTo(ms: Long) = viewModel.seekTo(ms)
                    override fun play() = viewModel.resume()
                    override fun pause() = viewModel.pause()
                }
            }
            val togetherVideo = remember {
                object : com.qmusic.ui.chat.TogetherPlayer {
                    // A video asked for before its player was ready: where to put it once it is.
                    var waiting: Triple<String, Long, Boolean>? = null
                    val playback get() = com.qmusic.ui.chat.TogetherVideo.playback
                    override fun current() = videosController.watch.value?.video?.let { v ->
                        com.qmusic.ui.chat.SharedItem(com.qmusic.ui.chat.ShareKind.VIDEO, v.id, v.title, v.channelName.orEmpty(), v.thumbnail)
                    }
                    override fun isPlaying() = playback?.isPlaying?.value == true
                    override fun positionMs() = playback?.positionMs?.value ?: 0L
                    override fun open(item: com.qmusic.ui.chat.SharedItem, positionMs: Long, play: Boolean) {
                        waiting = Triple(item.id, positionMs, play)
                        chatOpen = false
                        videosOpen = true
                        videosController.open(
                            com.qmusic.engine.video.VideoItem(id = item.id, title = item.title, channelName = item.subtitle.ifBlank { null }, thumbnail = item.thumbnail),
                        )
                    }
                    override fun seekTo(ms: Long) { playback?.seekTo(ms) }
                    override fun play() { if (playback?.isPlaying?.value == false) playback?.togglePlay() }
                    override fun pause() { playback?.pause() }
                    override fun tick() {
                        val (id, pos, play) = waiting ?: return
                        val p = playback ?: return
                        if (current()?.id != id || p.durationMs.value <= 0) return
                        waiting = null
                        p.seekTo(pos)
                        if (!play) p.pause()
                    }
                }
            }
            val tvScope = rememberCoroutineScope()
            // Songs as the sounds of Qeyf's calls: the music side's ringtones, its search, and its ringtone maker.
            LaunchedEffect(Unit) {
                val tones = com.qmusic.ui.chat.CallTones
                tones.searchSongs = { query ->
                    viewModel.youTube.search(query, com.qmusic.engine.models.SearchFilter.SONGS).getOrNull()?.items.orEmpty().take(25).map { song ->
                        com.qmusic.ui.chat.SharedItem(com.qmusic.ui.chat.ShareKind.SONG, song.id, song.title, song.artists.joinToString(", ") { it.name }, song.thumbnail)
                    }
                }
                tones.makeFromSong = { item ->
                    chatOpen = false
                    videosOpen = false
                    tones.openedFromChat = true
                    viewModel.openRingtoneEditor(
                        com.qmusic.engine.models.SongItem(
                            id = item.id,
                            title = item.title,
                            artists = item.subtitle.takeIf { it.isNotBlank() }?.let { listOf(com.qmusic.engine.models.Artist(it)) }.orEmpty(),
                            thumbnail = item.thumbnail,
                        ),
                    )
                }
                tones.backToChat = { chatOpen = true }
                viewModel.ringtones.saved.collect { list -> tones.made.value = list.map { com.qmusic.ui.chat.MadeTone(it.uri, it.title, it.artist, it.thumbnail) } }
            }
            androidx.compose.runtime.SideEffect {
                com.qmusic.ui.chat.CallTones.english = language == "en"
                com.qmusic.ui.tv.TvLink.english = language == "en"
                val tv = com.qmusic.ui.tv.TvScreen
                tv.music = togetherMusic
                tv.video = togetherVideo
                tv.musicIndex = { viewModel.queueIndex.value }
                tv.musicDuration = { viewModel.duration.value }
                tv.videoDuration = { com.qmusic.ui.chat.TogetherVideo.playback?.durationMs?.value ?: 0L }
                tv.jump = { index -> viewModel.skipToQueueIndex(index) }
                tv.next = { viewModel.next() }
                tv.previous = { viewModel.previous() }
                tv.playQueue = { songs, index, positionMs ->
                    chatOpen = false
                    videosOpen = false
                    viewModel.playerController.playQueue(songs, index)
                    if (positionMs > 1_500) {
                        tvScope.launch {
                            // Once the song is under way, on to where the phone was in it.
                            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                                viewModel.currentTrack.first { it?.id == songs[index].id }
                                viewModel.isPlaying.first { it }
                            } ?: return@launch
                            viewModel.seekTo(positionMs)
                        }
                    }
                }
            }
            // On a TV, a video sent from a phone fills the screen.
            if (com.qmusic.ui.tv.TvScreen.isTv) {
                val tvMode by com.qmusic.ui.tv.TvScreen.mode.collectAsState()
                val tvWatch by videosController.watch.collectAsState()
                LaunchedEffect(tvMode, tvWatch?.video?.id) {
                    if (tvMode == com.qmusic.ui.tv.TvMode.VIDEO && tvWatch != null) videosController.setFullscreen(true)
                }
            }
            LaunchedEffect(chatController) {
                val controller = chatController ?: return@LaunchedEffect
                if (controller.together != null) return@LaunchedEffect
                val music = togetherMusic
                val video = togetherVideo
                controller.together = com.qmusic.ui.chat.TogetherController(
                    backend = chatBackend!!,
                    scope = chatScope,
                    myUid = { (controller.account.value as? com.qmusic.ui.chat.ChatAccount.Ready)?.me?.uid },
                    players = { kind -> if (kind == com.qmusic.ui.chat.ShareKind.SONG) music else video },
                )
            }
            val chatLaunch by com.qmusic.ui.chat.ChatLaunch.request.collectAsState()
            LaunchedEffect(chatLaunch, chatController) {
                val request = chatLaunch ?: return@LaunchedEffect
                if (chatController == null) return@LaunchedEffect
                com.qmusic.ui.chat.ChatLaunch.request.value = null
                videosOpen = false
                chatOpen = true
                request.user?.let { user ->
                    if (request.profile) chatController.openProfileOf(user.uid, user) else chatController.openConversation(user)
                }
                request.username?.let { chatController.openByUsername(it) }
                request.invite?.let { chatController.openInvite(it) }
                request.callUsername?.let { chatController.openCallLink(it) }
            }
            // A ringing group call's notification: open the group; with Join, join its call (if it is still going on:
            // a call that ended meanwhile is not started again, which would ring everyone).
            val groupLaunch by com.qmusic.ui.chat.GroupCallLaunch.request.collectAsState()
            val askGroupCall = com.qmusic.ui.chat.rememberCallPermissions()
            LaunchedEffect(groupLaunch, chatController) {
                val request = groupLaunch ?: return@LaunchedEffect
                val controller = chatController ?: return@LaunchedEffect
                com.qmusic.ui.chat.GroupCallLaunch.request.value = null
                videosOpen = false
                chatOpen = true
                controller.openConversation(request.group)
                if (!request.join) return@LaunchedEffect
                val service = controller.groupCalls ?: return@LaunchedEffect
                kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    controller.account.first { it is com.qmusic.ui.chat.ChatAccount.Ready }
                } ?: return@LaunchedEffect
                val people = kotlinx.coroutines.withTimeoutOrNull(6_000) { controller.groupCallParticipants(request.group).first() }.orEmpty()
                val chatStrings = if (language == "en") com.qmusic.ui.chat.ChatStrings.English else com.qmusic.ui.chat.ChatStrings.Turkish
                if (people.isEmpty()) {
                    controller.reportError(chatStrings.groupCallEnded)
                    return@LaunchedEffect
                }
                askGroupCall(request.video) { granted ->
                    if (granted) service.join(request.group, request.video) else controller.reportError(chatStrings.callPermission)
                }
            }
            // "Call back" on a missed call: the conversation opens and the call starts (microphone, camera asked first).
            val callBackAsked by com.qmusic.ui.chat.CallLaunch.callBack.collectAsState()
            LaunchedEffect(callBackAsked, chatController) {
                val (peer, video) = callBackAsked ?: return@LaunchedEffect
                val controller = chatController ?: return@LaunchedEffect
                com.qmusic.ui.chat.CallLaunch.callBack.value = null
                videosOpen = false
                chatOpen = true
                controller.openConversation(peer)
                val service = controller.calls ?: return@LaunchedEffect
                kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    controller.account.first { it is com.qmusic.ui.chat.ChatAccount.Ready }
                } ?: return@LaunchedEffect
                askGroupCall(video) { granted ->
                    val chatStrings = if (language == "en") com.qmusic.ui.chat.ChatStrings.English else com.qmusic.ui.chat.ChatStrings.Turkish
                    if (granted) service.call(peer, video) else controller.reportError(chatStrings.callPermission)
                }
            }
            // A call (ringing, going on or just over) takes the whole screen, over any section.
            val callService = chatController?.calls
            val call by (callService?.state ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
            // A downloaded update while the app is open: a tap restarts it in the new version (never during a call).
            if (call == null) UpdateReadyBanner(english = language == "en")
            if (callService != null && call != null) {
                val chatStrings = if (language == "en") com.qmusic.ui.chat.ChatStrings.English else com.qmusic.ui.chat.ChatStrings.Turkish
                val askPermissions = com.qmusic.ui.chat.rememberCallPermissions()
                val acceptAskedAt by com.qmusic.ui.chat.CallLaunch.acceptRequestedAt.collectAsState()
                LaunchedEffect(acceptAskedAt, call?.phase, call?.id) {
                    val c = call ?: return@LaunchedEffect
                    if (acceptAskedAt == 0L || c.phase != com.qmusic.ui.chat.CallPhase.INCOMING) return@LaunchedEffect
                    com.qmusic.ui.chat.CallLaunch.acceptRequestedAt.value = 0L
                    // Only a fresh tap answers: an old one (its call long gone) is dropped, never applied to a new call.
                    val fresh = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - acceptAskedAt < com.qmusic.ui.chat.CallLaunch.ACCEPT_WINDOW_MS
                    if (fresh) askPermissions(c.video) { granted -> if (granted) callService.accept() else callService.decline() }
                }
                com.qmusic.ui.chat.CallScreen(
                    service = callService,
                    s = chatStrings,
                    onAccept = { video -> askPermissions(video) { granted -> if (granted) callService.accept() else callService.decline() } },
                    modifier = modifier,
                )
                return@CompositionLocalProvider
            }
            // A group call takes the whole screen too.
            val groupCallService = chatController?.groupCalls
            val groupCall by (groupCallService?.state ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
            if (groupCallService != null && groupCall != null) {
                val chatStrings = if (language == "en") com.qmusic.ui.chat.ChatStrings.English else com.qmusic.ui.chat.ChatStrings.Turkish
                com.qmusic.ui.chat.GroupCallScreen(
                    service = groupCallService,
                    s = chatStrings,
                    me = (chatController.account.value as? com.qmusic.ui.chat.ChatAccount.Ready)?.me,
                    modifier = modifier,
                )
                return@CompositionLocalProvider
            }
            if (chatOpen && chatController != null) {
                val chatStrings = if (language == "en") com.qmusic.ui.chat.ChatStrings.English else com.qmusic.ui.chat.ChatStrings.Turkish
                com.qmusic.ui.chat.ChatApp(
                    controller = chatController,
                    s = chatStrings,
                    shareables = {
                        listOfNotNull(
                            viewModel.currentTrack.value?.let { song ->
                                com.qmusic.ui.chat.SharedItem(
                                    kind = com.qmusic.ui.chat.ShareKind.SONG,
                                    id = song.id,
                                    title = song.title,
                                    subtitle = song.artists.joinToString(", ") { it.name },
                                    thumbnail = song.thumbnail,
                                )
                            },
                            videosController.watch.value?.video?.let { v ->
                                com.qmusic.ui.chat.SharedItem(
                                    kind = com.qmusic.ui.chat.ShareKind.VIDEO,
                                    id = v.id,
                                    title = v.title,
                                    subtitle = v.channelName.orEmpty(),
                                    thumbnail = v.thumbnail,
                                )
                            },
                        )
                    },
                    onOpenShare = { item ->
                        when (item.kind) {
                            com.qmusic.ui.chat.ShareKind.SONG -> {
                                viewModel.play(
                                    com.qmusic.engine.models.SongItem(
                                        id = item.id,
                                        title = item.title,
                                        artists = item.subtitle.takeIf { it.isNotBlank() }
                                            ?.let { listOf(com.qmusic.engine.models.Artist(it)) }.orEmpty(),
                                        thumbnail = item.thumbnail,
                                    ),
                                    radio = true,
                                )
                                chatOpen = false
                                viewModel.setPlayerExpanded(true)
                            }
                            com.qmusic.ui.chat.ShareKind.VIDEO -> {
                                chatOpen = false
                                videosOpen = true
                                videosController.open(
                                    com.qmusic.engine.video.VideoItem(
                                        id = item.id,
                                        title = item.title,
                                        channelName = item.subtitle.ifBlank { null },
                                        thumbnail = item.thumbnail,
                                    ),
                                )
                            }
                        }
                    },
                    onExit = { chatOpen = false },
                    modifier = modifier,
                )
                return@CompositionLocalProvider
            }
            val chatUnread by (chatController?.unreadCount ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
            // Until Google sign-in is set up for the app, the button is not shown at all.
            val chatAccount by (chatController?.account ?: kotlinx.coroutines.flow.MutableStateFlow(com.qmusic.ui.chat.ChatAccount.Unavailable())).collectAsState()
            val chatAvailable = chatController != null && chatAccount !is com.qmusic.ui.chat.ChatAccount.Unavailable

            if (videosOpen) {
                LaunchedEffect(Unit) {
                    videosController.refreshAccount()
                    videosController.warmUp()
                }
                val shareVideo = rememberShare()
                Box(modifier = modifier.fillMaxSize()) {
                    val videoStrings = if (language == "en") com.qmusic.ui.videos.VideoStrings.English else com.qmusic.ui.videos.VideoStrings.Turkish
                    com.qmusic.ui.videos.VideosApp(
                        controller = videosController,
                        s = videoStrings,
                        onExit = { videosOpen = false },
                        onSignIn = { videoLogin = true },
                        onPauseMusic = { viewModel.playerController.pause() },
                        onShare = shareVideo,
                    )
                    if (videoLogin) {
                        PlatformBackHandler(enabled = true) { videoLogin = false }
                        YouTubeLoginView(
                            onCookiesCaptured = { cookies ->
                                videosController.signedInWith(cookies)
                                videoLogin = false
                            },
                            onDismiss = { videoLogin = false },
                            title = videoStrings.signInPageTitle,
                        )
                    }
                }
                return@CompositionLocalProvider
            }

            val currentTab by viewModel.currentTab.collectAsState()
        val currentTrack by viewModel.currentTrack.collectAsState()
        val isPlaying by viewModel.isPlaying.collectAsState()
        // Only the players read this: it ticks every 250 ms and must not recompose the whole app.
        val playbackPositionState = viewModel.playbackPosition.collectAsState()
        val bufferedPositionState = viewModel.bufferedPosition.collectAsState()
        val volume by viewModel.volume.collectAsState()
        val share = rememberShare()
        val playbackSpeed by viewModel.playbackSpeed.collectAsState()
        val lyricsTranslation by viewModel.lyricsTranslation.collectAsState()
        val lyricsOffset by viewModel.lyricsOffsetMs.collectAsState()
        val lyricsMatches by viewModel.lyricsMatches.collectAsState()
        val moreSettings by viewModel.moreSettings.collectAsState()
        val seekPreview by viewModel.seekPreview.collectAsState()
        val related by viewModel.related.collectAsState()
        val backupFiles = rememberBackupFiles(
            content = { viewModel.exportBackup() },
            onSaved = { viewModel.onBackupSaved(it) },
            onOpened = { viewModel.importBackup(it) },
        )
        // Downloaded songs keep their large covers too: fetched once while online, they show in the full player
        // without a connection (the small cover is cached anyway by the lists that show it).
        val platformContext = LocalPlatformContext.current
        val prefetchedCovers = remember { HashSet<String>() }
        val songsWithCovers by viewModel.downloadedSongs.collectAsState()
        val onlineForCovers by viewModel.isOnline.collectAsState()
        LaunchedEffect(songsWithCovers, onlineForCovers) {
            if (!onlineForCovers) return@LaunchedEffect
            val loader = SingletonImageLoader.get(platformContext)
            songsWithCovers.forEach { song ->
                val url = song.thumbnail ?: song.thumbnails.lastOrNull()?.url ?: return@forEach
                if (prefetchedCovers.add(song.id)) {
                    listOf(800, 256).forEach { px ->
                        loader.enqueue(ImageRequest.Builder(platformContext).data(url.upscaleThumbnail(px)).build())
                    }
                }
            }
        }
        val samples by viewModel.samples.collectAsState()
        val isLoadingSamples by viewModel.isLoadingSamples.collectAsState()
        val listeningStats by viewModel.stats.collectAsState()
        val statsPeriod by viewModel.statsPeriod.collectAsState()
        val ytPlaylists by viewModel.ytPlaylists.collectAsState()
        val ytAlbums by viewModel.ytAlbums.collectAsState()
        val ytArtists by viewModel.ytArtists.collectAsState()
        val message by viewModel.message.collectAsState()
        val voiceSearch = rememberVoiceSearch { spoken -> viewModel.performSearch(spoken) }
        val songRecognition = rememberSongRecognition()
        val suggestedSearches by viewModel.suggestedSearches.collectAsState()
        val explorePage by viewModel.explorePage.collectAsState()
        val isLoadingExplore by viewModel.isLoadingExplore.collectAsState()
        val exploreError by viewModel.exploreError.collectAsState()
        val homeChips by viewModel.homeChips.collectAsState()
        val speedDial by viewModel.speedDial.collectAsState()
        val isOnline by viewModel.isOnline.collectAsState()
        val libraryStartTab by viewModel.libraryStartTab.collectAsState()
        val selectedHomeChip by viewModel.selectedHomeChip.collectAsState()
        val isLoadingMoreHome by viewModel.isLoadingMoreHome.collectAsState()
        val crashReport by viewModel.crashReport.collectAsState()
        val duration by viewModel.duration.collectAsState()
        val crossfadeDuration by viewModel.crossfadeDuration.collectAsState()
        val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsState()
        val showEqualizerDialog by viewModel.showEqualizerDialog.collectAsState()
        val showSpotifyImportDialog by viewModel.showSpotifyImportDialog.collectAsState()
        val showYouTubeLoginDialog by viewModel.showYouTubeLoginDialog.collectAsState()

        val equalizerBands by viewModel.equalizerBands.collectAsState()
        val selectedPreset by viewModel.selectedPreset.collectAsState()

        val queue by viewModel.queue.collectAsState()
        val queueIndex by viewModel.queueIndex.collectAsState()
        val shuffle by viewModel.shuffleEnabled.collectAsState()
        val repeatMode by viewModel.repeatMode.collectAsState()
        val sleepTimer by viewModel.sleepTimer.collectAsState()
        val lyricsState by viewModel.lyricsState.collectAsState()
        val videoMode by viewModel.videoMode.collectAsState()
        val videoPreferred by viewModel.videoPreferred.collectAsState()
        val playbackError by viewModel.playbackError.collectAsState()

        val homeSections by viewModel.homeSections.collectAsState()
        val isLoadingHome by viewModel.isLoadingHome.collectAsState()
        val homeError by viewModel.homeError.collectAsState()

        val routes by viewModel.routes.collectAsState()
        val collectionPage by viewModel.collectionPage.collectAsState()
        val isLoadingCollection by viewModel.isLoadingCollection.collectAsState()
        val collectionError by viewModel.collectionError.collectAsState()
        val artistPage by viewModel.artistPage.collectAsState()
        val isLoadingArtist by viewModel.isLoadingArtist.collectAsState()
        val artistError by viewModel.artistError.collectAsState()

        val searchQuery by viewModel.searchQuery.collectAsState()
        val selectedFilter by viewModel.selectedFilter.collectAsState()
        val searchPage by viewModel.searchPage.collectAsState()
        val isSearching by viewModel.isSearching.collectAsState()
        val searchError by viewModel.searchError.collectAsState()
        val showSearchResults by viewModel.showSearchResults.collectAsState()
        val searchSuggestions by viewModel.searchSuggestions.collectAsState()
        val searchHistory by viewModel.searchHistory.collectAsState()

        val favorites by viewModel.favorites.collectAsState()
        val history by viewModel.history.collectAsState()
        val playlists by viewModel.playlists.collectAsState()
        val downloadedIds by viewModel.downloadedIds.collectAsState()
        val downloadProgress by viewModel.downloadProgress.collectAsState()
        val downloadedSongs by viewModel.downloadedSongs.collectAsState()
        val downloadedBytes by viewModel.downloadedBytes.collectAsState()
        val autoDownload by viewModel.autoDownloadLiked.collectAsState()
        val wifiOnly by viewModel.wifiOnly.collectAsState()
        val videoByDefault by viewModel.videoByDefault.collectAsState()
        val autoCheckUpdates by viewModel.autoCheckUpdates.collectAsState()
        val updateNotifications by viewModel.updateNotifications.collectAsState()
        val autoUpdate by viewModel.autoUpdate.collectAsState()
        val audioQualityWifi by viewModel.audioQualityWifi.collectAsState()
        val audioQualityMobile by viewModel.audioQualityMobile.collectAsState()
        val normalizeVolume by viewModel.normalizeVolume.collectAsState()
        val offerUpdate by viewModel.offerUpdate.collectAsState()

        val menuSong by viewModel.menuSong.collectAsState()
        val playlistPickerSong by viewModel.playlistPickerSong.collectAsState()

        val isImportingSpotify by viewModel.isImportingSpotify.collectAsState()
        val spotifyImportProgress by viewModel.spotifyImportProgress.collectAsState()
        val spotifyImportStatus by viewModel.spotifyImportStatus.collectAsState()

        val isLoggedInYouTube by viewModel.isLoggedIn.collectAsState()
        val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
        val updateInfo by viewModel.updateInfo.collectAsState()
        val updateProgress by viewModel.updateProgress.collectAsState()
        val updateMessage by viewModel.updateMessage.collectAsState()

        val topRoute = routes.lastOrNull()
        // Read from the collected list (not the view model's snapshot), so every heart redraws the moment it changes.
        val favoriteIds = remember(favorites) { favorites.mapTo(HashSet()) { it.id } }
        val isCurrentTrackFavorite = currentTrack?.let { it.id in favoriteIds } ?: false

        // System Back: close the player first, then the topmost page.
        PlatformBackHandler(enabled = isPlayerExpanded) { viewModel.setPlayerExpanded(false) }
        PlatformBackHandler(enabled = !isPlayerExpanded && topRoute != null) { viewModel.popRoute() }

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val sheetHeightPx = constraints.maxHeight.toFloat()
            val scope = rememberCoroutineScope()
            // 0 = only the mini player, 1 = the full player. Dragging the mini player (or the full player's
            // cover) moves it live; letting go settles on whichever side is closer, or the flick's direction.
            val sheetProgress = remember { Animatable(0f) }
            val density = LocalDensity.current.density

            // Where the cover sits in the mini player and in the full player. The cover between them is drawn once
            // and interpolated, so it grows and slides continuously with the finger instead of cross-fading.
            var miniCover by remember { mutableStateOf<Rect?>(null) }
            var fullCover by remember { mutableStateOf<Rect?>(null) }

            // The sheet starts as the mini player's own top edge and rises to the top of the screen, so everything
            // that travels with it (the cover above all) stays inside it the whole way, as on YouTube Music.
            val sheetOriginPx: () -> Float = {
                (miniCover?.top?.minus(MINI_PADDING_DP * density) ?: (sheetHeightPx * 0.82f)).coerceAtLeast(1f)
            }
            LaunchedEffect(isPlayerExpanded) {
                sheetProgress.animateTo(
                    targetValue = if (isPlayerExpanded) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                )
            }
            var settleJob by remember { mutableStateOf<Job?>(null) }
            val settle: (Float) -> Unit = { velocity ->
                settleJob?.cancel()
                val expand = when {
                    velocity < -FLICK_VELOCITY -> true
                    velocity > FLICK_VELOCITY -> false
                    else -> sheetProgress.value > 0.5f
                }
                viewModel.setPlayerExpanded(expand)
                scope.launch {
                    sheetProgress.animateTo(
                        targetValue = if (expand) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                    )
                }
            }
            val onSheetDrag: (Float) -> Unit = { delta ->
                settleJob?.cancel()
                scope.launch { sheetProgress.snapTo((sheetProgress.value - delta / sheetOriginPx()).coerceIn(0f, 1f)) }
                // If the finger lifts without a stop event (interrupted gesture), still land on a side.
                settleJob = scope.launch {
                    delay(SETTLE_AFTER_MS)
                    settle(0f)
                }
            }
            val onSheetDragStopped: (Float) -> Unit = settle
            val sheetVisible by remember { derivedStateOf { sheetProgress.value > 0.001f } }

            // Where the cover sits in the mini player and in the full player. The cover between them is drawn once
            // and interpolated, so it grows and slides continuously with the finger instead of cross-fading.
            val coverSettled = { sheetProgress.value >= COVER_HANDOVER }

            val wide = maxWidth >= com.qmusic.ui.components.DESKTOP_MIN_WIDTH
            val selectTab: (Int) -> Unit = { index ->
                // Tapping a tab leaves any open page, as in every music app.
                viewModel.clearRoutes()
                viewModel.onTabClicked(navTabs[index].tab)
            }

            val screenBody: @Composable () -> Unit = {
                when (currentTab) {
                    ScreenTab.HOME -> HomeScreen(
                        sections = homeSections,
                        isOnline = isOnline,
                        downloads = downloadedSongs,
                        onOpenDownloads = { viewModel.openDownloads() },
                        speedDial = speedDial,
                        onRandomSong = { viewModel.playRandomSong() },
                        chips = homeChips,
                        selectedChip = selectedHomeChip,
                        isLoading = isLoadingHome,
                        isLoadingMore = isLoadingMoreHome,
                        error = homeError,
                        isLoggedIn = isLoggedInYouTube || (accountMode == com.qmusic.settings.AccountMode.SPOTIFY && spotifySignedIn),
                        scrollToTop = viewModel.homeScrollToTop,
                        onSongClick = { song, context -> viewModel.play(song, context, radio = true) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        onPlayAll = { viewModel.playAll(it) },
                        onChipClick = { viewModel.selectHomeChip(it) },
                        onSeeAll = { section -> section.moreBrowseId?.let { viewModel.openExplore(it, section.moreParams, section.title) } },
                        onOpenExplore = { id, title -> viewModel.openExplore(id, null, title) },
                        showExplore = accountMode != com.qmusic.settings.AccountMode.SPOTIFY,
                        currentTrack = currentTrack,
                        onCollectionClick = { viewModel.openCollection(it) },
                        onSearchClick = { viewModel.selectTab(ScreenTab.SEARCH) },
                        onChatClick = if (chatAvailable) ({ chatOpen = true }) else null,
                        chatUnread = chatUnread,
                        onSignIn = {
                            when (accountMode) {
                                com.qmusic.settings.AccountMode.SPOTIFY -> viewModel.setShowSpotifyLoginDialog(true)
                                com.qmusic.settings.AccountMode.YOUTUBE -> viewModel.setShowYouTubeLoginDialog(true)
                                else -> viewModel.changeAccount()
                            }
                        },
                        onRefresh = { viewModel.loadHomeContent() },
                        desktop = wide,
                    )

                    ScreenTab.FOR_YOU -> ForYouScreen(
                        samples = samples,
                        isLoading = isLoadingSamples,
                        isPlaying = isPlaying,
                        onTogglePlayback = { viewModel.toggleSamplePlayback() },
                        isFavorite = { it in favoriteIds },
                        onShown = { viewModel.prepareSamples() },
                        onSettle = { viewModel.playSample(it) },
                        onNearEnd = { viewModel.loadMoreSamples() },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { viewModel.showPlaylistPicker(it) },
                        onShare = { song ->
                            share("${song.title} - ${song.artists.joinToString { it.name }}\n${song.shareLink}")
                        },
                        onPlayFull = { viewModel.playSampleFully(it) },
                    )

                    ScreenTab.SEARCH -> SearchScreen(
                        query = searchQuery,
                        filter = selectedFilter,
                        page = searchPage,
                        isSearching = isSearching,
                        error = searchError,
                        showResults = showSearchResults,
                        suggestions = searchSuggestions,
                        history = searchHistory,
                        downloadedIds = downloadedIds,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                        onBack = { viewModel.clearSearch() },
                        onLoadMoreResults = { viewModel.loadMoreSearchResults() },
                        recentSongs = history,
                        suggestedSearches = suggestedSearches,
                        onOpenExplore = { id, title -> viewModel.openExplore(id, null, title) },
                        showExplore = accountMode != com.qmusic.settings.AccountMode.SPOTIFY,
                        onVoiceSearch = voiceSearch,
                        onRecognizeSong = songRecognition,
                        onFilterChange = { viewModel.setSearchFilter(it) },
                        onSearch = { viewModel.performSearch() },
                        onSuggestionClick = { viewModel.selectSuggestion(it) },
                        onHistoryClick = { viewModel.performSearch(it) },
                        onHistoryDelete = { viewModel.deleteSearchHistoryEntry(it) },
                        onClearHistory = { viewModel.clearSearchHistory() },
                        onSongClick = { song, context -> viewModel.play(song, context, radio = true) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        onCollectionClick = { viewModel.openCollection(it) },
                        onArtistClick = { viewModel.openArtist(it.browseId, it.name) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        isFavoritePredicate = { it in favoriteIds },
                    )

                    ScreenTab.LIBRARY -> LibraryScreen(
                        favorites = favorites,
                        history = history,
                        playlists = playlists,
                        downloads = downloadedSongs,
                        downloadedIds = downloadedIds,
                        downloadProgress = downloadProgress,
                        downloadedBytes = downloadedBytes,
                        autoDownload = autoDownload,
                        wifiOnly = wifiOnly,
                        onAutoDownloadChange = { viewModel.setAutoDownloadLiked(it) },
                        onWifiOnlyChange = { viewModel.setWifiOnly(it) },
                        onDownloadAll = { viewModel.downloadAll(it) },
                        onCancelDownloads = { viewModel.cancelDownloads() },
                        onRemoveAllDownloads = { viewModel.removeAllDownloads() },
                        onPlayAll = { viewModel.playAll(it) },
                        onShuffle = { viewModel.shuffleAll(it) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        onOpenPlaylist = { viewModel.openLocalPlaylist(it.id, it.name) },
                        onSongClick = { song, context -> viewModel.play(song, context) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        isFavoritePredicate = { it in favoriteIds },
                        onCreatePlaylist = { viewModel.createPlaylist(it) },
                        startTab = libraryStartTab,
                        ytPlaylists = ytPlaylists,
                        ytAlbums = ytAlbums,
                        ytArtists = ytArtists,
                        onOpenCollection = { viewModel.openCollection(it) },
                        onOpenStats = { viewModel.openStats() },
                    )

                    ScreenTab.SETTINGS -> SettingsScreen(
                        isLoggedInYouTube = isLoggedInYouTube,
                        crossfadeSeconds = crossfadeDuration,
                        isCheckingUpdate = isCheckingUpdate,
                        updateInfo = updateInfo,
                        updateMessage = updateMessage,
                        currentAppVersion = viewModel.currentAppVersion,
                        onLoginYouTubeClick = { viewModel.setShowYouTubeLoginDialog(true) },
                        onLogoutYouTubeClick = { viewModel.logoutYouTube() },
                        onCrossfadeChange = { viewModel.setCrossfadeDuration(it) },
                        onOpenEqualizerClick = { viewModel.setShowEqualizerDialog(true) },
                        onCheckUpdateClick = { viewModel.checkForUpdates() },
                        themeMode = themeMode,
                        onThemeMode = { viewModel.setThemeMode(it) },
                        crashReport = crashReport,
                        onBackupExport = backupFiles?.save,
                        onBackupImport = backupFiles?.open,
                        onShareCrashReport = { crashReport?.let(share) },
                        onClearCrashReport = { viewModel.clearCrashReport() },
                        videoByDefault = videoByDefault,
                        onVideoByDefaultChange = { viewModel.setVideoByDefault(it) },
                        autoDownload = autoDownload,
                        onAutoDownloadChange = { viewModel.setAutoDownloadLiked(it) },
                        wifiOnly = wifiOnly,
                        onWifiOnlyChange = { viewModel.setWifiOnly(it) },
                        downloadedBytes = downloadedBytes,
                        onRemoveAllDownloads = { viewModel.removeAllDownloads() },
                        autoCheckUpdates = autoCheckUpdates,
                        updateNotifications = updateNotifications,
                        autoUpdate = autoUpdate,
                        audioQualityWifi = audioQualityWifi,
                        onAudioQualityWifiChange = { viewModel.setAudioQualityWifi(it) },
                        audioQualityMobile = audioQualityMobile,
                        onAudioQualityMobileChange = { viewModel.setAudioQualityMobile(it) },
                        normalizeVolume = normalizeVolume,
                        onNormalizeVolumeChange = { viewModel.setNormalizeVolume(it) },
                        onAutoUpdateChange = { viewModel.setAutoUpdate(it) },
                        onUpdateNotificationsChange = { viewModel.setUpdateNotifications(it) },
                        onAutoCheckUpdatesChange = { viewModel.setAutoCheckUpdates(it) },
                        updateProgress = updateProgress,
                        onInstallUpdate = { viewModel.installUpdate() },
                        onOpenSpotifyImport = { viewModel.setShowSpotifyImportDialog(true) },
                        language = language,
                        onLanguageChange = { viewModel.setLanguage(it) },
                        more = moreSettings,
                        onMore = { viewModel.onMoreSettings(it) },
                        accountMode = accountMode,
                        spotifySignedIn = spotifySignedIn,
                        onSpotifySignIn = { viewModel.setShowSpotifyLoginDialog(true) },
                        onSpotifySignOut = { viewModel.signOutSpotify() },
                        onChangeAccount = { viewModel.changeAccount() },
                        onOpenRingtones = if (viewModel.ringtones.available) ({ viewModel.openRingtones() }) else null,
                    )
                }

                // Full-screen pages stacked above the tabs: the last one is on screen
                when (val route = topRoute) {
                    is Route.Collection -> CollectionScreen(
                        item = route.item,
                        page = collectionPage,
                        isLoading = isLoadingCollection,
                        error = collectionError,
                        onBack = { viewModel.popRoute() },
                        onSongClick = { song, context -> viewModel.play(song, context, gapless = route.item.kind == CollectionKind.ALBUM) },
                        onPlayAll = { viewModel.playAll(it, gapless = route.item.kind == CollectionKind.ALBUM) },
                        onShuffle = { viewModel.shuffleAll(it) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        downloadedIds = downloadedIds,
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        isFavoritePredicate = { it in favoriteIds },
                        onDownloadAll = { viewModel.downloadAll(it) },
                        onShare = {
                            share("${collectionPage?.title ?: route.item.title}\nhttps://music.youtube.com/browse/${route.item.browseId}")
                        },
                        onMove = { from, to -> viewModel.moveInYouTubePlaylist(route.item, from, to) },
                        onDeletePlaylist = { viewModel.deleteYouTubePlaylist(route.item) },
                    )

                    is Route.Artist -> ArtistScreen(
                        name = route.name,
                        page = artistPage,
                        isLoading = isLoadingArtist,
                        error = artistError,
                        downloadedIds = downloadedIds,
                        onBack = { viewModel.popRoute() },
                        onPlayAll = { viewModel.playAll(it) },
                        onShuffle = { viewModel.shuffleAll(it) },
                        onSongClick = { song, context -> viewModel.play(song, context) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        onCollectionClick = { viewModel.openCollection(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        isFavoritePredicate = { it in favoriteIds },
                        onToggleSubscribe = { viewModel.toggleArtistSubscription() },
                        onStartRadio = { viewModel.startRadio(it) },
                        onShare = { page ->
                            share("${page.name}\nhttps://music.youtube.com/channel/${page.channelId ?: route.browseId}")
                        },
                        onSeeAll = { section -> section.moreBrowseId?.let { viewModel.openExplore(it, section.moreParams, section.title) } },
                    )

                    is Route.RingtoneEditor -> com.qmusic.ui.screens.RingtoneEditorScreen(
                        studio = viewModel.ringtones,
                        onBack = { viewModel.popRoute() },
                        onOpenList = { viewModel.openRingtones() },
                    )

                    Route.Ringtones -> com.qmusic.ui.screens.RingtonesScreen(
                        studio = viewModel.ringtones,
                        onBack = { viewModel.popRoute() },
                    )

                    Route.Stats -> StatsScreen(
                        stats = listeningStats,
                        period = statsPeriod,
                        onPeriodChange = { viewModel.loadStats(it) },
                        onBack = { viewModel.popRoute() },
                        onShare = share,
                        onSongClick = { song, context -> viewModel.play(song, context) },
                    )

                    is Route.Explore -> ExploreScreen(
                        title = route.title,
                        page = explorePage,
                        isLoading = isLoadingExplore,
                        error = exploreError,
                        onBack = { viewModel.popRoute() },
                        onSongClick = { song, context -> viewModel.play(song, context, radio = true) },
                        onSongMore = { viewModel.showSongMenu(it) },
                        onPlayAll = { viewModel.playAll(it) },
                        onCollectionClick = { viewModel.openCollection(it) },
                        onMoodClick = { viewModel.openExplore(it.browseId, it.params, it.text) },
                        onSeeAll = { section -> section.moreBrowseId?.let { viewModel.openExplore(it, section.moreParams, section.title) } },
                    )

                    is Route.LocalPlaylist -> {
                        val songs by remember(route.id) { viewModel.getPlaylistSongs(route.id) }
                            .collectAsState(initial = emptyList<SongItem>())
                        LocalPlaylistScreen(
                            name = route.name,
                            songs = songs,
                            downloadedIds = downloadedIds,
                            onBack = { viewModel.popRoute() },
                            onPlayAll = { viewModel.playAll(it) },
                            onShuffle = { viewModel.shuffleAll(it) },
                            onSongClick = { song, context -> viewModel.play(song, context) },
                            onSongMore = { viewModel.showSongMenu(it) },
                            onRemoveSong = { viewModel.removeSongFromPlaylist(route.id, it) },
                            onRename = { viewModel.renamePlaylist(route.id, it) },
                            onDelete = { viewModel.deletePlaylist(route.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            isFavoritePredicate = { it in favoriteIds },
                            onReorder = { viewModel.reorderLocalPlaylist(route.id, it) },
                        )
                    }

                    null -> Unit
                }
            }

            if (wide) {
                var sidebarExpanded by remember { mutableStateOf(true) }
                val desktopDominant = rememberDominantColor(
                    currentTrack?.let { it.thumbnail ?: it.thumbnails.lastOrNull()?.url }
                )

                Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
                    com.qmusic.ui.components.DesktopTopNavBar(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { q ->
                            viewModel.setSearchQuery(q)
                            if (currentTab != ScreenTab.SEARCH) {
                                viewModel.clearRoutes()
                                viewModel.selectTab(ScreenTab.SEARCH)
                            }
                            if (isPlayerExpanded) viewModel.setPlayerExpanded(false)
                        },
                        onSearchSubmit = {
                            if (currentTab != ScreenTab.SEARCH) {
                                viewModel.clearRoutes()
                                viewModel.selectTab(ScreenTab.SEARCH)
                            }
                            if (isPlayerExpanded) viewModel.setPlayerExpanded(false)
                            viewModel.performSearch()
                        },
                        onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
                        onHomeClick = {
                            viewModel.setPlayerExpanded(false)
                            viewModel.clearRoutes()
                            viewModel.onTabClicked(ScreenTab.HOME)
                        },
                        onOpenEqualizer = { viewModel.setShowEqualizerDialog(true) },
                        onOpenStats = {
                            viewModel.setPlayerExpanded(false)
                            viewModel.openStats()
                        },
                        onOpenSettings = {
                            viewModel.setPlayerExpanded(false)
                            viewModel.clearRoutes()
                            viewModel.selectTab(ScreenTab.SETTINGS)
                        },
                        isLoggedIn = isLoggedInYouTube || (accountMode == com.qmusic.settings.AccountMode.SPOTIFY && spotifySignedIn),
                        onAccountClick = {
                            when (accountMode) {
                                com.qmusic.settings.AccountMode.SPOTIFY -> viewModel.setShowSpotifyLoginDialog(true)
                                com.qmusic.settings.AccountMode.YOUTUBE -> viewModel.setShowYouTubeLoginDialog(true)
                                else -> viewModel.changeAccount()
                            }
                        },
                        dominantTint = desktopDominant,
                        onOpenVideos = { videosOpen = true },
                        onOpenChat = if (chatAvailable) ({ chatOpen = true }) else null,
                        chatUnread = chatUnread,
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            com.qmusic.ui.components.DesktopLeftSidebar(
                                expanded = sidebarExpanded,
                                currentTab = currentTab,
                                onSelectTab = { tab ->
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.clearRoutes()
                                    viewModel.onTabClicked(tab)
                                },
                                favoritesCount = favorites.size,
                                downloadsCount = downloadedSongs.size,
                                playlists = playlists,
                                ytPlaylists = ytPlaylists,
                                onOpenFavorites = {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.clearRoutes()
                                    viewModel.selectTab(ScreenTab.LIBRARY)
                                },
                                onOpenDownloads = {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.openDownloads()
                                },
                                onOpenLocalPlaylist = {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.openLocalPlaylist(it.id, it.name)
                                },
                                onOpenCollection = {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.openCollection(it)
                                },
                                onCreatePlaylist = { viewModel.createPlaylist(it) },
                                dominantTint = desktopDominant,
                            )

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                screenBody()
                            }
                        }

                        // Toast / error banner
                        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            message?.let { text ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgSurface)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            playbackError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgSurface)
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }

                        // Desktop Watch Page (`ytmusic-player-page`) slides between Top Bar and Bottom Player Bar
                        if (sheetVisible) {
                            currentTrack?.let { track ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(0, ((1f - sheetProgress.value) * sheetHeightPx).roundToInt()) },
                                ) {
                                    com.qmusic.ui.components.DesktopWatchPage(
                                        track = track,
                                        isPlaying = isPlaying,
                                        positionMs = { playbackPositionState.value },
                                        durationMs = duration,
                                        isDownloaded = track.id in downloadedIds,
                                        downloadProgress = downloadProgress[track.id],
                                        sleepTimer = sleepTimer,
                                        queue = queue,
                                        queueIndex = queueIndex,
                                        lyrics = lyricsState,
                                        videoMode = videoMode,
                                        videoPreferred = videoPreferred,
                                        onSetVideoPreferred = { viewModel.setVideoPreferred(it) },
                                        onSeekTo = { viewModel.seekTo(it) },
                                        onToggleDownload = {
                                            if (track.id in downloadedIds) viewModel.removeDownload(track) else viewModel.download(track)
                                        },
                                        onSetSleepTimer = { viewModel.setSleepTimer(it) },
                                        onSleepAtEndOfTrack = { viewModel.setSleepAtEndOfTrack() },
                                        onOpenEqualizer = { viewModel.setShowEqualizerDialog(true) },
                                        onQueueItemClick = { viewModel.skipToQueueIndex(it) },
                                        onQueueItemRemove = { viewModel.removeFromQueue(it) },
                                        onSaveQueue = { viewModel.saveQueueAsPlaylist(it) },
                                        onClearQueue = { viewModel.clearQueue() },
                                        onLoadLyrics = { viewModel.loadLyrics() },
                                        related = related,
                                        onLoadRelated = { viewModel.loadRelated() },
                                        onRelatedSongClick = { song, context -> viewModel.play(song, context, radio = true) },
                                        onRelatedCollectionClick = {
                                            viewModel.setPlayerExpanded(false)
                                            viewModel.openCollection(it)
                                        },
                                        onRelatedSongMore = { viewModel.showSongMenu(it) },
                                        playbackSpeed = playbackSpeed,
                                        onCycleSpeed = { viewModel.cyclePlaybackSpeed() },
                                        lyricsTranslation = lyricsTranslation,
                                        onToggleTranslation = { viewModel.toggleLyricsTranslation() },
                                    )
                                }
                            }
                        }
                    }

                    currentTrack?.let { track ->
                        com.qmusic.ui.components.DesktopBottomPlayerBar(
                            currentTrack = track,
                            isPlaying = isPlaying,
                            positionMs = { playbackPositionState.value },
                            bufferedMs = { bufferedPositionState.value },
                            durationMs = duration,
                            isFavorite = isCurrentTrackFavorite,
                            shuffle = shuffle,
                            repeatMode = repeatMode,
                            volume = volume,
                            isExpanded = isPlayerExpanded,
                            dominantTint = desktopDominant,
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onPrevious = { viewModel.previous() },
                            onNext = { viewModel.next() },
                            onSeekTo = { viewModel.seekTo(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onAddToPlaylist = { viewModel.showPlaylistPicker(track) },
                            onMore = { viewModel.showSongMenu(track) },
                            onArtistClick = track.artists.firstOrNull { it.id != null }?.let { artist ->
                                {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.openArtist(artist.id!!, artist.name)
                                }
                            },
                            onVolumeChange = { viewModel.setVolume(it) },
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onCycleRepeat = { viewModel.cycleRepeatMode() },
                            onOpenEqualizer = { viewModel.setShowEqualizerDialog(true) },
                            onToggleExpand = { viewModel.setPlayerExpanded(!isPlayerExpanded) },
                        )
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        containerColor = DarkBackground,
                        topBar = {
                            // Pages bring their own header (and sit under the status bar), so the logo bar steps aside;
                            // Home draws its own, over its tinted top.
                            if (topRoute == null && currentTab != ScreenTab.HOME && currentTab != ScreenTab.SEARCH && currentTab != ScreenTab.FOR_YOU) {
                                Column(modifier = Modifier.fillMaxWidth().background(DarkBackground.copy(alpha = 0.92f))) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .statusBarsPadding()
                                            .padding(horizontal = 20.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        QMusicHeaderLogo(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        },
                        bottomBar = {
                            Column(
                                modifier = Modifier.graphicsLayer { translationY = sheetProgress.value * size.height },
                            ) {
                                message?.let { text ->
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BgSurface)
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                playbackError?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BgSurface)
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                }

                                // Mini player and navigation bar share one surface, like YouTube Music
                                // Not on "For you": its preview is full screen there, and is not what the player keeps.
                                if (currentTrack != null && currentTab != ScreenTab.FOR_YOU) {
                                    MiniPlayerBar(
                                        currentTrack = currentTrack,
                                        isPlaying = isPlaying,
                                        positionMs = { playbackPositionState.value },
                                        durationMs = duration,
                                        onTogglePlayPause = { viewModel.togglePlayPause() },
                                        onNext = { viewModel.next() },
                                        onPrevious = { viewModel.previous() },
                                        onClick = { viewModel.setPlayerExpanded(true) },
                                        onDragDelta = onSheetDrag,
                                        onDragStopped = onSheetDragStopped,
                                        coverAlpha = { if (sheetProgress.value > 0.001f) 0f else 1f },
                                        contentAlpha = { (1f - sheetProgress.value * 3f).coerceIn(0f, 1f) },
                                        onCoverPositioned = { rect -> if (sheetProgress.value < 0.001f) miniCover = rect },
                                    )
                                }

                                QMusicNavigationBar(
                                    items = navTabs.map { NavBarItem(it.title, it.icon) } +
                                        NavBarItem("QVideo", QMusicIcons.VideoPlay),
                                    selectedIndex = navTabs.indexOfFirst { it.tab == currentTab },
                                    onSelect = { index -> if (index >= navTabs.size) videosOpen = true else selectTab(index) },
                                )
                            }
                        },
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // Pages (artist, album, playlist) start at the very top: their pictures go under the
                                // status bar and their own headers keep clear of it.
                                .padding(
                                    if (topRoute == null && currentTab != ScreenTab.HOME && currentTab != ScreenTab.SEARCH && currentTab != ScreenTab.FOR_YOU) {
                                        innerPadding
                                    } else {
                                        PaddingValues(bottom = innerPadding.calculateBottomPadding())
                                    },
                                ),
                        ) {
                            screenBody()
                        }
                    }
                }

                // Full-screen player: it rises with the drag, over everything including the bottom bar
                if (sheetVisible) {
                    currentTrack?.let { track ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset { IntOffset(0, ((1f - sheetProgress.value) * sheetOriginPx()).roundToInt()) },
                        ) {
                            NowPlayingScreen(
                                track = track,
                                isPlaying = isPlaying,
                                positionMs = { playbackPositionState.value },
                                bufferedMs = { bufferedPositionState.value },
                                durationMs = duration,
                                isFavorite = isCurrentTrackFavorite,
                                isDownloaded = track.id in downloadedIds,
                                downloadProgress = downloadProgress[track.id],
                                shuffle = shuffle,
                                repeat = repeatMode,
                                sleepTimer = sleepTimer,
                                queue = queue,
                                queueIndex = queueIndex,
                                lyrics = lyricsState,
                                crossfadeSeconds = crossfadeDuration,
                                videoMode = videoMode,
                                videoPreferred = videoPreferred,
                                onSetVideoPreferred = { viewModel.setVideoPreferred(it) },
                                onDismiss = { viewModel.setPlayerExpanded(false) },
                                onSheetDrag = onSheetDrag,
                                onSheetDragStopped = onSheetDragStopped,
                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                onSeekTo = { viewModel.seekTo(it) },
                                onPrevious = { viewModel.previous() },
                                onNext = { viewModel.next() },
                                onToggleFavorite = { viewModel.toggleFavorite(track) },
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                onCycleRepeat = { viewModel.cycleRepeatMode() },
                                onOpenEqualizer = { viewModel.setShowEqualizerDialog(true) },
                                onMore = { viewModel.showSongMenu(track) },
                                onAddToPlaylist = { viewModel.showPlaylistPicker(track) },
                                onArtistClick = track.artists.firstOrNull { it.id != null }?.let { artist ->
                                    {
                                        viewModel.setPlayerExpanded(false)
                                        viewModel.openArtist(artist.id!!, artist.name)
                                    }
                                },
                                onToggleDownload = {
                                    if (track.id in downloadedIds) viewModel.removeDownload(track) else viewModel.download(track)
                                },
                                onSetSleepTimer = { viewModel.setSleepTimer(it) },
                                onSleepAtEndOfTrack = { viewModel.setSleepAtEndOfTrack() },
                                onQueueItemClick = { viewModel.skipToQueueIndex(it) },
                                onQueueItemRemove = { viewModel.removeFromQueue(it) },
                                onQueueItemMove = { from, to -> viewModel.moveInQueue(from, to) },
                                onLoadLyrics = { viewModel.loadLyrics() },
                                related = related,
                                onLoadRelated = { viewModel.loadRelated() },
                                onRelatedSongClick = { song, context -> viewModel.play(song, context, radio = true) },
                                onRelatedCollectionClick = {
                                    viewModel.setPlayerExpanded(false)
                                    viewModel.openCollection(it)
                                },
                                onSaveQueue = { viewModel.saveQueueAsPlaylist(it) },
                                onClearQueue = { viewModel.clearQueue() },
                                onRelatedSongMore = { viewModel.showSongMenu(it) },
                                playbackSpeed = playbackSpeed,
                                onCycleSpeed = { viewModel.cyclePlaybackSpeed() },
                                lyricsTranslation = lyricsTranslation,
                                onToggleTranslation = { viewModel.toggleLyricsTranslation() },
                                seekPreview = seekPreview,
                                landscape = false,
                                lyricsTools = com.qmusic.ui.components.LyricsTools(
                                    offsetMs = lyricsOffset,
                                    onShift = { viewModel.shiftLyrics(it) },
                                    matches = lyricsMatches,
                                    onSearch = { viewModel.searchLyrics(it) },
                                    onUse = { viewModel.useLyrics(it) },
                                    onCloseSearch = { viewModel.closeLyricsSearch() },
                                ),
                                contentAlpha = { ((sheetProgress.value - 0.55f) / 0.45f).coerceIn(0f, 1f) },
                                coverAlpha = { if (coverSettled()) 1f else 0f },
                                backdropAlpha = { (sheetProgress.value * 4f).coerceIn(0f, 1f) },
                                onCoverPositioned = { rect ->
                                    // Measured inside the sliding sheet, so undo the slide to get the settled position.
                                    fullCover = rect.translate(0f, -(1f - sheetProgress.value) * sheetOriginPx())
                                },
                            )
                        }
                    }

                    MorphingCover(
                        model = currentTrack?.let { it.thumbnail ?: it.thumbnails.lastOrNull()?.url },
                        from = { miniCover },
                        to = { fullCover },
                        progress = { sheetProgress.value },
                    )
                }
            }

            // The "more" sheet of any song
            menuSong?.let { song ->
                SongActionsSheet(
                    song = song,
                    isFavorite = song.id in favoriteIds,
                    isDownloaded = song.id in downloadedIds,
                    onDismiss = { viewModel.dismissSongMenu() },
                    onPlayNext = {
                        viewModel.playNext(song)
                        viewModel.dismissSongMenu()
                    },
                    onAddToQueue = {
                        viewModel.addToQueue(song)
                        viewModel.dismissSongMenu()
                    },
                    onAddToPlaylist = { viewModel.showPlaylistPicker(song) },
                    onStartRadio = {
                        viewModel.startRadio(song)
                        viewModel.dismissSongMenu()
                    },
                    onRemoveFromPlaylist = (topRoute as? Route.Collection)?.takeIf {
                        collectionPage?.editable == true && collectionPage?.setVideoIds?.containsKey(song.id) == true
                    }?.let { route ->
                        {
                            viewModel.removeFromYouTubePlaylist(route.item, song)
                            viewModel.dismissSongMenu()
                        }
                    },
                    onShare = {
                        share("${song.title} - ${song.artists.joinToString { it.name }}\n${song.shareLink}")
                        viewModel.dismissSongMenu()
                    },
                    onMakeRingtone = if (viewModel.ringtones.available) {
                        {
                            viewModel.setPlayerExpanded(false)
                            viewModel.openRingtoneEditor(song)
                        }
                    } else {
                        null
                    },
                    onToggleFavorite = {
                        viewModel.toggleFavorite(song)
                        viewModel.dismissSongMenu()
                    },
                    onToggleDownload = {
                        if (song.id in downloadedIds) viewModel.removeDownload(song) else viewModel.download(song)
                        viewModel.dismissSongMenu()
                    },
                    onGoToArtist = song.artists.firstOrNull { it.id != null }?.let { artist ->
                        {
                            viewModel.dismissSongMenu()
                            viewModel.setPlayerExpanded(false)
                            viewModel.openArtist(artist.id!!, artist.name)
                        }
                    },
                )
            }

            playlistPickerSong?.let { song ->
                AddToPlaylistDialog(
                    song = song,
                    playlists = playlists,
                    onPick = {
                        viewModel.addSongToPlaylist(it.id, song)
                        viewModel.dismissPlaylistPicker()
                    },
                    onCreate = {
                        viewModel.createPlaylistAndAdd(it, song)
                        viewModel.dismissPlaylistPicker()
                    },
                    onDismiss = { viewModel.dismissPlaylistPicker() },
                    ytPlaylists = ytPlaylists,
                    canSaveToYouTube = isLoggedInYouTube,
                    onCreateOnYouTube = {
                        viewModel.createPlaylistAndAdd(it, song, onYouTube = true)
                        viewModel.dismissPlaylistPicker()
                    },
                    onPickYouTube = {
                        viewModel.addSongToYouTubePlaylist(it, song)
                        viewModel.dismissPlaylistPicker()
                    },
                )
            }

            // 10-Band Equalizer Dialog
            if (showEqualizerDialog) {
                EqualizerDialog(
                    equalizerBands = equalizerBands,
                    selectedPreset = selectedPreset,
                    onBandGainChange = { band, gain -> viewModel.setEqualizerBand(band, gain) },
                    onPresetSelect = { viewModel.applyEqualizerPreset(it) },
                    onDismissRequest = { viewModel.setShowEqualizerDialog(false) },
                )
            }

            // Spotify Import Dialog
            // A new version: offered when "update automatically" is off, and a progress box while it downloads.
            val newVersion = updateInfo
            if (offerUpdate && newVersion != null && updateProgress == null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdateOffer() },
                    title = { Text(strings.updateDialogTitle(newVersion.versionName)) },
                    text = { Text(strings.updateDialogBody) },
                    confirmButton = { TextButton(onClick = { viewModel.installUpdate() }) { Text(strings.updateNow) } },
                    dismissButton = { TextButton(onClick = { viewModel.dismissUpdateOffer() }) { Text(strings.updateLater) } },
                )
            }
            updateProgress?.let { progress ->
                UpdatingDialog(progress = progress, info = updateInfo, english = language == "en")
            }

            // The first opening after an update: what changed, once.
            val whatsNew by viewModel.whatsNew.collectAsState()
            whatsNew?.let { news ->
                WhatsNewDialog(news, english = language == "en", onDismiss = { viewModel.dismissWhatsNew() })
            }

            if (showSpotifyImportDialog) {
                SpotifyImportDialog(
                    isImporting = isImportingSpotify,
                    progress = spotifyImportProgress,
                    statusText = spotifyImportStatus,
                    onStartImport = { url -> viewModel.importSpotifyPlaylist(url) },
                    onDismissRequest = { viewModel.setShowSpotifyImportDialog(false) },
                )
            }

            if (showSpotifyLoginDialog) {
                com.qmusic.ui.auth.SpotifyLoginView(
                    onSignedIn = { viewModel.onSpotifySignedIn() },
                    onDismiss = { viewModel.setShowSpotifyLoginDialog(false) },
                )
            }

            // YouTube Cookie Capture Login View
            if (showYouTubeLoginDialog) {
                YouTubeLoginView(
                    onCookiesCaptured = { cookies -> viewModel.onYouTubeCookiesCaptured(cookies) },
                    onDismiss = { viewModel.setShowYouTubeLoginDialog(false) },
                )
            }
        }
        }
        // No opening animation: the app opens straight away (Android shows its own short splash with the icon).
    }
}

/** "Updated to X": the release notes of the version just installed, as Markdown, shown once after an update. */
@Composable
private fun WhatsNewDialog(news: com.qmusic.ui.viewmodel.MainViewModel.WhatsNew, english: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { com.qmusic.ui.theme.QMusicLogo(size = 40.dp, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (english) "Updated to Qeyf ${news.version}" else "Qeyf ${news.version} sürümüne güncellendi") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (english) "What's new" else "Neler değişti",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val notes = cleanReleaseNotes(news.notes)
                if (notes.isNotBlank()) {
                    com.mikepenz.markdown.m3.Markdown(content = notes)
                } else {
                    Text(if (english) "Fixes and improvements." else "Düzeltmeler ve iyileştirmeler.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (english) "Got it" else "Tamam") } },
    )
}

/**
 * The box shown while a new version downloads and installs: which version, how far it is, and what it brings (its
 * release notes from GitHub, without emoji).
 */
@Composable
private fun UpdatingDialog(progress: Float, info: com.qmusic.updater.model.UpdateInfo?, english: Boolean) {
    val done = progress >= 0.999f
    val notes = remember(info?.changelog) { cleanReleaseNotes(info?.changelog.orEmpty()) }
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Column {
                Text(
                    if (english) "UPDATING" else "GÜNCELLENİYOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Qeyf " + (info?.versionName ?: ""),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            done -> if (english) "Installing…" else "Kuruluyor…"
                            else -> if (english) "Downloading" else "İndiriliyor"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%" + (progress.coerceIn(0f, 1f) * 100).toInt(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (english) "In this version" else "Bu sürümde",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        com.mikepenz.markdown.m3.Markdown(content = notes)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (english) "Qeyf restarts by itself when it is done." else "Bitince Qeyf kendiliğinden yeniden açılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

/**
 * Release notes as shown in the app: no emoji (a clean, quiet look), and without the "## Qeyf 2.8.1" line the dialog
 * already says.
 */
fun cleanReleaseNotes(markdown: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < markdown.length) {
        val c = markdown[i]
        val cp: Int
        val size: Int
        if (c.isHighSurrogate() && i + 1 < markdown.length && markdown[i + 1].isLowSurrogate()) {
            cp = ((c.code - 0xD800) shl 10) + (markdown[i + 1].code - 0xDC00) + 0x10000
            size = 2
        } else {
            cp = c.code
            size = 1
        }
        val emoji = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp == 0x200D || cp in 0x2300..0x23FF
        if (!emoji) out.append(markdown, i, i + size)
        i += size
    }
    val versionLine = Regex("""^#+\s*Qeyf\s+[0-9.]+\s*$""", RegexOption.IGNORE_CASE)
    return out.lines()
        // Where an emoji stood: "**🔔 Title**" became "** Title**", "- 🎵 x" became "-  x"; the space goes.
        .map { line ->
            line.replace(Regex("""^(\s*)\*\*\s+"""), "$1**")
                .replace(Regex("""^(\s*(?:#+|[-*]))\s{2,}"""), "$1 ")
                .replace(Regex(""" {2,}"""), " ")
                .trimEnd()
        }
        .filterNot { line -> versionLine.matches(line.trim()) }
        .joinToString("\n")
        .trim()
}

/** The "Sana Özel" tab (song samples to swipe through); hidden for now, true brings it back. */
const val FOR_YOU_SHOWN = false
