package com.qmusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import com.qmusic.ui.strings.TurkishStrings
import com.qmusic.ui.auth.YouTubeLoginView
import com.qmusic.ui.components.AddToPlaylistDialog
import com.qmusic.ui.components.EqualizerDialog
import com.qmusic.ui.components.MorphingCover
import com.qmusic.ui.components.upscaleThumbnail
import com.qmusic.ui.components.MiniPlayerBar
import com.qmusic.ui.components.NavBarItem
import com.qmusic.ui.components.QMusicNavigationBar
import com.qmusic.ui.components.NowPlayingScreen
import com.qmusic.ui.components.QMusicSplash
import com.qmusic.ui.components.SongActionsSheet
import com.qmusic.ui.components.SpotifyImportDialog
import com.qmusic.ui.screens.ArtistScreen
import com.qmusic.ui.screens.CollectionScreen
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
    QMusicTheme {
        var showSplash by remember { mutableStateOf(true) }
        if (showSplash) {
            QMusicSplash(onFinished = { showSplash = false }, modifier = modifier)
            return@QMusicTheme
        }

        val language by viewModel.language.collectAsState()
        val strings = if (language == "en") EnglishStrings else TurkishStrings

        CompositionLocalProvider(LocalStrings provides strings) {
            val navTabs = remember(strings) {
                listOf(
                    NavigationTabItem(ScreenTab.HOME, strings.tabHome, QMusicIcons.Home),
                    NavigationTabItem(ScreenTab.SEARCH, strings.tabSearch, QMusicIcons.Search),
                    NavigationTabItem(ScreenTab.LIBRARY, strings.tabLibrary, QMusicIcons.Library),
                    NavigationTabItem(ScreenTab.SETTINGS, strings.tabSettings, QMusicIcons.Settings),
                )
            }

            val currentTab by viewModel.currentTab.collectAsState()
        val currentTrack by viewModel.currentTrack.collectAsState()
        val isPlaying by viewModel.isPlaying.collectAsState()
        // Only the players read this: it ticks every 250 ms and must not recompose the whole app.
        val playbackPositionState = viewModel.playbackPosition.collectAsState()
        val bufferedPositionState = viewModel.bufferedPosition.collectAsState()
        val share = rememberShare()
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
        val isCurrentTrackFavorite = currentTrack?.let { viewModel.isFavorite(it.id) } ?: false

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

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = DarkBackground,
                topBar = {
                    // Pages bring their own header (and sit under the status bar), so the logo bar steps aside.
                    if (topRoute == null) {
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
                            HorizontalDivider(color = BorderSubtle)
                        }
                    }
                },
                bottomBar = {
                    Column(
                        modifier = Modifier.graphicsLayer { translationY = sheetProgress.value * size.height },
                    ) {
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
                        if (currentTrack != null) {
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
                            items = navTabs.map { NavBarItem(it.title, it.icon) },
                            selectedIndex = navTabs.indexOfFirst { it.tab == currentTab },
                            onSelect = { index ->
                                // Tapping a tab leaves any open page, as in every music app.
                                viewModel.clearRoutes()
                                viewModel.selectTab(navTabs[index].tab)
                            },
                        )
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    when (currentTab) {
                        ScreenTab.HOME -> HomeScreen(
                            sections = homeSections,
                            isLoading = isLoadingHome,
                            error = homeError,
                            isLoggedIn = isLoggedInYouTube,
                            onSongClick = { song, context -> viewModel.play(song, context) },
                            onCollectionClick = { viewModel.openCollection(it) },
                            onSearchClick = { viewModel.selectTab(ScreenTab.SEARCH) },
                            onSignIn = { viewModel.setShowYouTubeLoginDialog(true) },
                            onRefresh = { viewModel.loadHomeContent() },
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
                            onFilterChange = { viewModel.setSearchFilter(it) },
                            onSearch = { viewModel.performSearch() },
                            onSuggestionClick = { viewModel.selectSuggestion(it) },
                            onHistoryClick = { viewModel.performSearch(it) },
                            onHistoryDelete = { viewModel.deleteSearchHistoryEntry(it) },
                            onClearHistory = { viewModel.clearSearchHistory() },
                            onSongClick = { song, context -> viewModel.play(song, context) },
                            onSongMore = { viewModel.showSongMenu(it) },
                            onCollectionClick = { viewModel.openCollection(it) },
                            onArtistClick = { viewModel.openArtist(it.browseId, it.name) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            isFavoritePredicate = { viewModel.isFavorite(it) },
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
                            isFavoritePredicate = { viewModel.isFavorite(it) },
                            onCreatePlaylist = { viewModel.createPlaylist(it) },
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
                            crashReport = crashReport,
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
                            onAutoCheckUpdatesChange = { viewModel.setAutoCheckUpdates(it) },
                            updateProgress = updateProgress,
                            onInstallUpdate = { viewModel.installUpdate() },
                            onOpenSpotifyImport = { viewModel.setShowSpotifyImportDialog(true) },
                            language = language,
                            onLanguageChange = { viewModel.setLanguage(it) },
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
                            onSongClick = { song, context -> viewModel.play(song, context) },
                            onPlayAll = { viewModel.playAll(it) },
                            onShuffle = { viewModel.shuffleAll(it) },
                            onSongMore = { viewModel.showSongMenu(it) },
                            downloadedIds = downloadedIds,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            isFavoritePredicate = { viewModel.isFavorite(it) },
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
                            isFavoritePredicate = { viewModel.isFavorite(it) },
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
                                isFavoritePredicate = { viewModel.isFavorite(it) },
                            )
                        }

                        null -> Unit
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
                    model = currentTrack?.let { it.thumbnail ?: it.thumbnails.lastOrNull()?.url }?.upscaleThumbnail(800),
                    from = { miniCover },
                    to = { fullCover },
                    progress = { sheetProgress.value },
                )
            }

            // The "more" sheet of any song
            menuSong?.let { song ->
                SongActionsSheet(
                    song = song,
                    isFavorite = viewModel.isFavorite(song.id),
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
                    onShare = {
                        share("${song.title} - ${song.artists.joinToString { it.name }}\nhttps://music.youtube.com/watch?v=${song.id}")
                        viewModel.dismissSongMenu()
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
            if (showSpotifyImportDialog) {
                SpotifyImportDialog(
                    isImporting = isImportingSpotify,
                    progress = spotifyImportProgress,
                    statusText = spotifyImportStatus,
                    onStartImport = { url -> viewModel.importSpotifyPlaylist(url) },
                    onDismissRequest = { viewModel.setShowSpotifyImportDialog(false) },
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
    }
}
