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
import com.qmusic.engine.models.ExplorePage
import com.qmusic.engine.models.HomeChip
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SectionStyle
import com.qmusic.engine.models.Lyrics
import com.qmusic.engine.models.LyricsMatch
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.SearchSuggestion
import com.qmusic.engine.models.SongItem
import com.qmusic.player.DownloadController
import com.qmusic.player.NoOpDownloadController
import com.qmusic.player.AudioQuality
import com.qmusic.player.PlayerController
import com.qmusic.player.RepeatMode
import com.qmusic.network.AlwaysOnline
import com.qmusic.network.ConnectivityMonitor
import com.qmusic.settings.AccountMode
import com.qmusic.ringtone.NoRingtones
import com.qmusic.ringtone.RingtoneMaker
import com.qmusic.ringtone.RingtoneStudio
import com.qmusic.settings.AppSettings
import com.qmusic.settings.NoSpotifySession
import com.qmusic.settings.SpotifySession
import com.qmusic.engine.models.SpotifyIds
import com.qmusic.spotify.SpotifySignedOutException
import com.qmusic.spotify.SpotifyWebApi
import com.qmusic.ui.strings.forService
import com.qmusic.settings.MoreSettings
import com.qmusic.settings.MoreSettingsChange
import com.qmusic.settings.NoScrobbling
import com.qmusic.settings.ScrobbleAccount
import com.qmusic.settings.SettingKeys
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class ScreenTab {
    HOME,
    FOR_YOU,
    SEARCH,
    LIBRARY,
    SETTINGS,
}

/** A full-screen page stacked above the tabs; the last one is on screen and Back pops it. */
sealed interface Route {
    data class Collection(val item: CollectionItem) : Route
    data class Artist(val browseId: String, val name: String) : Route
    data class LocalPlaylist(val id: Long, val name: String) : Route
    data class Explore(val browseId: String, val params: String?, val title: String) : Route
    data object Stats : Route
    data class RingtoneEditor(val song: SongItem) : Route
    data object Ringtones : Route
}

/** The backup file: what lives only on this device. */
@kotlinx.serialization.Serializable
data class Backup(
    val version: Int,
    val favorites: List<SongItem> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val history: List<SongItem> = emptyList(),
    val searches: List<String> = emptyList(),
)

/** The queue and moment the app was at, for the next launch. */
@kotlinx.serialization.Serializable
data class SavedSession(val queue: List<SongItem>, val index: Int, val positionMs: Long)

@kotlinx.serialization.Serializable
data class BackupPlaylist(val name: String, val songs: List<SongItem>)

enum class StatsPeriod(val days: Int?) { MONTH(30), YEAR(365), ALL(null) }

/** What was listened to over a period: totals, the most played songs and artists. */
data class ListeningStats(
    val period: StatsPeriod,
    val plays: Int,
    val songs: Int,
    val minutes: Int,
    val topSongs: List<Pair<SongItem, Int>>,
    val topArtists: List<Pair<String, Int>>,
)

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
    val connectivity: ConnectivityMonitor = AlwaysOnline(),
    /** Whether the connection is unmetered (Wi-Fi); automatic downloads wait for it when "Wi-Fi only" is on. */
    private val isUnmetered: () -> Boolean = { true },
    /** Keeps the queue topped up with YouTube Music's radio as the end approaches (off in unit tests: it uses the network). */
    private val autoRefillQueue: Boolean = true,
    /** Called with the raw cookie string after sign-in, or null after sign-out, so the host can persist the session. */
    private val onSessionChanged: (String?) -> Unit = {},
    private val stringsProvider: (() -> AppStrings)? = null,
    /** Last.fm, where what is listened to can be sent (nothing by default). */
    val scrobbling: ScrobbleAccount = NoScrobbling,
    /** The Spotify account, when it is the one chosen. */
    val spotifySession: SpotifySession = NoSpotifySession,
    /** Finds the YouTube recording of a Spotify song (null where Spotify is not offered). */
    private val spotifyLinker: SpotifyLinker? = null,
    /** What the phone does for ringtones (nothing where there are none). */
    ringtoneMaker: RingtoneMaker = NoRingtones,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val strings: AppStrings
        get() = stringsProvider?.invoke()
            ?: (if (settings.language.value == "en") EnglishStrings else TurkishStrings).forService(isSpotify)

    // --- The account: YouTube Music or Spotify, chosen on the welcome screen; only that one is ever used ---
    val accountMode: StateFlow<String> = settings.string(SettingKeys.ACCOUNT_MODE, AccountMode.UNSET)
    val isSpotify: Boolean get() = accountMode.value == AccountMode.SPOTIFY
    val spotifySignedIn: StateFlow<Boolean> = spotifySession.signedIn
    private val spotify: SpotifyWebApi by lazy { SpotifyWebApi(token = { refresh -> spotifySession.token(refresh) }) }

    private val _showSpotifyLoginDialog = MutableStateFlow(false)
    val showSpotifyLoginDialog: StateFlow<Boolean> = _showSpotifyLoginDialog.asStateFlow()

    /** Spotify playlists the account owns (only those can be changed or have songs added). */
    private var spotifyOwned: Set<String> = emptySet()

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
        // Eagerly: toggleFavorite reads it, so it must be current even when no screen is showing it.
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The "Speed dial" grid at the top of the home page: what was played last, then the liked songs. (YouTube
     * Music builds this on its servers; here it comes from this device's history and likes.)
     */
    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    /**
     * "More you may like" under the past searches: the artists of what was played and liked, not searched for yet.
     */
    val suggestedSearches: StateFlow<List<String>> by lazy {
        combine(history, favorites, searchHistory) { recent, liked, searched ->
            val done = searched.map { it.lowercase() }.toSet()
            (recent + liked).flatMap { it.artists }
                .map { it.name.trim() }
                .filter { it.isNotBlank() && it.lowercase() !in done }
                .distinctBy { it.lowercase() }
                .take(SUGGESTED_SEARCHES)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** Which Library tab to open with: 0 favorites ... 2 downloads. Set by "Go to downloads" on the home page. */
    private val _libraryStartTab = MutableStateFlow(0)
    val libraryStartTab: StateFlow<Int> = _libraryStartTab.asStateFlow()

    fun openDownloads() {
        _libraryStartTab.value = LIBRARY_DOWNLOADS_TAB
        selectTab(ScreenTab.LIBRARY)
    }

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
                // Words are kept apart from the song / artist rows, which may share a title with a word.
                val words = (local + remote.filterNot { it.isRich }).distinctBy { it.text.lowercase() }.take(10)
                words + remote.filter { it.isRich }
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

    /** Making ringtones from songs: the editor, and the list of the ones made. */
    val ringtones = RingtoneStudio(
        maker = ringtoneMaker,
        player = playerController,
        settings = settings,
        scope = scope,
        strings = { strings },
        message = { showMessage(it) },
        isOnline = { connectivity.isOnline.value },
    )

    init {
        // Leaving the editor (or the list) stops its preview and gives the song's file back.
        scope.launch {
            _routes.map { it.lastOrNull() }.distinctUntilChanged().collect { top ->
                if (top !is Route.RingtoneEditor) ringtones.close()
                if (top != Route.Ringtones) ringtones.leaveList()
            }
        }
    }

    fun openRingtoneEditor(song: SongItem) {
        dismissSongMenu()
        push(Route.RingtoneEditor(song))
    }

    /** The list of ringtones made; from the editor it takes the editor's place, so Back leads to where that was opened. */
    fun openRingtones() {
        if (_routes.value.lastOrNull() is Route.RingtoneEditor) _routes.value = _routes.value.dropLast(1)
        push(Route.Ringtones)
    }
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
    val audioQualityWifi: StateFlow<String> = settings.audioQualityWifi
    val audioQualityMobile: StateFlow<String> = settings.audioQualityMobile
    val normalizeVolume: StateFlow<Boolean> = settings.normalizeVolume

    fun setAudioQualityWifi(quality: String) = settings.setAudioQualityWifi(quality)

    fun setAudioQualityMobile(quality: String) = settings.setAudioQualityMobile(quality)

    fun setNormalizeVolume(enabled: Boolean) = settings.setNormalizeVolume(enabled)

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
    private val _lyricsTranslation = MutableStateFlow<List<String>?>(null)
    /** The lyrics in the app's language, line by line; null while the original is shown. */
    val lyricsTranslation: StateFlow<List<String>?> = _lyricsTranslation.asStateFlow()
    private var translatedFor: String? = null

    /** How far the timed lyrics are moved, per song (positive: lines come earlier). */
    private val lyricsOffsets = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lyricsOffsetMs: StateFlow<Long> = combine(playerController.currentTrack, lyricsOffsets) { track, offsets ->
        track?.let { offsets[it.id] } ?: 0L
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    private val _lyricsMatches = MutableStateFlow<List<LyricsMatch>?>(null)
    /** The lyrics search's hits; null while it runs (or when no search is open). */
    val lyricsMatches: StateFlow<List<LyricsMatch>?> = _lyricsMatches.asStateFlow()
    private var lyricsSearchJob: Job? = null

    private val _cacheBytes = MutableStateFlow(0L)

    private val previewCache = HashMap<String, com.qmusic.engine.models.PreviewFrames?>()
    private val _seekPreview = MutableStateFlow<com.qmusic.engine.models.PreviewFrames?>(null)
    /** Preview frames of the playing song, for the seek bar; fetched once the full player is open. */
    val seekPreview: StateFlow<com.qmusic.engine.models.PreviewFrames?> = _seekPreview.asStateFlow()

    val amoledTheme: StateFlow<Boolean> = settings.bool(SettingKeys.AMOLED, false)

    /** The Settings rows without a property of their own: appearance, headphones, cache, Last.fm. */
    val moreSettings: StateFlow<MoreSettings> = combine(
        combine(
            settings.bool(SettingKeys.AMOLED, false),
            settings.bool(SettingKeys.HEADSET_RESUME, false),
            settings.bool(SettingKeys.NOTIFICATION_LYRICS, false),
        ) { amoled, headset, lyrics -> Triple(amoled, headset, lyrics) },
        combine(
            settings.string(SettingKeys.CACHE_LIMIT_MB, SettingKeys.DEFAULT_CACHE_MB),
            settings.string(SettingKeys.DOWNLOAD_QUALITY, "HIGH"),
            _cacheBytes,
        ) { cache, quality, bytes -> Triple(cache, quality, bytes) },
        combine(scrobbling.user, settings.bool(SettingKeys.SMART_DOWNLOADS, false)) { user, smart -> user to smart },
    ) { (amoled, headset, lyrics), (cache, quality, bytes), (user, smart) ->
        MoreSettings(amoled, headset, lyrics, cache, bytes, quality, scrobbling.available, user, smart)
    }.stateIn(scope, SharingStarted.Eagerly, MoreSettings())

    /** The line being sung, for the lyrics notification: null when that is off, nothing plays or nothing is timed. */
    val notificationLyricLine: StateFlow<String?> by lazy {
        combine(
            settings.bool(SettingKeys.NOTIFICATION_LYRICS, false),
            lyricsState,
            playbackPosition,
            isPlaying,
            lyricsOffsetMs,
        ) { on, state, position, playing, offset ->
            if (!on || !playing) return@combine null
            val lyrics = (state as? LyricsState.Available)?.lyrics?.takeIf { it.isSynced } ?: return@combine null
            lyrics.lines.lastOrNull { it.timeMs <= position + offset + NOTIFICATION_LYRIC_LEAD_MS }?.text?.takeIf { it.isNotBlank() }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    }
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
    val currentAppVersion: String = "2.0.1"
    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    init {
        // Someone signed in to YouTube Music before the welcome screen existed: that is their choice.
        if (accountMode.value == AccountMode.UNSET && youTubeAuth.isLoggedIn) settings.setString(SettingKeys.ACCOUNT_MODE, AccountMode.YOUTUBE)
        youTube.setAuth(youTubeAuth)
        youTube.setLanguage(settings.language.value)
        if (autoLoadHome) {
            // Back online: a home page that failed or never loaded fills itself in.
            scope.launch {
                connectivity.isOnline.collect { online ->
                    if (online && (_homeSections.value.isEmpty() || _homeError.value != null) && !_isLoadingHome.value) {
                        loadHomeContent()
                    }
                }
            }
            loadHomeContent()
            if (youTubeAuth.isLoggedIn || isSpotify) {
                loadLikedSongs()
                loadYouTubeLibrary()
            }
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
                    val seed = ytId(track) ?: return@collect
                    youTube.radio(seed).onSuccess { more ->
                        if (playerController.currentTrack.value?.id == track.id) playerController.appendToQueue(more)
                    }
                }
            }
        }
        // The sound settings chosen last time: crossfade length and equalizer.
        setCrossfadeDuration(settings.crossfadeSeconds.value)
        runCatching { EqualizerPreset.valueOf(settings.equalizerPreset.value) }.getOrNull()?.let { _selectedPreset.value = it }
        settings.equalizerBands.value.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 10 }?.let { bands ->
            _equalizerBands.value = bands
            bands.forEachIndexed { index, gain -> playerController.setEqualizerBand(index, gain) }
        }
        restoreSession()
        // The queue and the moment are written down as they change, so a later launch can carry on.
        scope.launch {
            combine(playerController.queue, playerController.queueIndex) { queue, index -> queue to index }
                .collect { saveSession() }
        }
        if (autoLoadHome) {
            scope.launch {
                while (true) {
                    delay(SESSION_SAVE_MS)
                    if (playerController.isPlaying.value) saveSession()
                }
            }
        }
        // Stream quality follows the connection: the Wi-Fi choice on Wi-Fi, the mobile one on mobile data.
        scope.launch {
            combine(settings.audioQualityWifi, settings.audioQualityMobile, connectivity.isUnmetered) { wifi, mobile, unmetered ->
                runCatching { AudioQuality.valueOf(if (unmetered) wifi else mobile) }.getOrDefault(AudioQuality.HIGH)
            }.collect { playerController.setAudioQuality(it) }
        }
        scope.launch { settings.normalizeVolume.collect { playerController.setNormalizeVolume(it) } }
        // The seek bar's preview frames: only for the song on screen in the full player, each song asked for once.
        scope.launch {
            combine(playerController.currentTrack.distinctUntilChangedBy { it?.id }, _isPlayerExpanded) { track, open -> track to open }
                .collect { (track, open) ->
                    if (track == null) {
                        _seekPreview.value = null
                        return@collect
                    }
                    if (previewCache.containsKey(track.id)) {
                        _seekPreview.value = previewCache[track.id]
                        return@collect
                    }
                    _seekPreview.value = null
                    if (!open || !connectivity.isOnline.value) return@collect
                    val frames = youTube.previewFrames(track.id).getOrNull()
                    previewCache[track.id] = frames
                    if (playerController.currentTrack.value?.id == track.id) _seekPreview.value = frames
                }
        }
        // Smart downloads refresh themselves once a day, whenever Wi-Fi is there.
        scope.launch {
            combine(settings.bool(SettingKeys.SMART_DOWNLOADS, false), connectivity.isUnmetered, connectivity.isOnline) { on, wifi, online ->
                on && wifi && online
            }.collect { ready -> if (ready) refreshSmartDownloads() }
        }
        // Lyrics in the notification need the lyrics of every song, not only when the lyrics page is opened.
        scope.launch {
            combine(
                playerController.currentTrack.distinctUntilChangedBy { it?.id },
                settings.bool(SettingKeys.NOTIFICATION_LYRICS, false),
            ) { track, on -> track != null && on }
                .collect { wanted -> if (wanted) loadLyrics() }
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
        if (tab == ScreenTab.SETTINGS) refreshCacheSize()
        // Leaving "For you" stops the preview that was playing there, as on YouTube Music.
        if (_currentTab.value == ScreenTab.FOR_YOU && tab != ScreenTab.FOR_YOU) stopSample()
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
    fun play(song: SongItem, context: List<SongItem> = emptyList(), gapless: Boolean = false) {
        applyGapless(gapless)
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
        applyGapless(false)
        if (!shuffleEnabled.value) playerController.setShuffle(true)
        playerController.playQueue(songs, kotlin.random.Random.nextInt(songs.size))
    }

    /** Plays a whole list from its first song (playlist / album "Play"). */
    fun playAll(songs: List<SongItem>, gapless: Boolean = false) {
        if (songs.isEmpty()) return
        applyGapless(gapless)
        playerController.playQueue(songs, 0)
    }

    /**
     * Albums play gapless: live records and songs that run into each other are not blended. Any other list gets
     * the crossfade chosen in Settings back.
     */
    private fun applyGapless(gapless: Boolean) {
        playerController.setCrossfadeDuration(if (gapless) 0 else _crossfadeDuration.value)
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
        settings.setCrossfadeSeconds(clamped)
    }

    fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            val clamped = gainDb.coerceIn(-12f, 12f)
            val updated = _equalizerBands.value.toMutableList()
            updated[bandIndex] = clamped
            _equalizerBands.value = updated
            playerController.setEqualizerBand(bandIndex, clamped)
            settings.setEqualizer(updated.joinToString(","), _selectedPreset.value.name)
        }
    }

    fun applyEqualizerPreset(preset: EqualizerPreset) {
        _selectedPreset.value = preset
        _equalizerBands.value = preset.gains
        preset.gains.forEachIndexed { index, gain ->
            playerController.setEqualizerBand(index, gain)
        }
        settings.setEqualizer(preset.gains.joinToString(","), preset.name)
    }

    // --- Library & Favorites ---
    fun toggleFavorite(song: SongItem) {
        val like = !isFavorite(song.id)
        scope.launch {
            // The device copy follows the combined state, so a song liked only on YouTube can be un-liked too.
            val savedHere = localFavorites.value.any { it.id == song.id }
            if (savedHere != like) repository.toggleFavorite(song)

            if (isSpotify) {
                _likedSongs.value = if (like) listOf(song) + _likedSongs.value.filter { it.id != song.id }
                else _likedSongs.value.filter { it.id != song.id }
                runCatching {
                    val trackId = spotifyTrackId(song) ?: error("No Spotify track")
                    spotify.setTrackSaved(trackId, like)
                }.onFailure { onSpotifyFailure(it) }
            } else if (youTubeAuth.isLoggedIn) {
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

    // --- The YouTube Music library: playlists, saved albums, subscribed artists ---
    private val _ytPlaylists = MutableStateFlow<List<CollectionItem>>(emptyList())
    val ytPlaylists: StateFlow<List<CollectionItem>> = _ytPlaylists.asStateFlow()

    private val _ytAlbums = MutableStateFlow<List<CollectionItem>>(emptyList())
    val ytAlbums: StateFlow<List<CollectionItem>> = _ytAlbums.asStateFlow()

    private val _ytArtists = MutableStateFlow<List<CollectionItem>>(emptyList())
    val ytArtists: StateFlow<List<CollectionItem>> = _ytArtists.asStateFlow()

    /** Fetches the account's playlists, albums and artists; a no-op while signed out. */
    fun loadYouTubeLibrary() {
        if (isSpotify) {
            if (!spotifySignedIn.value) {
                _ytPlaylists.value = emptyList()
                _ytAlbums.value = emptyList()
                _ytArtists.value = emptyList()
                return
            }
            scope.launch {
                runCatching { spotify.playlists() }.onSuccess { list ->
                    spotifyOwned = list.filter { it.owned }.mapTo(HashSet()) { it.item.browseId }
                    _ytPlaylists.value = list.map { it.item }
                }.onFailure { onSpotifyFailure(it) }
            }
            scope.launch { runCatching { spotify.savedAlbums() }.onSuccess { _ytAlbums.value = it } }
            scope.launch { runCatching { spotify.followedArtists() }.onSuccess { _ytArtists.value = it } }
            return
        }
        if (!youTubeAuth.isLoggedIn) {
            _ytPlaylists.value = emptyList()
            _ytAlbums.value = emptyList()
            _ytArtists.value = emptyList()
            return
        }
        scope.launch { youTube.library("FEmusic_liked_playlists").onSuccess { _ytPlaylists.value = it } }
        scope.launch { youTube.library("FEmusic_liked_albums").onSuccess { _ytAlbums.value = it } }
        scope.launch { youTube.library("FEmusic_library_corpus_artists").onSuccess { _ytArtists.value = it } }
    }

    /** Adds [song] to one of the account's YouTube Music playlists, and says how it went. */
    fun addSongToYouTubePlaylist(playlist: CollectionItem, song: SongItem) {
        SpotifyIds.playlistId(playlist.browseId)?.let { playlistId ->
            scope.launch {
                runCatching {
                    spotify.addToPlaylist(playlistId, listOf(spotifyTrackId(song) ?: error("No Spotify track")))
                    collectionCache.remove(playlist.browseId)
                }.onSuccess { showMessage(strings.addedToPlaylist(playlist.title)) }
                    .onFailure { showMessage(strings.couldNotAddToPlaylist) }
            }
            return
        }
        scope.launch {
            youTube.addToPlaylist(playlist.browseId, song.id)
                .onSuccess { showMessage(strings.addedToPlaylist(playlist.title)) }
                .onFailure { showMessage(strings.couldNotAddToPlaylist) }
        }
    }

    // --- Short messages at the bottom of the screen ---
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    fun showMessage(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            _message.value = null
        }
    }

    /** Fetches the account's "Liked music"; a no-op while signed out. */
    fun loadLikedSongs() {
        if (isSpotify) {
            if (!spotifySignedIn.value) {
                _likedSongs.value = emptyList()
                return
            }
            _isSyncingLikes.value = true
            scope.launch {
                runCatching { spotify.savedTracks() }.onSuccess { _likedSongs.value = it }.onFailure { onSpotifyFailure(it) }
                _isSyncingLikes.value = false
            }
            return
        }
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

    fun createPlaylistAndAdd(name: String, song: SongItem, onYouTube: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            if (onYouTube && isSpotify) {
                runCatching { spotify.createPlaylist(trimmed, listOfNotNull(spotifyTrackId(song))) }
                    .onSuccess {
                        showMessage(strings.addedToPlaylist(trimmed))
                        loadYouTubeLibrary()
                    }
                    .onFailure { showMessage(strings.couldNotAddToPlaylist) }
            } else if (onYouTube && youTubeAuth.isLoggedIn) {
                youTube.createPlaylist(trimmed, listOf(song.id))
                    .onSuccess {
                        showMessage(strings.addedToPlaylist(trimmed))
                        loadYouTubeLibrary()
                    }
                    .onFailure { showMessage(strings.couldNotAddToPlaylist) }
            } else {
                val id = repository.createPlaylist(trimmed)
                repository.addSongToPlaylist(id, song)
            }
        }
    }

    // --- Owned YouTube Music playlists: remove, move, delete ---
    private fun updateCollection(browseId: String, change: (CollectionPage) -> CollectionPage) {
        val page = collectionCache[browseId] ?: _collectionPage.value ?: return
        val updated = change(page)
        collectionCache[browseId] = updated
        val top = _routes.value.lastOrNull()
        if (top is Route.Collection && top.item.browseId == browseId) _collectionPage.value = updated
    }

    fun removeFromYouTubePlaylist(item: CollectionItem, song: SongItem) {
        val page = collectionCache[item.browseId] ?: return
        val setVideoId = page.setVideoIds[song.id] ?: return
        updateCollection(item.browseId) { it.copy(songs = it.songs.filterNot { s -> s.id == song.id }) }
        SpotifyIds.playlistId(item.browseId)?.let { playlistId ->
            scope.launch {
                runCatching { spotify.removeFromPlaylist(playlistId, SpotifyIds.trackId(song.id) ?: error("No track")) }
                    .onSuccess { showMessage(strings.removedFromPlaylist) }
                    .onFailure {
                        updateCollection(item.browseId) { page }
                        showMessage(strings.couldNotChangePlaylist)
                    }
            }
            return
        }
        scope.launch {
            youTube.removeFromPlaylist(item.browseId, song.id, setVideoId)
                .onSuccess { showMessage(strings.removedFromPlaylist) }
                .onFailure {
                    updateCollection(item.browseId) { page }
                    showMessage(strings.couldNotChangePlaylist)
                }
        }
    }

    fun moveInYouTubePlaylist(item: CollectionItem, from: Int, to: Int) {
        val page = collectionCache[item.browseId] ?: return
        if (from !in page.songs.indices || to !in page.songs.indices || from == to) return
        val moved = page.songs.toMutableList().apply { add(to, removeAt(from)) }
        SpotifyIds.playlistId(item.browseId)?.let { playlistId ->
            updateCollection(item.browseId) { it.copy(songs = moved) }
            scope.launch {
                runCatching { spotify.moveInPlaylist(playlistId, from, to) }.onFailure {
                    updateCollection(item.browseId) { page }
                    showMessage(strings.couldNotChangePlaylist)
                }
            }
            return
        }
        val setVideoId = page.setVideoIds[moved[to].id] ?: return
        val successor = moved.getOrNull(to + 1)?.let { page.setVideoIds[it.id] }
        updateCollection(item.browseId) { it.copy(songs = moved) }
        scope.launch {
            youTube.movePlaylistItem(item.browseId, setVideoId, successor).onFailure {
                updateCollection(item.browseId) { page }
                showMessage(strings.couldNotChangePlaylist)
            }
        }
    }

    fun deleteYouTubePlaylist(item: CollectionItem) {
        scope.launch {
            val spotifyPlaylist = SpotifyIds.playlistId(item.browseId)
            (if (spotifyPlaylist != null) runCatching { spotify.deletePlaylist(spotifyPlaylist) } else youTube.deletePlaylist(item.browseId))
                .onSuccess {
                    collectionCache.remove(item.browseId)
                    popRoute()
                    loadYouTubeLibrary()
                    showMessage(strings.playlistDeleted)
                }
                .onFailure { showMessage(strings.couldNotChangePlaylist) }
        }
    }

    fun reorderLocalPlaylist(playlistId: Long, songIds: List<String>) {
        scope.launch { repository.reorderPlaylist(playlistId, songIds) }
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

    // --- Carrying on where the last session stopped ---
    private fun saveSession() {
        val queue = playerController.queue.value
        val index = playerController.queueIndex.value
        if (queue.isEmpty() || index !in queue.indices) return
        // Only the songs around the current one: enough to carry on, small enough to write often.
        val from = (index - SESSION_BEHIND).coerceAtLeast(0)
        val songs = queue.subList(from, (index + SESSION_AHEAD).coerceAtMost(queue.size))
        val session = SavedSession(songs, index - from, playerController.playbackPosition.value)
        runCatching { settings.saveSession(backupJson.encodeToString(SavedSession.serializer(), session)) }
    }

    private fun restoreSession() {
        val text = settings.loadSession() ?: return
        val session = runCatching { backupJson.decodeFromString(SavedSession.serializer(), text) }.getOrNull() ?: return
        playerController.restoreQueue(session.queue, session.index, session.positionMs)
    }

    // --- Playback speed ---
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** Steps through the speeds: 1x, 1.25x, 1.5x, 1.75x, 2x, then 0.75x and back to 1x. */
    fun cyclePlaybackSpeed() {
        val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val next = speeds[(speeds.indexOf(_playbackSpeed.value) + 1).mod(speeds.size)]
        _playbackSpeed.value = next
        playerController.setPlaybackSpeed(next)
    }

    // --- Related (the player's "Related" tab) ---
    private val _related = MutableStateFlow<List<HomeSection>?>(null)
    /** Null while loading; empty when YouTube Music has nothing related. */
    val related: StateFlow<List<HomeSection>?> = _related.asStateFlow()
    private val relatedCache = HashMap<String, List<HomeSection>>()
    private var relatedJob: Job? = null

    fun loadRelated() {
        val song = currentTrack.value ?: return
        relatedCache[song.id]?.let {
            _related.value = it
            return
        }
        _related.value = null
        relatedJob?.cancel()
        relatedJob = scope.launch {
            val sections = ytId(song)?.let { youTube.related(it).getOrDefault(emptyList()) }.orEmpty()
            relatedCache[song.id] = sections
            if (currentTrack.value?.id == song.id) _related.value = sections
        }
    }

    // --- Queue ---
    fun clearQueue() = playerController.clearQueue()

    /** Saves what is queued as a playlist on the device (and on YouTube Music when signed in). */
    fun saveQueueAsPlaylist(name: String) {
        val trimmed = name.trim()
        val songs = queue.value
        if (trimmed.isEmpty() || songs.isEmpty()) return
        scope.launch {
            val id = repository.createPlaylist(trimmed)
            songs.forEach { repository.addSongToPlaylist(id, it) }
            if (youTubeAuth.isLoggedIn) {
                youTube.createPlaylist(trimmed, songs.map { it.id }).onSuccess { loadYouTubeLibrary() }
            }
            showMessage(strings.queueSaved(trimmed))
        }
    }

    // --- Backup: favorites, playlists and history in one file ---
    /** Everything worth keeping that lives only on this device, as JSON. */
    suspend fun exportBackup(): String {
        val playlists = repository.getPlaylists().first().map { playlist ->
            BackupPlaylist(playlist.name, repository.getPlaylistSongs(playlist.id).first())
        }
        val backup = Backup(
            version = 1,
            favorites = repository.getFavorites().first(),
            playlists = playlists,
            history = repository.getHistory().first().take(BACKUP_HISTORY),
            searches = repository.getSearchHistory(BACKUP_SEARCHES).first(),
        )
        return backupJson.encodeToString(Backup.serializer(), backup)
    }

    /** Adds what a backup holds to this device (nothing already here is removed). */
    fun importBackup(text: String) {
        scope.launch {
            val backup = runCatching { backupJson.decodeFromString(Backup.serializer(), text) }.getOrNull()
            if (backup == null) {
                showMessage(strings.backupInvalid)
                return@launch
            }
            val liked = localFavorites.value.mapTo(HashSet()) { it.id }
            backup.favorites.filter { it.id !in liked }.forEach { repository.toggleFavorite(it) }
            val existing = playlists.value.map { it.name }.toSet()
            backup.playlists.filter { it.name !in existing }.forEach { playlist ->
                val id = repository.createPlaylist(playlist.name)
                playlist.songs.forEach { repository.addSongToPlaylist(id, it) }
            }
            backup.history.asReversed().forEach { repository.recordHistory(it) }
            backup.searches.asReversed().forEach { repository.recordSearch(it) }
            showMessage(strings.backupRestored(backup.favorites.size, backup.playlists.size))
        }
    }

    fun onBackupSaved(ok: Boolean) = showMessage(if (ok) strings.backupSaved else strings.backupFailed)

    // --- Smart downloads: a mix of what is played most, and songs like it, kept on the device ---
    /** The songs smart downloads fetched itself; songs downloaded by hand are never in it (and never removed by it). */
    private fun smartIds(): Set<String> =
        settings.string(SettingKeys.SMART_IDS, "").value.split(',').filter { it.isNotBlank() }.toSet()

    private var smartJob: Job? = null

    /** Builds today's mix and swaps it in: new songs download, the ones that left the mix are removed. Once a day. */
    private fun refreshSmartDownloads(force: Boolean = false) {
        if (!settings.bool(SettingKeys.SMART_DOWNLOADS, false).value) return
        if (!connectivity.isOnline.value || !isUnmetered()) return
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val last = settings.string(SettingKeys.SMART_REFRESHED_AT, "0").value.toLongOrNull() ?: 0L
        if (!force && now - last < SMART_REFRESH_MS) return
        if (smartJob?.isActive == true) return
        smartJob = scope.launch {
            val mix = buildSmartMix()
            if (mix.isEmpty()) return@launch
            val wanted = mix.mapTo(HashSet()) { it.id }
            val mine = smartIds()
            val kept = favorites.value.mapTo(HashSet()) { it.id }
            // What left the mix makes room, unless it was liked meanwhile.
            downloadedSongs.value.filter { it.id in mine && it.id !in wanted && it.id !in kept }.forEach(::removeDownload)
            val already = downloads.downloadedIds.value
            val fetched = mix.filter { it.id !in already }
            fetched.forEach(::download)
            // Ours: what it fetched now, plus what it fetched before and still wants.
            settings.setString(SettingKeys.SMART_IDS, ((mine intersect wanted) + fetched.map { it.id }).joinToString(","))
            settings.setString(SettingKeys.SMART_REFRESHED_AT, now.toString())
        }
    }

    /** The most played songs of the last month and the liked ones, then YouTube Music's radio of a few of them. */
    private suspend fun buildSmartMix(): List<SongItem> {
        val top = repository.playCounts(SMART_DAYS).sortedByDescending { it.second }.map { it.first }.take(SMART_TOP)
        val liked = favorites.value.take(SMART_LIKED)
        val seeds = (top.take(SMART_SEEDS) + liked.take(2)).distinctBy { it.id }.ifEmpty { history.value.take(SMART_SEEDS) }
        val similar = seeds.flatMap { seed -> ytId(seed)?.let { youTube.radio(it).getOrDefault(emptyList()).take(SMART_PER_SEED) }.orEmpty() }
        return (top + liked + similar).distinctBy { it.id }.take(SMART_COUNT)
    }

    /** Turning smart downloads off gives the room back: what it fetched (and was not liked meanwhile) is removed. */
    private fun removeSmartDownloads() {
        smartJob?.cancel()
        val mine = smartIds()
        val kept = favorites.value.mapTo(HashSet()) { it.id }
        downloadedSongs.value.filter { it.id in mine && it.id !in kept }.forEach(::removeDownload)
        settings.setString(SettingKeys.SMART_IDS, "")
        settings.setString(SettingKeys.SMART_REFRESHED_AT, "0")
    }

    // --- Settings without a property of their own ---
    private fun refreshCacheSize() {
        _cacheBytes.value = downloads.streamCacheBytes()
    }

    fun onMoreSettings(change: MoreSettingsChange) {
        when (change) {
            is MoreSettingsChange.Amoled -> settings.setBool(SettingKeys.AMOLED, change.on)
            is MoreSettingsChange.HeadsetResume -> settings.setBool(SettingKeys.HEADSET_RESUME, change.on)
            is MoreSettingsChange.NotificationLyrics -> settings.setBool(SettingKeys.NOTIFICATION_LYRICS, change.on)
            is MoreSettingsChange.CacheLimit -> settings.setString(SettingKeys.CACHE_LIMIT_MB, change.mb)
            is MoreSettingsChange.DownloadQuality -> settings.setString(SettingKeys.DOWNLOAD_QUALITY, change.quality)
            is MoreSettingsChange.SmartDownloads -> {
                settings.setBool(SettingKeys.SMART_DOWNLOADS, change.on)
                if (change.on) refreshSmartDownloads(force = true) else removeSmartDownloads()
            }
            MoreSettingsChange.ClearCache -> {
                downloads.clearStreamCache()
                showMessage(strings.cacheCleared)
                scope.launch {
                    delay(CACHE_REFRESH_MS)
                    refreshCacheSize()
                }
            }
            is MoreSettingsChange.LastFmSignIn -> scope.launch {
                scrobbling.signIn(change.user.trim(), change.password)
                    .onSuccess { showMessage(strings.lastFmConnected) }
                    .onFailure { showMessage(it.message?.takeIf(String::isNotBlank) ?: strings.lastFmFailed) }
            }
            MoreSettingsChange.LastFmSignOut -> scrobbling.signOut()
        }
    }

    /**
     * Opens a YouTube / YouTube Music link (shared to the app or tapped elsewhere): a song plays, a playlist, album
     * or artist opens on its page.
     */
    fun openLink(text: String) {
        when (val link = AppLinks.parse(text)) {
            null -> showMessage(strings.linkNotSupported)
            is AppLink.Song -> scope.launch {
                val song = youTube.song(link.videoId).getOrNull() ?: SongItem(id = link.videoId, title = link.videoId)
                play(song)
                setPlayerExpanded(true)
            }
            is AppLink.Playlist -> openCollection(CollectionItem(browseId = "VL" + link.listId, title = "", kind = CollectionKind.PLAYLIST))
            is AppLink.Album -> openCollection(CollectionItem(browseId = link.browseId, title = "", kind = CollectionKind.ALBUM))
            is AppLink.Artist -> openArtist(link.channelId, "")
            is AppLink.Spotify -> openSpotifyLink(link)
        }
    }

    private fun openSpotifyLink(link: AppLink.Spotify) {
        if (!isSpotify || !spotifySignedIn.value) {
            showMessage(strings.linkNotSupported)
            return
        }
        when (link.kind) {
            "album" -> openCollection(CollectionItem(SpotifyIds.album(link.id), "", kind = CollectionKind.ALBUM))
            "playlist" -> openCollection(CollectionItem(SpotifyIds.playlist(link.id), "", kind = CollectionKind.PLAYLIST))
            "artist" -> openArtist(SpotifyIds.artist(link.id), "")
            else -> scope.launch {
                runCatching { spotify.track(link.id) }.getOrNull()
                    ?.let {
                        play(it)
                        setPlayerExpanded(true)
                    } ?: showMessage(strings.linkNotSupported)
            }
        }
    }

    // --- Lyrics ---
    fun shiftLyrics(deltaMs: Long) {
        val id = currentTrack.value?.id ?: return
        val offsets = lyricsOffsets.value
        lyricsOffsets.value = offsets + (id to ((offsets[id] ?: 0L) + deltaMs))
    }

    fun searchLyrics(query: String) {
        if (query.isBlank()) return
        lyricsSearchJob?.cancel()
        _lyricsMatches.value = null
        lyricsSearchJob = scope.launch {
            _lyricsMatches.value = youTube.searchLyrics(query.trim()).getOrDefault(emptyList())
        }
    }

    fun closeLyricsSearch() {
        lyricsSearchJob?.cancel()
        _lyricsMatches.value = null
    }

    /** Shows the picked lyrics for the playing song from now on. */
    fun useLyrics(match: LyricsMatch) {
        val song = currentTrack.value ?: return
        lyricsCache[song.id] = match.lyrics
        _lyricsTranslation.value = null
        _lyricsState.value = LyricsState.Available(match.lyrics)
        closeLyricsSearch()
    }

    /** Loads the lyrics of the playing song (cached per song). Called when the lyrics page is shown. */

    fun toggleLyricsTranslation() {
        if (_lyricsTranslation.value != null) {
            _lyricsTranslation.value = null
            return
        }
        val song = currentTrack.value ?: return
        val lyrics = (_lyricsState.value as? LyricsState.Available)?.lyrics ?: return
        val lines = if (lyrics.isSynced) lyrics.lines.map { it.text } else lyrics.text.split("\n")
        scope.launch {
            youTube.translate(lines.joinToString("\n"), settings.language.value)
                .onSuccess { translated ->
                    if (currentTrack.value?.id == song.id) {
                        translatedFor = song.id
                        _lyricsTranslation.value = translated
                    }
                }
                .onFailure { showMessage(it.message ?: "") }
        }
    }

    fun loadLyrics() {
        val song = currentTrack.value ?: return
        if (translatedFor != song.id) _lyricsTranslation.value = null
        lyricsJob?.cancel()
        if (lyricsCache.containsKey(song.id)) {
            _lyricsState.value = lyricsCache[song.id]?.let(LyricsState::Available) ?: LyricsState.Unavailable
            return
        }
        _lyricsState.value = LyricsState.Loading
        lyricsJob = scope.launch {
            // Lines that move with the song are preferred; YouTube Music's plain text is the fallback.
            val synced = async { youTube.syncedLyrics(song.title, song.artists.joinToString(", ") { it.name }, song.duration).getOrNull() }
            val plain = ytId(song)?.let { youTube.lyrics(it).getOrNull() }
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
                if (_searchQuery.value.trim() == trimmed) _remoteSuggestions.value = if (isSpotify) suggestions.filterNot { it.isRich } else suggestions
            }
        }
    }

    fun setSearchFilter(filter: SearchFilter?) {
        _selectedFilter.value = filter
    }

    fun selectSuggestion(suggestion: SearchSuggestion) {
        // A suggested song plays, a suggested artist or album opens; words are searched for.
        suggestion.song?.let { play(it); return }
        suggestion.artist?.let { openArtist(it.browseId, it.name); return }
        suggestion.collection?.let { openCollection(it); return }
        _searchQuery.value = suggestion.text
        performSearch(suggestion.text)
    }

    fun deleteSearchHistoryEntry(query: String) {
        scope.launch { repository.deleteSearch(query) }
    }

    fun clearSearchHistory() {
        scope.launch { repository.clearSearchHistory() }
    }

    private var loadingMoreResults = false

    /** Fetches the next part of a filtered search when its list is scrolled to the end. */
    fun loadMoreSearchResults() {
        val page = _searchPage.value ?: return
        val token = page.continuation ?: return
        if (loadingMoreResults) return
        loadingMoreResults = true
        scope.launch {
            val next = if (isSpotify) runCatching { spotify.searchMore(_searchQuery.value.trim(), token) ?: error("No more") } else youTube.searchMore(token)
            next.onSuccess { more ->
                val current = _searchPage.value
                if (current?.continuation == token) {
                    _searchPage.value = current.copy(
                        songs = (current.songs + more.songs).distinctBy { it.id },
                        albums = (current.albums + more.albums).distinctBy { it.browseId },
                        artists = (current.artists + more.artists).distinctBy { it.browseId },
                        playlists = (current.playlists + more.playlists).distinctBy { it.browseId },
                        continuation = more.continuation,
                    )
                }
            }
            loadingMoreResults = false
        }
    }

    /** The back arrow of the search box: leaves the results and the typed words. */
    fun clearSearch() {
        suggestionJob?.cancel()
        _searchQuery.value = ""
        _remoteSuggestions.value = emptyList()
        _showSearchResults.value = false
        _searchPage.value = null
        _searchError.value = null
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

        // No connection: the downloaded songs are what can be played, so the search looks there.
        if (!isOnline.value) {
            val words = trimmed.lowercase()
            val found = downloadedSongs.value.filter { song ->
                song.title.lowercase().contains(words) || song.artists.any { it.name.lowercase().contains(words) }
            }
            _searchPage.value = SearchResultsPage(songs = found)
            _searchResults.value = found
            _isSearching.value = false
            showMessage(strings.offlineSearchNote)
            return
        }

        scope.launch {
            repository.recordSearch(trimmed)
            val result = if (isSpotify) runCatching { spotify.search(trimmed, filter) } else youTube.searchPage(query = trimmed, filter = filter)
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
            if (tab == ScreenTab.LIBRARY) {
                _libraryStartTab.value = 0
                if (isSpotify) loadLikedSongs()
                // The account's library may have changed on another device since it was last fetched.
                loadYouTubeLibrary()
            }
        }
        selectTab(tab)
    }

    private fun loadHome(params: String?) {
        if (isSpotify) {
            loadSpotifyHome()
            return
        }
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
                    // What sits at the top of the home page is what gets tapped: get it ready to start at once.
                    playerController.warmUp(
                        (speedDial.value.take(WARM_SPEED_DIAL) + page.sections.filter { it.style == SectionStyle.ROWS }
                            .flatMap { it.songs.take(WARM_QUICK_PICKS) }).distinctBy { it.id },
                    )
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
            is Route.Explore -> loadExplore(route)
            is Route.Stats -> loadStats(_statsPeriod.value)
            is Route.RingtoneEditor -> ringtones.open(route.song)
            Route.Ringtones -> ringtones.refreshList()
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
            val spotifyPlaylist = SpotifyIds.playlistId(item.browseId)
            val spotifyAlbum = SpotifyIds.albumId(item.browseId)
            val loaded = when {
                spotifyPlaylist != null -> runCatching { spotify.playlistPage(spotifyPlaylist) }
                spotifyAlbum != null -> runCatching { spotify.albumPage(spotifyAlbum) }
                else -> youTube.collection(item.browseId)
            }
            loaded.onSuccess { page ->
                val complete = page.copy(
                    title = page.title.ifBlank { item.title },
                    thumbnail = page.thumbnail ?: item.thumbnail,
                )
                collectionCache[item.browseId] = complete
                // Only show the result if the user has not already moved on to another page.
                if (_routes.value.lastOrNull() == route) {
                    _collectionPage.value = complete
                    playerController.warmUp(complete.songs.take(WARM_PAGE_SONGS))
                }
            }.onFailure { throwable ->
                if (_routes.value.lastOrNull() == route) _collectionError.value = throwable.message ?: strings.couldNotOpenPage
            }
            if (_routes.value.lastOrNull() == route) _isLoadingCollection.value = false
        }
    }

    /** Subscribe / unsubscribe on the open artist page; shown at once, undone if YouTube Music refuses. */
    fun toggleArtistSubscription() {
        val page = _artistPage.value ?: return
        val channelId = page.channelId ?: return
        SpotifyIds.artistId(channelId)?.let { artistId ->
            if (!spotifySignedIn.value) {
                setShowSpotifyLoginDialog(true)
                return
            }
            val updated = page.copy(subscribed = !page.subscribed)
            _artistPage.value = updated
            artistCache[channelId] = updated
            scope.launch {
                runCatching { spotify.setArtistFollowed(artistId, updated.subscribed) }.onSuccess { loadYouTubeLibrary() }.onFailure {
                    if (_artistPage.value?.channelId == channelId) _artistPage.value = page
                    artistCache[channelId] = page
                }
            }
            return
        }
        if (!youTubeAuth.isLoggedIn) {
            setShowYouTubeLoginDialog(true)
            return
        }
        val updated = page.copy(subscribed = !page.subscribed)
        _artistPage.value = updated
        artistCache.entries.firstOrNull { it.value.channelId == channelId }?.let { artistCache[it.key] = updated }
        scope.launch {
            youTube.setSubscribed(channelId, updated.subscribed).onFailure {
                if (_artistPage.value?.channelId == channelId) _artistPage.value = page
                artistCache.entries.firstOrNull { it.value.channelId == channelId }?.let { artistCache[it.key] = page }
            }
        }
    }

    // --- "For you": a feed of short previews, one song per screen ---
    private val _samples = MutableStateFlow<List<SongItem>>(emptyList())
    val samples: StateFlow<List<SongItem>> = _samples.asStateFlow()

    /** The song being previewed (it starts part-way in); null once it is played in full or stopped. */
    private var sampleId: String? = null
    private var sampleJob: Job? = null
    private var loadingMoreSamples = false

    /** Fills the feed from the home page's songs the first time it is needed. */
    fun prepareSamples() {
        if (_samples.value.isNotEmpty()) return
        _samples.value = _homeSections.value.flatMap { it.songs }.distinctBy { it.id }.shuffled()
    }

    /** Tops the feed up with songs like the last one when the end comes near. */
    fun loadMoreSamples() {
        val last = _samples.value.lastOrNull() ?: return
        if (loadingMoreSamples) return
        loadingMoreSamples = true
        scope.launch {
            youTube.radio(ytId(last) ?: last.id).onSuccess { more ->
                val known = _samples.value.mapTo(HashSet()) { it.id }
                _samples.value = _samples.value + more.filter { it.id !in known }
            }
            loadingMoreSamples = false
        }
    }

    /** Plays [song] from about a third of the way in: the part that tells what it is like. */
    fun playSample(song: SongItem) {
        if (sampleId == song.id) return
        sampleId = song.id
        sampleJob?.cancel()
        // The stream is opened right at that point: starting at 0 and jumping later loaded the song twice, and the
        // jump could land before the new song was there (so the preview began at the start, and late).
        val length = (song.duration ?: 0) * 1000L
        playerController.playFrom(song, if (length > 0) (length * SAMPLE_START).toLong() else SAMPLE_FALLBACK_MS)
        // The next previews are looked up now, so swiping to them does not wait for that.
        val next = _samples.value.dropWhile { it.id != song.id }.drop(1).take(SAMPLE_WARM_AHEAD)
        playerController.warmUp(next)
    }

    /** "Play full song": back to the start, and it keeps playing after leaving the tab. */
    fun playSampleFully(song: SongItem) {
        sampleJob?.cancel()
        sampleId = null
        if (playerController.currentTrack.value?.id == song.id) playerController.seekTo(0L) else playerController.play(song)
    }

    private fun stopSample() {
        val id = sampleId ?: return
        sampleJob?.cancel()
        sampleId = null
        if (playerController.currentTrack.value?.id == id) playerController.pause()
    }

    // --- Listening statistics ---
    private val _statsPeriod = MutableStateFlow(StatsPeriod.MONTH)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    private val _stats = MutableStateFlow<ListeningStats?>(null)
    val stats: StateFlow<ListeningStats?> = _stats.asStateFlow()

    fun openStats() = push(Route.Stats)

    fun loadStats(period: StatsPeriod) {
        _statsPeriod.value = period
        scope.launch {
            val counts = repository.playCounts(period.days)
            val artists = counts.flatMap { (song, plays) -> song.artists.map { it.name to plays } }
                .filter { it.first.isNotBlank() }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
                .entries.sortedByDescending { it.value }
                .map { it.key to it.value }
            _stats.value = ListeningStats(
                period = period,
                plays = counts.sumOf { it.second },
                songs = counts.size,
                minutes = counts.sumOf { (song, plays) -> (song.duration ?: 0) * plays } / 60,
                topSongs = counts.take(10),
                topArtists = artists.take(5),
            )
        }
    }

    // --- Explore: new releases, charts, moods & genres ---
    private val _explorePage = MutableStateFlow<ExplorePage?>(null)
    val explorePage: StateFlow<ExplorePage?> = _explorePage.asStateFlow()

    private val _isLoadingExplore = MutableStateFlow(false)
    val isLoadingExplore: StateFlow<Boolean> = _isLoadingExplore.asStateFlow()

    private val _exploreError = MutableStateFlow<String?>(null)
    val exploreError: StateFlow<String?> = _exploreError.asStateFlow()

    private val exploreCache = mutableMapOf<String, ExplorePage>()

    fun openExplore(browseId: String, params: String?, title: String) = push(Route.Explore(browseId, params, title))

    private fun loadExplore(route: Route.Explore) {
        val key = "${route.browseId}|${route.params}"
        exploreCache[key]?.let {
            _explorePage.value = it
            _exploreError.value = null
            _isLoadingExplore.value = false
            return
        }
        _explorePage.value = null
        _exploreError.value = null
        _isLoadingExplore.value = true
        scope.launch {
            youTube.explore(route.browseId, route.params).onSuccess { page ->
                val complete = page.copy(title = page.title.ifBlank { route.title })
                exploreCache[key] = complete
                if (_routes.value.lastOrNull() == route) _explorePage.value = complete
            }.onFailure { throwable ->
                if (_routes.value.lastOrNull() == route) _exploreError.value = throwable.message ?: strings.couldNotOpenPage
            }
            if (_routes.value.lastOrNull() == route) _isLoadingExplore.value = false
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
            val spotifyArtist = SpotifyIds.artistId(route.browseId)
            val loaded = if (spotifyArtist != null) runCatching { spotifyArtistPage(route.browseId, spotify.artist(spotifyArtist)) } else youTube.artist(route.browseId)
            loaded.onSuccess { page ->
                val complete = page.copy(name = page.name.ifBlank { route.name })
                artistCache[route.browseId] = complete
                if (_routes.value.lastOrNull() == route) {
                    _artistPage.value = complete
                    playerController.warmUp(complete.topSongs.take(WARM_PAGE_SONGS))
                }
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

    // --- Spotify ---
    fun setShowSpotifyLoginDialog(show: Boolean) {
        _showSpotifyLoginDialog.value = show
    }

    /** The welcome screen's choice: sign in to that service, or go on without an account. */
    fun chooseAccount(mode: String) {
        when (mode) {
            AccountMode.YOUTUBE -> setShowYouTubeLoginDialog(true)
            AccountMode.SPOTIFY -> if (spotifySession.available) setShowSpotifyLoginDialog(true) else showMessage(strings.spotifyNotSupported)
            else -> {
                settings.setString(SettingKeys.ACCOUNT_MODE, AccountMode.NONE)
                loadHomeContent()
            }
        }
    }

    /** Signed in on Spotify's page: Spotify is the account from now on, and YouTube Music is signed out of. */
    fun onSpotifySignedIn() {
        spotifySession.onSignedIn()
        _showSpotifyLoginDialog.value = false
        if (youTubeAuth.isLoggedIn) {
            youTubeAuth.setCookies("")
            youTube.setAuth(youTubeAuth)
            _isLoggedIn.value = false
            onSessionChanged(null)
        }
        settings.setString(SettingKeys.ACCOUNT_MODE, AccountMode.SPOTIFY)
        reloadAccount()
    }

    /** Signs out of Spotify; the app stays a Spotify app (Settings offers to sign in again). */
    fun signOutSpotify() {
        spotifySession.signOut()
        reloadAccount()
    }

    /** Back to the welcome screen: the current account is signed out of. */
    fun changeAccount() {
        if (spotifySignedIn.value) spotifySession.signOut()
        if (youTubeAuth.isLoggedIn) {
            youTubeAuth.setCookies("")
            youTube.setAuth(youTubeAuth)
            _isLoggedIn.value = false
            onSessionChanged(null)
        }
        settings.setString(SettingKeys.ACCOUNT_MODE, AccountMode.UNSET)
        clearRoutes()
        selectTab(ScreenTab.HOME)
        reloadAccount()
    }

    /** Everything that came from the account, fetched again (after a sign-in, a sign-out or a switch). */
    private fun reloadAccount() {
        collectionCache.clear()
        artistCache.clear()
        _likedSongs.value = emptyList()
        _ytPlaylists.value = emptyList()
        _ytAlbums.value = emptyList()
        _ytArtists.value = emptyList()
        _homeChips.value = emptyList()
        _selectedHomeChip.value = null
        loadHomeContent()
        loadLikedSongs()
        loadYouTubeLibrary()
    }

    /** The YouTube video for [song]: itself, or for a Spotify song the recording found for it. */
    private suspend fun ytId(song: SongItem): String? =
        if (SpotifyIds.isSpotify(song.id)) spotifyLinker?.youtubeId(song) else song.id

    /** The Spotify track of [song], searched on Spotify when the song came from elsewhere (a radio, a link). */
    private suspend fun spotifyTrackId(song: SongItem): String? =
        SpotifyIds.trackId(song.id)
            ?: runCatching { spotify.findTrack(song.title, song.artists.firstOrNull()?.name.orEmpty(), song.duration) }.getOrNull()

    private fun onSpotifyFailure(error: Throwable) {
        if (error is SpotifySignedOutException) showMessage(strings.spotifySessionExpired)
    }

    /** The Spotify home: what was played, the favourites, the playlists, new releases and songs like the top one. */
    private fun loadSpotifyHome() {
        homeJob?.cancel()
        _homeChips.value = emptyList()
        _isLoadingMoreHome.value = false
        if (!spotifySignedIn.value) {
            _homeSections.value = emptyList()
            _homeError.value = strings.spotifySignedOutSubtitle
            _isLoadingHome.value = false
            return
        }
        _isLoadingHome.value = true
        _homeError.value = null
        homeJob = scope.launch {
            val s = strings
            val recent = async { runCatching { spotify.recentlyPlayed() }.getOrDefault(emptyList()) }
            val top = async { runCatching { spotify.topTracks() }.getOrDefault(emptyList()) }
            val artists = async { runCatching { spotify.topArtists() }.getOrDefault(emptyList()) }
            val playlists = async { runCatching { spotify.playlists(max = 50).map { it.item } }.getOrDefault(emptyList()) }
            val releases = async { runCatching { spotify.newReleases() }.getOrDefault(emptyList()) }
            val signedOut = runCatching { spotify.me() }.exceptionOrNull() is SpotifySignedOutException
            val sections = listOfNotNull(
                HomeSection(s.spotifyRecentlyPlayed, songs = recent.await().take(HOME_ROW_SONGS), style = SectionStyle.ROWS, canPlayAll = true),
                HomeSection(s.spotifyTopSongs, songs = top.await().take(HOME_ROW_SONGS), style = SectionStyle.ROWS, canPlayAll = true),
                HomeSection(s.spotifyTopArtists, collections = artists.await()),
                HomeSection(s.spotifyYourPlaylists, collections = playlists.await()),
                HomeSection(s.spotifyNewReleases, collections = releases.await()),
            ).filter { it.songs.isNotEmpty() || it.collections.isNotEmpty() }
            _homeSections.value = sections
            _isLoadingHome.value = false
            if (sections.isEmpty()) {
                _homeError.value = if (signedOut) s.spotifySessionExpired else s.youtubeReturnedNothing
                return@launch
            }
            playerController.warmUp(sections.flatMap { it.songs.take(WARM_QUICK_PICKS) })
            // Songs like the favourite of the month, found by sound: something new next to what is known.
            val seed = (top.await() + recent.await()).firstOrNull() ?: return@launch
            val similar = ytId(seed)?.let { youTube.radio(it).getOrDefault(emptyList()) }.orEmpty().take(HOME_ROW_SONGS)
            if (similar.isNotEmpty()) {
                _homeSections.value = _homeSections.value.take(2) +
                    HomeSection(s.spotifyMoreLike(seed.title), songs = similar, style = SectionStyle.ROWS, canPlayAll = true) +
                    _homeSections.value.drop(2)
            }
        }
    }

    /** An artist page from Spotify's parts, worded in the app's language. */
    private fun spotifyArtistPage(browseId: String, artist: com.qmusic.spotify.SpotifyArtist): ArtistPage {
        val s = strings
        return ArtistPage(
            name = artist.name,
            thumbnail = artist.image,
            topSongs = artist.topSongs,
            sections = listOfNotNull(
                artist.albums.takeIf { it.isNotEmpty() }?.let { HomeSection(s.spotifyAlbums, collections = it) },
                artist.singles.takeIf { it.isNotEmpty() }?.let { HomeSection(s.spotifySingles, collections = it) },
                artist.related.takeIf { it.isNotEmpty() }?.let { HomeSection(s.spotifyRelated, collections = it) },
            ),
            audience = artist.followers?.let { s.spotifyFollowers(compactCount(it)) },
            channelId = browseId,
            subscribed = artist.following,
        )
    }

    private fun compactCount(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M".replace(".0M", "M")
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }

    // --- YouTube Authentication ---
    fun onYouTubeCookiesCaptured(cookies: String) {
        youTubeAuth.setCookies(cookies)
        youTube.setAuth(youTubeAuth)
        _isLoggedIn.value = youTubeAuth.isLoggedIn
        _showYouTubeLoginDialog.value = false
        if (youTubeAuth.isLoggedIn) {
            if (spotifySignedIn.value) spotifySession.signOut()
            settings.setString(SettingKeys.ACCOUNT_MODE, AccountMode.YOUTUBE)
            onSessionChanged(cookies)
            loadHomeContent()
            loadLikedSongs()
            loadYouTubeLibrary()
        }
    }

    fun logoutYouTube() {
        youTubeAuth.setCookies("")
        youTube.setAuth(youTubeAuth)
        _isLoggedIn.value = false
        onSessionChanged(null)
        _likedSongs.value = emptyList()
        loadYouTubeLibrary()
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
        const val CACHE_REFRESH_MS = 600L
        const val HOME_ROW_SONGS = 20
        const val SMART_REFRESH_MS = 24 * 60 * 60 * 1000L
        const val SMART_COUNT = 50
        const val SMART_DAYS = 30
        const val SMART_TOP = 20
        const val SMART_LIKED = 15
        const val SMART_SEEDS = 3
        const val SMART_PER_SEED = 10
        const val NOTIFICATION_LYRIC_LEAD_MS = 250L
        const val SESSION_SAVE_MS = 10_000L
        const val SESSION_BEHIND = 20
        const val SESSION_AHEAD = 80
        const val BACKUP_HISTORY = 500
        const val BACKUP_SEARCHES = 50
        val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
        const val WARM_SPEED_DIAL = 4
        const val WARM_QUICK_PICKS = 4
        const val WARM_PAGE_SONGS = 3
        const val SAMPLE_START = 0.3
        /** Where a preview starts when the song's length is not known. */
        const val SAMPLE_FALLBACK_MS = 45_000L
        const val SAMPLE_WARM_AHEAD = 2
        const val MESSAGE_MS = 3_000L
        const val SUGGESTED_SEARCHES = 12
        const val LIBRARY_DOWNLOADS_TAB = 2
        const val SPEED_DIAL_SIZE = 17 // two pages of nine, one slot of which is the dice
        val DOUBLE_TAP_WINDOW = 500.milliseconds
        const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
