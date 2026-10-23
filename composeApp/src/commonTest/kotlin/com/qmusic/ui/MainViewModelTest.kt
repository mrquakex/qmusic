package com.qmusic.ui

import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.database.repository.MusicRepository
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.player.NoOpPlayerController
import com.qmusic.ui.viewmodel.EqualizerPreset
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.ScreenTab
import com.qmusic.updater.UpdateChecker
import com.qmusic.updater.model.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeMusicRepository : MusicRepository {
    val favoritesList = mutableListOf<SongItem>()
    val historyList = mutableListOf<SongItem>()
    val playlistsList = mutableListOf<PlaylistEntity>()
    val playlistSongsMap = mutableMapOf<Long, MutableList<SongItem>>()

    private val favoritesFlow = MutableStateFlow<List<SongItem>>(emptyList())
    private val historyFlow = MutableStateFlow<List<SongItem>>(emptyList())
    private val playlistsFlow = MutableStateFlow<List<PlaylistEntity>>(emptyList())

    override fun getFavorites(): Flow<List<SongItem>> = favoritesFlow

    override fun getHistory(): Flow<List<SongItem>> = historyFlow

    override suspend fun toggleFavorite(song: SongItem) {
        val existingIndex = favoritesList.indexOfFirst { it.id == song.id }
        if (existingIndex >= 0) {
            favoritesList.removeAt(existingIndex)
        } else {
            favoritesList.add(song)
        }
        favoritesFlow.value = favoritesList.toList()
    }

    override suspend fun recordHistory(song: SongItem) {
        historyList.add(0, song)
        historyFlow.value = historyList.toList()
    }

    override fun getPlaylists(): Flow<List<PlaylistEntity>> = playlistsFlow

    override suspend fun createPlaylist(name: String): Long {
        val id = (playlistsList.size + 1).toLong()
        val playlist = PlaylistEntity(id = id, name = name, createdAt = 1000L)
        playlistsList.add(playlist)
        playlistsFlow.value = playlistsList.toList()
        return id
    }

    override suspend fun addSongToPlaylist(playlistId: Long, song: SongItem) {
        playlistSongsMap.getOrPut(playlistId) { mutableListOf() }.add(song)
    }

    private val downloadedFlow = MutableStateFlow<List<SongItem>>(emptyList())

    override fun getDownloadedSongs(): Flow<List<SongItem>> = downloadedFlow

    fun downloadedFlowIdsForTest(): List<String> = downloadedFlow.value.map { it.id }

    override suspend fun setDownloaded(song: SongItem, downloaded: Boolean) {
        downloadedFlow.value = downloadedFlow.value.filter { it.id != song.id } + if (downloaded) listOf(song) else emptyList()
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        playlistSongsMap[playlistId]?.removeAll { it.id == songId }
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String) {
        val index = playlistsList.indexOfFirst { it.id == playlistId }
        if (index >= 0) playlistsList[index] = playlistsList[index].copy(name = name)
        playlistsFlow.value = playlistsList.toList()
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistsList.removeAll { it.id == playlistId }
        playlistSongsMap.remove(playlistId)
        playlistsFlow.value = playlistsList.toList()
    }

    private val searchHistoryFlow = MutableStateFlow<List<String>>(emptyList())

    override fun getSearchHistory(limit: Int): Flow<List<String>> = searchHistoryFlow

    override suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        searchHistoryFlow.value = listOf(trimmed) + searchHistoryFlow.value.filter { !it.equals(trimmed, ignoreCase = true) }
    }

    override suspend fun deleteSearch(query: String) {
        searchHistoryFlow.value = searchHistoryFlow.value.filter { it != query }
    }

    override suspend fun clearSearchHistory() {
        searchHistoryFlow.value = emptyList()
    }

    override fun getPlaylistSongs(playlistId: Long): Flow<List<SongItem>> {
        return MutableStateFlow(playlistSongsMap[playlistId] ?: emptyList())
    }
}

class FakeUpdateChecker(
    var updateToReturn: UpdateInfo? = null,
) : UpdateChecker {
    override suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> {
        return Result.success(updateToReturn)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeRepo = FakeMusicRepository()
    private val playerController = NoOpPlayerController()
    private val fakeUpdater = FakeUpdateChecker()
    private val youTubeAuth = YouTubeAuth()

    private fun createViewModel(scope: kotlinx.coroutines.CoroutineScope = testScope): MainViewModel {
        return MainViewModel(
            playerController = playerController,
            repository = fakeRepo,
            youTubeAuth = youTubeAuth,
            updateChecker = fakeUpdater,
            externalScope = scope,
            autoLoadHome = false,
            autoRefillQueue = false,
        )
    }

    @Test
    fun testInitialState() {
        val vm = createViewModel()
        assertEquals(ScreenTab.HOME, vm.currentTab.value)
        assertEquals(3, vm.crossfadeDuration.value)
        assertEquals(10, vm.equalizerBands.value.size)
        assertFalse(vm.isPlayerExpanded.value)
        assertFalse(vm.showEqualizerDialog.value)
        assertFalse(vm.showSpotifyImportDialog.value)
        assertFalse(vm.showYouTubeLoginDialog.value)
    }

    @Test
    fun testNavigationTabSelection() {
        val vm = createViewModel()
        vm.selectTab(ScreenTab.SEARCH)
        assertEquals(ScreenTab.SEARCH, vm.currentTab.value)

        vm.selectTab(ScreenTab.LIBRARY)
        assertEquals(ScreenTab.LIBRARY, vm.currentTab.value)

        vm.selectTab(ScreenTab.SETTINGS)
        assertEquals(ScreenTab.SETTINGS, vm.currentTab.value)
    }

    @Test
    fun testPlaybackControls() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val testSong = SongItem(
            id = "test-123",
            title = "Midnight City",
            artists = listOf(Artist("M83")),
            duration = 240,
        )

        vm.play(testSong)
        assertEquals(testSong, vm.currentTrack.value)
        assertTrue(vm.isPlaying.value)

        vm.pause()
        assertFalse(vm.isPlaying.value)

        vm.resume()
        assertTrue(vm.isPlaying.value)

        vm.togglePlayPause()
        assertFalse(vm.isPlaying.value)

        vm.togglePlayPause()
        assertTrue(vm.isPlaying.value)
    }

    @Test
    fun testCrossfadeClamping() {
        val vm = createViewModel()

        vm.setCrossfadeDuration(7)
        assertEquals(7, vm.crossfadeDuration.value)

        vm.setCrossfadeDuration(-5)
        assertEquals(0, vm.crossfadeDuration.value)

        vm.setCrossfadeDuration(20)
        assertEquals(12, vm.crossfadeDuration.value)
    }

    @Test
    fun testEqualizerBandsAndPresets() {
        val vm = createViewModel()

        vm.setEqualizerBand(0, 6.0f)
        assertEquals(6.0f, vm.equalizerBands.value[0])

        // Clamping check
        vm.setEqualizerBand(1, 18.0f)
        assertEquals(12.0f, vm.equalizerBands.value[1])

        vm.setEqualizerBand(2, -20.0f)
        assertEquals(-12.0f, vm.equalizerBands.value[2])

        // Preset check
        vm.applyEqualizerPreset(EqualizerPreset.ROCK)
        assertEquals(EqualizerPreset.ROCK, vm.selectedPreset.value)
        assertEquals(EqualizerPreset.ROCK.gains, vm.equalizerBands.value)
    }

    @Test
    fun testFavoritesAndPlaylists() = testScope.runTest {
        // backgroundScope: the VM's long-lived stateIn sharing jobs must not block test completion.
        val vm = createViewModel(backgroundScope)
        val testSong = SongItem(
            id = "fav-1",
            title = "Starboy",
            artists = listOf(Artist("The Weeknd")),
        )

        vm.toggleFavorite(testSong)
        testDispatcher.scheduler.runCurrent()

        assertTrue(fakeRepo.favoritesList.any { it.id == "fav-1" })

        vm.createPlaylist("Night Drive")
        testDispatcher.scheduler.runCurrent()

        assertTrue(fakeRepo.playlistsList.any { it.name == "Night Drive" })
    }

    @Test
    fun testYouTubeAuthenticationState() {
        val vm = createViewModel()
        assertFalse(vm.isLoggedIn.value)

        val cookieSample = "SID=abc123; SAPISID=xyz987; SSID=something"
        vm.onYouTubeCookiesCaptured(cookieSample)

        assertTrue(vm.isLoggedIn.value)
        assertFalse(vm.showYouTubeLoginDialog.value)

        vm.logoutYouTube()
        assertFalse(vm.isLoggedIn.value)
    }

    @Test
    fun testUpdateChecker() = testScope.runTest {
        val update = UpdateInfo(
            versionName = "1.1.0",
            versionCode = 2,
            changelog = "Added new equalizer presets",
            downloadUrl = "https://example.com/qmusic.apk",
        )
        fakeUpdater.updateToReturn = update

        val vm = createViewModel(backgroundScope)
        vm.checkForUpdates()
        testDispatcher.scheduler.runCurrent()

        assertEquals(update, vm.updateInfo.value)
        assertNotNull(vm.updateMessage.value)
        assertTrue(vm.updateMessage.value!!.contains("1.1.0"))
    }
}
