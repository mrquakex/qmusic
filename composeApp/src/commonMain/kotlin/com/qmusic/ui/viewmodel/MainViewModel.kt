package com.qmusic.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.database.repository.MusicRepository
import com.qmusic.engine.YouTube
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.ArtistPage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.CollectionPage
import com.qmusic.engine.models.HomeChip
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.Lyrics
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.SearchSuggestion
import com.qmusic.engine.models.SongItem
import com.qmusic.player.DownloadController
import com.qmusic.player.NoOpDownloadController
import com.qmusic.player.PlayerController
import com.qmusic.player.RepeatMode
import com.qmusic.settings.AppSettings
import com.qmusic.settings.CrashReports
import com.qmusic.settings.NoCrashReports
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.spotify.SpotifyImporter
import com.qmusic.updater.GithubReleaseUpdateChecker
import com.qmusic.updater.NoOpUpdateInstaller
import com.qmusic.updater.UpdateChecker
import com.qmusic.updater.UpdateInstaller
import com.qmusic.updater.model.UpdateInfo
import com.qmusic.ui.strings.AppStrings
import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.TurkishStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class ScreenTab {
    HOME,
    SEARCH,
    LIBRARY,
    SETTINGS,
}

/** A full-screen page stacked above the tabs; the last one is on screen and Back pops it. */
sealed interface Route {
    data class Collection(val item: CollectionItem) : Route
    data class Artist(val browseId: String, val name: String) : Route
    data class LocalPlaylist(val id: Long, val name: String) : Route
}

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Available(val lyrics: Lyrics) : LyricsState
    data object Unavailable : LyricsState
}

enum class EqualizerPreset(val displayName: String, val gains: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Bass Boost", listOf(5f, 4f, 3f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
    ROCK("Rock", listOf(4f, 3f, -1f, -1.5f, 0f, 1f, 2.5f, 3.5f, 4f, 4f)),
    VOCAL("Vocal", listOf(-2f, -1f, 0f, 2f, 3.5f, 3.5f, 2f, 1f, 0f, -1f)),
}

/**
 * Main application ViewModel orchestrating player state, YouTube search, database caching,
 * Spotify migration, authentication, and app updates.
 */
class MainViewModel(
    val youTube: YouTube = YouTube(),
    val playerController: PlayerController,
    val repository: MusicRepository,
    val spotifyImporter: SpotifyImporter = SpotifyImporter.create(),
    val youTubeAuth: YouTubeAuth = YouTubeAuth(),
    val updateChecker: UpdateChecker = GithubReleaseUpdateChecker(),
    val updateInstaller: UpdateInstaller = NoOpUpdateInstaller(),
    externalScope: CoroutineScope? = null,
    autoLoadHome: Boolean = true,
    val downloads: DownloadController = NoOpDownloadController(),
    val settings: AppSettings = InMemoryAppSettings(),
    val crashReports: CrashReports = NoCrashReports(),
    /** Whether the connection is unmetered (Wi-Fi); automatic downloads wait for it when "Wi-Fi only" is on. */
    private val isUnmetered: () -> Boolean = { true },
    /** Keeps the queue topped up with YouTube Music's radio as the end approaches (off in unit tests: it uses the network). */
    private val autoRefillQueue: Boolean = true,
    /** Called with the raw cookie string after sign-in, or null after sign-out, so the host can persist the session. */
    private val onSessionChanged: (String?) -> Unit = {},
    private val stringsProvider: (() -> AppStrings)? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val strings: AppStrings get() = stringsProvider?.invoke() ?: if (settings.language.value == "en") EnglishStrings else TurkishStrings

    val language: StateFlow<String> = settings.language

    fun setLanguage(code: String) {
        settings.setLanguage(code)
        youTube.setLanguage(code)
        loadHomeContent()
    }

    // Navigation and Modal UI States
    private val _currentTab = MutableStateFlow(ScreenTab.HOME)
    val currentTab: StateFlow<ScreenTab> = _currentTab.asStateFlow()

    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    private val _showEqualizerDialog = MutableStateFlow(false)
    val showEqualizerDialog: StateFlow<Boolean> = _showEqualizerDialog.asStateFlow()

    private val _showSpotifyImportDialog = MutableStateFlow(false)
    val showSpotifyImportDialog: StateFlow<Boolean> = _showSpotifyImportDialog.asStateFlow()

    private val _showYouTubeLoginDialog = MutableStateFlow(false)
    val showYouTubeLoginDialog: StateFlow<Boolean> = _showYouTubeLoginDialog.asStateFlow()

    // Player State forwarded from PlayerController
    val currentTrack: StateFlow<SongItem?> = playerController.currentTrack
    val isPlaying: StateFlow<Boolean> = playerController.isPlaying
    val playbackPosition: StateFlow<Long> = playerController.playbackPosition
    val bufferedPosition: StateFlow<Long> = playerController.bufferedPosition
    val duration: StateFlow<Long> = playerController.duration
    val queue: StateFlow<List<SongItem>> = playerController.queue
    val queueIndex: StateFlow<Int> = playerController.queueIndex
    val playbackError: StateFlow<String?> = playerController.playbackError
    val shuffleEnabled: StateFlow<Boolean> = playerController.shuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = playerController.repeatMode
    val sleepTimer = playerController.sleepTimer
    val videoMode: StateFlow<Boolean> = playerController.videoMode

    /** Whether the Song/Video switch is on "Video"; the clip only plays while the full player is open. */
    private val _videoPreferred = MutableStateFlow(settings.videoByDefault.value)
    val videoPreferred: StateFlow<Boolean> = _videoPreferred.asStateFlow()

    // Crossfade Duration (0-12s, default 3s)
    private val _crossfadeDuration = MutableStateFlow(3)
    val crossfadeDuration: StateFlow<Int> = _crossfadeDuration.asStateFlow()

    // 10-Band Equalizer (-12 to +12 dB)
    private val _equalizerBands = MutableStateFlow(List(10) { 0f })
    val equalizerBands: StateFlow<List<Float>> = _equalizerBands.asStateFlow()

    private val _selectedPreset = MutableStateFlow(EqualizerPreset.FLAT)
    val selectedPreset: StateFlow<EqualizerPreset> = _selectedPreset.asStateFlow()

    // Database Collections
    private val localFavorites: StateFlow<List<SongItem>> = repository.getFavorites()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Liked music" of the signed-in YouTube account; empty while signed out. */
    private val _likedSongs = MutableStateFlow<List<SongItem>>(emptyList())
    val likedSongs: StateFlow<List<SongItem>> = _likedSongs.asStateFlow()

    private val _isSyncingLikes = MutableStateFlow(false)
    val isSyncingLikes: StateFlow<Boolean> = _isSyncingLikes.asStateFlow()

    /** Favorites shown in the library: the ones saved on this device plus the account's liked songs. */
    val favorites: StateFlow<List<SongItem>> = combine(localFavorites, _likedSongs) { local, liked ->
        val localIds = local.mapTo(HashSet()) { it.id }
        local + liked.filter { it.id !in localIds }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The "Speed dial" grid at the top of the home page: what was played last, then the liked songs. (YouTube
     * Music builds this on its servers; here it comes from this device's history and likes.)
     */
    val speedDial: StateFlow<List<SongItem>> by lazy {
        combine(history, favorites) { recent, liked ->
            (recent + liked).distinctBy { it.id }.take(SPEED_DIAL_SIZE)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** The dice tile: plays a song picked at random from what the person listens to. */
    fun playRandomSong() {
        val pool = (history.value + favorites.value).distinctBy { it.id }
            .ifEmpty { _homeSections.value.flatMap { it.songs }.distinctBy { it.id } }
        pool.randomOrNull()?.let { playerController.play(it) }
    }

    val history: StateFlow<List<SongItem>> = repository.getHistory()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getPlaylists()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search Screen State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow<SearchFilter?>(null)
    val selectedFilter: StateFlow<SearchFilter?> = _selectedFilter.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SongItem>>(emptyList())
    val searchResults: StateFlow<List<SongItem>> = _searchResults.asStateFlow()

    /** Search grouped into songs / albums / artists / playlists; null before the first search. */
    private val _searchPage = MutableStateFlow<SearchResultsPage?>(null)
    val searchPage: StateFlow<SearchResultsPage?> = _searchPage.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /** True once a search was submitted and the text has not been edited since; false while typing. */
    private val _showSearchResults = MutableStateFlow(false)
    val showSearchResults: StateFlow<Boolean> = _showSearchResults.asStateFlow()

    val searchHistory: StateFlow<List<String>> = repository.getSearchHistory()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _remoteSuggestions = MutableStateFlow<List<SearchSuggestion>>(emptyList())

    /** Autocomplete rows: matching past searches first, then YouTube Music's own suggestions. */
    val searchSuggestions: StateFlow<List<SearchSuggestion>> =
        combine(_searchQuery, searchHistory, _remoteSuggestions) { query, history, remote ->
            val needle = query.trim()
            if (needle.isEmpty()) {
                emptyList()
            } else {
                val local = history
                    .filter { it.contains(needle, ignoreCase = true) && !it.equals(needle, ignoreCase = true) }
                    .take(3)
                    .map { SearchSuggestion(it, isHistory = true) }
                (local + remote).distinctBy { it.text.lowercase() }.take(10)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var suggestionJob: Job? = null
    private var lastSubmittedQuery: String? = null

    // Home Screen State
    private val _homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    private val _homeChips = MutableStateFlow<List<HomeChip>>(emptyList())
    val homeChips: StateFlow<List<HomeChip>> = _homeChips.asStateFlow()

    private val _selectedHomeChip = MutableStateFlow<String?>(null)
    val selectedHomeChip: StateFlow<String?> = _selectedHomeChip.asStateFlow()

    private val _isLoadingMoreHome = MutableStateFlow(false)
    val isLoadingMoreHome: StateFlow<Boolean> = _isLoadingMoreHome.asStateFlow()

    private val _homeScrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homeScrollToTop: SharedFlow<Unit> = _homeScrollToTop.asSharedFlow()

    private var lastHomeTap: TimeSource.Monotonic.ValueTimeMark? = null

    private val _isLoadingHome = MutableStateFlow(false)
    val isLoadingHome: StateFlow<Boolean> = _isLoadingHome.asStateFlow()

    private val _homeError = MutableStateFlow<String?>(null)
    val homeError: StateFlow<String?> = _homeError.asStateFlow()

    private var homeJob: Job? = null

    // Full-screen pages (playlist, album, artist, local playlist) stacked above the tabs
    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _collectionPage = MutableStateFlow<CollectionPage?>(null)
    val collectionPage: StateFlow<CollectionPage?> = _collectionPage.asStateFlow()

    private val _isLoadingCollection = MutableStateFlow(false)
    val isLoadingCollection: StateFlow<Boolean> = _isLoadingCollection.asStateFlow()

    private val _collectionError = MutableStateFlow<String?>(null)
    val collectionError: StateFlow<String?> = _collectionError.asStateFlow()

    private val _artistPage = MutableStateFlow<ArtistPage?>(null)
    val artistPage: StateFlow<ArtistPage?> = _artistPage.asStateFlow()

    private val _isLoadingArtist = MutableStateFlow(false)
    val isLoadingArtist: StateFlow<Boolean> = _isLoadingArtist.asStateFlow()

    private val _artistError = MutableStateFlow<String?>(null)
    val artistError: StateFlow<String?> = _artistError.asStateFlow()

    private val collectionCache = HashMap<String, CollectionPage>()
    private val artistCache = HashMap<String, ArtistPage>()

    // Song actions: the "more" sheet and the add-to-playlist picker
    private val _menuSong = MutableStateFlow<SongItem?>(null)
    val menuSong: StateFlow<SongItem?> = _menuSong.asStateFlow()

    private val _playlistPickerSong = MutableStateFlow<SongItem?>(null)
    val playlistPickerSong: StateFlow<SongItem?> = _playlistPickerSong.asStateFlow()

    // Downloads: the cache is the truth for "playable offline", the database remembers the song details
    val downloadedIds: StateFlow<Set<String>> = downloads.downloadedIds
    val downloadProgress: StateFlow<Map<String, Float>> = downloads.progress
    val downloadedSongs: StateFlow<List<SongItem>> =
        combine(repository.getDownloadedSongs(), downloads.downloadedIds) { saved, ready ->
            saved.filter { it.id in ready }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val autoDownloadLiked: StateFlow<Boolean> = settings.autoDownloadLiked
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
    val videoByDefault: StateFlow<Boolean> = settings.videoByDefault
    val autoCheckUpdates: StateFlow<Boolean> = settings.autoCheckUpdates
    val updateNotifications: StateFlow<Boolean> = settings.updateNotifications
    val autoUpdate: StateFlow<Boolean> = settings.autoUpdate

    /** Set when the launch check finds a version and "update automatically" is off: the dialog that offers it. */
    private val _offerUpdate = MutableStateFlow(false)
    val offerUpdate: StateFlow<Boolean> = _offerUpdate.asStateFlow()

    fun dismissUpdateOffer() {
        _offerUpdate.value = false
    }

    fun setAutoUpdate(enabled: Boolean) {
        settings.setAutoUpdate(enabled)
    }
    val downloadedBytes: StateFlow<Long> = downloads.downloadedBytes

    /** Songs already handed to the downloader by "auto-download", so a failure is not retried in a loop. */
    private val autoQueued = HashSet<String>()

    // Lyrics of the song that is playing
    private val _lyricsState = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()
    private val lyricsCache = HashMap<String, Lyrics?>()
    private var lyricsJob: Job? = null
    private var radioFilledFor: String? = null

    // Spotify Import State
    private val _isImportingSpotify = MutableStateFlow(false)
    val isImportingSpotify: StateFlow<Boolean> = _isImportingSpotify.asStateFlow()

    private val _spotifyImportProgress = MutableStateFlow(0f)
    val spotifyImportProgress: StateFlow<Float> = _spotifyImportProgress.asStateFlow()

    private val _spotifyImportStatus = MutableStateFlow("")
    val spotifyImportStatus: StateFlow<String> = _spotifyImportStatus.asStateFlow()

    // YouTube Auth State
    private val _isLoggedIn = MutableStateFlow(youTubeAuth.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Update Checker State
    // Keep equal to versionName in androidApp/build.gradle.kts, or the update check compares against the wrong number.
    val currentAppVersion: String = "1.0.8"
    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    init {
        youTube.setAuth(youTubeAuth)
        youTube.setLanguage(settings.language.value)
        if (autoLoadHome) {
            loadHomeContent()
            if (youTubeAuth.isLoggedIn) loadLikedSongs()
            if (settings.autoCheckUpdates.value) checkForUpdates(silent = true)
        }
        // Auto-download: every liked song (device favorites + YouTube Music likes) that is not saved yet
        scope.launch {
            combine(settings.autoDownloadLiked, settings.wifiOnly, favorites, downloads.downloadedIds) { auto, wifi, liked, saved ->
                if (auto && (!wifi || isUnmetered())) liked.filter { it.id !in saved } else emptyList()
            }.collect { missing ->
                missing.filter { autoQueued.add(it.id) }.forEach(::download)
            }
        }
        if (autoRefillQueue) {
            scope.launch {
                combine(playerController.queue, playerController.queueIndex, playerController.currentTrack) { queue, index, track ->
                    Triple(queue.size - index - 1, index, track)
                }.collect { (upcoming, index, track) ->
                    if (track == null || index < 0 || upcoming > RADIO_REFILL_THRESHOLD) return@collect
                    if (radioFilledFor == track.id || repeatMode.value == RepeatMode.ONE) return@collect
                    radioFilledFor = track.id
                    youTube.radio(track.id).onSuccess { more ->
                        if (playerController.currentTrack.value?.id == track.id) playerController.appendToQueue(more)
                    }
                }
            }
        }
        // History follows what actually plays, so songs reached through next/auto-advance are recorded too.
        scope.launch {
            playerController.currentTrack
                .filterNotNull()
                .distinctUntilChangedBy { it.id }
                .collect { repository.recordHistory(it) }
        }
    }

    // --- Navigation & Sheet Controls ---
    fun selectTab(tab: ScreenTab) {
        _currentTab.value = tab
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
        // A clip is only fetched and drawn while the full player is open (saves data and battery otherwise).
        playerController.setVideoMode(expanded && _videoPreferred.value)
    }

    /** The Settings switch: what the player opens on from now on (and right now, if it is open). */
    fun setVideoByDefault(enabled: Boolean) {
        settings.setVideoByDefault(enabled)
        setVideoPreferred(enabled)
    }

    fun setUpdateNotifications(enabled: Boolean) {
        settings.setUpdateNotifications(enabled)
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        settings.setAutoCheckUpdates(enabled)
    }

    fun setVideoPreferred(preferred: Boolean) {
        _videoPreferred.value = preferred
        if (_isPlayerExpanded.value) playerController.setVideoMode(preferred)
    }

    fun setShowEqualizerDialog(show: Boolean) {
        _showEqualizerDialog.value = show
    }

    fun setShowSpotifyImportDialog(show: Boolean) {
        _showSpotifyImportDialog.value = show
    }

    fun setShowYouTubeLoginDialog(show: Boolean) {
        _showYouTubeLoginDialog.value = show
    }

    // --- Playback Controls ---
    /**
     * Plays [song]. With a [context] list (the shelf, search results or playlist it was tapped in) the whole
     * list becomes the queue; alone, YouTube Music's radio for the song fills the queue instead.
     */
    fun play(song: SongItem, context: List<SongItem> = emptyList()) {
        val index = context.indexOfFirst { it.id == song.id }
        if (index >= 0 && context.size > 1) {
            playerController.playQueue(context, index)
        } else {
            // Alone, the song is a one-item queue; the refill below tops it up with YouTube Music's radio.
            playerController.play(song)
        }
    }

    /** Plays a list in random order, starting from a random song. */
    fun shuffleAll(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (!shuffleEnabled.value) playerController.setShuffle(true)
        playerController.playQueue(songs, kotlin.random.Random.nextInt(songs.size))
    }

    /** Plays a whole list from its first song (playlist / album "Play"). */
    fun playAll(songs: List<SongItem>) {
        if (songs.isNotEmpty()) playerController.playQueue(songs, 0)
    }

    fun next() {
        playerController.next()
    }

    fun previous() {
        playerController.previous()
    }

    fun playNext(song: SongItem) {
        if (currentTrack.value == null) playerController.play(song) else playerController.playNext(song)
    }

    fun addToQueue(song: SongItem) {
        if (currentTrack.value == null) playerController.play(song) else playerController.appendToQueue(listOf(song))
    }

    fun skipToQueueIndex(index: Int) {
        playerController.skipTo(index)
    }

    fun removeFromQueue(index: Int) {
        playerController.removeFromQueue(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        playerController.moveInQueue(from, to)
    }

    /** Plays [song] alone; the queue then fills itself with songs like it (YouTube Music's radio). */
    fun startRadio(song: SongItem) {
        playerController.play(song)
    }

    val crashReport: StateFlow<String?> = crashReports.lastReport

    fun clearCrashReport() {
        crashReports.clear()
    }

    fun toggleShuffle() {
        playerController.setShuffle(!shuffleEnabled.value)
    }

    fun cycleRepeatMode() {
        playerController.setRepeatMode(
            when (repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    fun setSleepTimer(minutes: Int) {
        playerController.setSleepTimer(minutes)
    }

    fun setSleepAtEndOfTrack() {
        playerController.setSleepAtEndOfTrack()
    }

    fun pause() {
        playerController.pause()
    }

    fun resume() {
        playerController.resume()
    }

    fun togglePlayPause() {
        if (isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        playerController.seekTo(positionMs)
    }

    fun setCrossfadeDuration(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        _crossfadeDuration.value = clamped
        playerController.setCrossfadeDuration(clamped)
    }

    fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            val clamped = gainDb.coerceIn(-12f, 12f)
            val updated = _equalizerBands.value.toMutableList()
            updated[bandIndex] = clamped
            _equalizerBands.value = updated
            playerController.setEqualizerBand(bandIndex, clamped)
        }
    }

    fun applyEqualizerPreset(preset: EqualizerPreset) {
        _selectedPreset.value = preset
        _equalizerBands.value = preset.gains
        preset.gains.forEachIndexed { index, gain ->
            playerController.setEqualizerBand(index, gain)
        }
    }

    // --- Library & Favorites ---
    fun toggleFavorite(song: SongItem) {
        val like = !isFavorite(song.id)
        scope.launch {
            // The device copy follows the combined state, so a song liked only on YouTube can be un-liked too.
            val savedHere = localFavorites.value.any { it.id == song.id }
            if (savedHere != like) repository.toggleFavorite(song)

            if (youTubeAuth.isLoggedIn) {
                _likedSongs.value = if (like) listOf(song) + _likedSongs.value.filter { it.id != song.id }
                else _likedSongs.value.filter { it.id != song.id }
                youTube.setLiked(song.id, like).onFailure {
                    // Roll the optimistic change back so the heart does not lie about the account.
                    loadLikedSongs()
                }
            }
        }
    }

    fun isFavorite(songId: String): Boolean {
        return favorites.value.any { it.id == songId }
    }

    /** Fetches the account's "Liked music"; a no-op while signed out. */
    fun loadLikedSongs() {
        if (!youTubeAuth.isLoggedIn) {
            _likedSongs.value = emptyList()
            return
        }
        _isSyncingLikes.value = true
        scope.launch {
            youTube.likedSongs().onSuccess { _likedSongs.value = it }
            _isSyncingLikes.value = false
        }
    }

    fun createPlaylist(name: String, onCreated: ((Long) -> Unit)? = null) {
        scope.launch {
            val id = repository.createPlaylist(name)
            onCreated?.invoke(id)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: SongItem) {
        scope.launch {
            repository.addSongToPlaylist(playlistId, song)
        }
    }

    fun getPlaylistSongs(playlistId: Long): Flow<List<SongItem>> {
        return repository.getPlaylistSongs(playlistId)
    }

    // --- Song actions ---
    fun showSongMenu(song: SongItem) {
        _menuSong.value = song
    }

    fun dismissSongMenu() {
        _menuSong.value = null
    }

    fun showPlaylistPicker(song: SongItem) {
        _menuSong.value = null
        _playlistPickerSong.value = song
    }

    fun dismissPlaylistPicker() {
        _playlistPickerSong.value = null
    }

    fun createPlaylistAndAdd(name: String, song: SongItem) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val id = repository.createPlaylist(trimmed)
            repository.addSongToPlaylist(id, song)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: SongItem) {
        scope.launch { repository.removeSongFromPlaylist(playlistId, song.id) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch { repository.renamePlaylist(playlistId, trimmed) }
        _routes.value = _routes.value.map {
            if (it is Route.LocalPlaylist && it.id == playlistId) it.copy(name = trimmed) else it
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch { repository.deletePlaylist(playlistId) }
        _routes.value = _routes.value.filterNot { it is Route.LocalPlaylist && it.id == playlistId }
    }

    fun download(song: SongItem) {
        scope.launch { repository.setDownloaded(song, true) }
        downloads.download(song)
    }

    /** Queues every song of [songs] that is not on the device yet. */
    fun downloadAll(songs: List<SongItem>) {
        songs.filter { it.id !in downloads.downloadedIds.value }.forEach(::download)
    }

    fun cancelDownloads() {
        downloads.cancelAll()
    }

    fun removeAllDownloads() {
        val saved = downloadedSongs.value
        downloads.removeAll()
        scope.launch { saved.forEach { repository.setDownloaded(it, false) } }
    }

    fun setAutoDownloadLiked(enabled: Boolean) {
        settings.setAutoDownloadLiked(enabled)
        if (!enabled) autoQueued.clear()
    }

    fun setWifiOnly(enabled: Boolean) {
        settings.setWifiOnly(enabled)
    }

    fun removeDownload(song: SongItem) {
        downloads.remove(song.id)
        scope.launch { repository.setDownloaded(song, false) }
    }

    // --- Lyrics ---
    /** Loads the lyrics of the playing song (cached per song). Called when the lyrics page is shown. */
    fun loadLyrics() {
        val song = currentTrack.value ?: return
        lyricsJob?.cancel()
        if (lyricsCache.containsKey(song.id)) {
            _lyricsState.value = lyricsCache[song.id]?.let(LyricsState::Available) ?: LyricsState.Unavailable
            return
        }
        _lyricsState.value = LyricsState.Loading
        lyricsJob = scope.launch {
            // Lines that move with the song are preferred; YouTube Music's plain text is the fallback.
            val synced = async { youTube.syncedLyrics(song.title, song.artists.joinToString(", ") { it.name }, song.duration).getOrNull() }
            val plain = youTube.lyrics(song.id).getOrNull()
            val lyrics = synced.await() ?: plain
            lyricsCache[song.id] = lyrics
            if (currentTrack.value?.id == song.id) {
                _lyricsState.value = lyrics?.let(LyricsState::Available) ?: LyricsState.Unavailable
            }
        }
    }

    // --- Search ---
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        suggestionJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _remoteSuggestions.value = emptyList()
            _searchResults.value = emptyList()
            _searchPage.value = null
            _searchError.value = null
            _showSearchResults.value = false
            lastSubmittedQuery = null
            return
        }
        if (trimmed != lastSubmittedQuery) {
            _showSearchResults.value = false
        }
        suggestionJob = scope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            youTube.searchSuggestions(trimmed).onSuccess { suggestions ->
                if (_searchQuery.value.trim() == trimmed) _remoteSuggestions.value = suggestions
            }
        }
    }

    fun setSearchFilter(filter: SearchFilter?) {
        _selectedFilter.value = filter
    }

    fun selectSuggestion(suggestion: SearchSuggestion) {
        _searchQuery.value = suggestion.text
        performSearch(suggestion.text)
    }

    fun deleteSearchHistoryEntry(query: String) {
        scope.launch { repository.deleteSearch(query) }
    }

    fun clearSearchHistory() {
        scope.launch { repository.clearSearchHistory() }
    }

    fun performSearch(query: String = _searchQuery.value, filter: SearchFilter? = _selectedFilter.value) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            _showSearchResults.value = false
            return
        }

        suggestionJob?.cancel()
        _remoteSuggestions.value = emptyList()
        _searchQuery.value = trimmed
        lastSubmittedQuery = trimmed
        _showSearchResults.value = true
        _isSearching.value = true
        _searchError.value = null

        scope.launch {
            repository.recordSearch(trimmed)
            val result = youTube.searchPage(query = trimmed, filter = filter)
            result.onSuccess { page ->
                _searchPage.value = page
                playerController.warmUp(page.songs.take(3))
                _searchResults.value = page.songs
                _isSearching.value = false
            }.onFailure { throwable ->
                _searchError.value = throwable.message ?: strings.searchFailed
                _isSearching.value = false
            }
        }
    }

    // --- Home Screen Content ---
    /** Loads the YouTube Music home page; personalised once the user has signed in. */
    fun loadHomeContent() {
        loadHome(_selectedHomeChip.value)
    }

    /** Applies a mood chip to the home feed; tapping the chosen chip again goes back to the plain feed. */
    fun selectHomeChip(chip: HomeChip) {
        val params = if (_selectedHomeChip.value == chip.params) null else chip.params
        _selectedHomeChip.value = params
        loadHome(params)
    }

    /** What a tap on a bottom tab does: Home pressed while shown scrolls to the top, and twice in a row refreshes. */
    fun onTabClicked(tab: ScreenTab) {
        if (tab == ScreenTab.HOME && _currentTab.value == ScreenTab.HOME) {
            val last = lastHomeTap
            if (last != null && last.elapsedNow() < DOUBLE_TAP_WINDOW) {
                lastHomeTap = null
                loadHomeContent()
            } else {
                lastHomeTap = TimeSource.Monotonic.markNow()
                _homeScrollToTop.tryEmit(Unit)
            }
        } else {
            lastHomeTap = null
        }
        selectTab(tab)
    }

    private fun loadHome(params: String?) {
        homeJob?.cancel()
        _isLoadingHome.value = true
        _isLoadingMoreHome.value = false
        _homeError.value = null
        homeJob = scope.launch {
            var first = true
            youTube.home(params) { page ->
                if (first) {
                    first = false
                    _homeSections.value = page.sections
                    if (page.chips.isNotEmpty()) _homeChips.value = page.chips
                    _isLoadingHome.value = false
                    _isLoadingMoreHome.value = true
                } else {
                    _homeSections.value = _homeSections.value + page.sections
                }
            }.onSuccess {
                if (_homeSections.value.isEmpty()) _homeError.value = strings.youtubeReturnedNothing
            }.onFailure { throwable ->
                _homeError.value = throwable.message ?: strings.couldNotLoadYouTube
            }
            _isLoadingHome.value = false
            _isLoadingMoreHome.value = false
        }
    }

    // --- Pages: playlist, album, artist, local playlist ---
    fun openCollection(item: CollectionItem) {
        if (item.kind == CollectionKind.ARTIST) openArtist(item.browseId, item.title) else push(Route.Collection(item))
    }

    fun openArtist(browseId: String, name: String) = push(Route.Artist(browseId, name))

    fun openLocalPlaylist(id: Long, name: String) = push(Route.LocalPlaylist(id, name))

    /** Pops the top page; false when there was none (so the system Back can do its default). */
    fun popRoute(): Boolean {
        if (_routes.value.isEmpty()) return false
        _routes.value = _routes.value.dropLast(1)
        loadTopRoute()
        return true
    }

    fun closeCollection() {
        popRoute()
    }

    fun clearRoutes() {
        _routes.value = emptyList()
    }

    private fun push(route: Route) {
        if (_routes.value.lastOrNull() == route) return
        _routes.value = _routes.value + route
        loadTopRoute()
    }

    private fun loadTopRoute() {
        when (val route = _routes.value.lastOrNull()) {
            is Route.Collection -> loadCollection(route)
            is Route.Artist -> loadArtist(route)
            is Route.LocalPlaylist, null -> Unit
        }
    }

    private fun loadCollection(route: Route.Collection) {
        val item = route.item
        collectionCache[item.browseId]?.let {
            _collectionPage.value = it
            _collectionError.value = null
            _isLoadingCollection.value = false
            return
        }
        _collectionPage.value = null
        _collectionError.value = null
        _isLoadingCollection.value = true
        scope.launch {
            youTube.collection(item.browseId).onSuccess { page ->
                val complete = page.copy(
                    title = page.title.ifBlank { item.title },
                    thumbnail = page.thumbnail ?: item.thumbnail,
                )
                collectionCache[item.browseId] = complete
                // Only show the result if the user has not already moved on to another page.
                if (_routes.value.lastOrNull() == route) _collectionPage.value = complete
            }.onFailure { throwable ->
                if (_routes.value.lastOrNull() == route) _collectionError.value = throwable.message ?: strings.couldNotOpenPage
            }
            if (_routes.value.lastOrNull() == route) _isLoadingCollection.value = false
        }
    }

    private fun loadArtist(route: Route.Artist) {
        artistCache[route.browseId]?.let {
            _artistPage.value = it
            _artistError.value = null
            _isLoadingArtist.value = false
            return
        }
        _artistPage.value = null
        _artistError.value = null
        _isLoadingArtist.value = true
        scope.launch {
            youTube.artist(route.browseId).onSuccess { page ->
                val complete = page.copy(name = page.name.ifBlank { route.name })
                artistCache[route.browseId] = complete
                if (_routes.value.lastOrNull() == route) _artistPage.value = complete
            }.onFailure { throwable ->
                if (_routes.value.lastOrNull() == route) _artistError.value = throwable.message ?: strings.couldNotOpenArtist
            }
            if (_routes.value.lastOrNull() == route) _isLoadingArtist.value = false
        }
    }

    // --- Spotify Migration ---
    fun importSpotifyPlaylist(url: String, onFinished: ((Int) -> Unit)? = null) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return

        _isImportingSpotify.value = true
        _spotifyImportProgress.value = 0f
        _spotifyImportStatus.value = strings.spotifyImportFetching

        scope.launch {
            val playlistResult = spotifyImporter.importPlaylistFromUrl(cleanUrl)
            playlistResult.onSuccess { playlist ->
                val totalTracks = playlist.tracks.size
                if (totalTracks == 0) {
                    _spotifyImportStatus.value = strings.spotifyImportNoTracks
                    _isImportingSpotify.value = false
                    return@launch
                }

                _spotifyImportStatus.value = strings.spotifyImportCreating(playlist.title)
                val playlistId = repository.createPlaylist(playlist.title)

                var matchedCount = 0
                for ((index, track) in playlist.tracks.withIndex()) {
                    _spotifyImportStatus.value = strings.spotifyImportMatching(index + 1, totalTracks, track.title)
                    _spotifyImportProgress.value = (index + 1).toFloat() / totalTracks.toFloat()

                    val matchResult = spotifyImporter.matchTrackToYouTube(track, youTube)
                    matchResult.onSuccess { matchedSong ->
                        repository.addSongToPlaylist(playlistId, matchedSong)
                        matchedCount++
                    }
                }

                _spotifyImportStatus.value = strings.spotifyImportComplete(matchedCount, totalTracks)
                _isImportingSpotify.value = false
                onFinished?.invoke(matchedCount)
            }.onFailure { error ->
                _spotifyImportStatus.value = strings.spotifyImportFailed(error.message ?: "")
                _isImportingSpotify.value = false
            }
        }
    }

    // --- YouTube Authentication ---
    fun onYouTubeCookiesCaptured(cookies: String) {
        youTubeAuth.setCookies(cookies)
        youTube.setAuth(youTubeAuth)
        _isLoggedIn.value = youTubeAuth.isLoggedIn
        _showYouTubeLoginDialog.value = false
        if (youTubeAuth.isLoggedIn) {
            onSessionChanged(cookies)
            loadHomeContent()
            loadLikedSongs()
        }
    }

    fun logoutYouTube() {
        youTubeAuth.setCookies("")
        youTube.setAuth(youTubeAuth)
        _isLoggedIn.value = false
        onSessionChanged(null)
        _likedSongs.value = emptyList()
        loadHomeContent()
    }

    // --- Update Checker ---
    /** Download progress (0..1) of the update being fetched, null when none is. */
    val updateProgress: StateFlow<Float?> = updateInstaller.progress

    fun installUpdate() {
        val info = _updateInfo.value ?: return
        _offerUpdate.value = false
        if (updateProgress.value != null) return
        _updateMessage.value = strings.downloadingUpdate(info.versionName)
        scope.launch {
            updateInstaller.install(info)
                .onSuccess { _updateMessage.value = strings.updateAvailable(info.versionName) }
                .onFailure { _updateMessage.value = strings.couldNotInstallUpdate(it.message ?: "") }
        }
    }

    /** [silent]: the automatic check at launch only says something when there is a new version. */
    fun checkForUpdates(silent: Boolean = false) {
        _isCheckingUpdate.value = true
        _updateMessage.value = null
        _updateInfo.value = null

        scope.launch {
            val result = updateChecker.checkForUpdate(currentAppVersion)
            result.onSuccess { info ->
                _isCheckingUpdate.value = false
                if (info != null) {
                    _updateInfo.value = info
                    _updateMessage.value = strings.updateAvailable(info.versionName)
                    // Found when the app opened: install it straight away, or offer it, as the person chose.
                    if (silent) {
                        if (settings.autoUpdate.value) installUpdate() else _offerUpdate.value = true
                    }
                } else if (!silent) {
                    _updateMessage.value = strings.latestVersion
                }
            }.onFailure { error ->
                _isCheckingUpdate.value = false
                if (!silent) _updateMessage.value = strings.updateCheckFailed(error.message ?: "")
            }
        }
    }

    private companion object {
        const val RADIO_REFILL_THRESHOLD = 3
        const val SPEED_DIAL_SIZE = 17 // two pages of nine, one slot of which is the dice
        val DOUBLE_TAP_WINDOW = 500.milliseconds
        const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
