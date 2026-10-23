package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.SongItem
import com.qmusic.player.DownloadController
import com.qmusic.player.NoOpPlayerController
import com.qmusic.player.RepeatMode
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeDownloads : DownloadController {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    override val downloadedIds: StateFlow<Set<String>> = _ids.asStateFlow()
    override val progress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap<String, Float>()).asStateFlow()
    override val downloadedBytes: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    val requested = mutableListOf<String>()
    var cancelled = false
    var removedAll = false

    override fun download(song: SongItem) {
        requested += song.id
    }

    override fun downloadAll(songs: List<SongItem>) {
        songs.forEach(::download)
    }

    override fun remove(songId: String) {
        _ids.value = _ids.value - songId
    }

    override fun cancelAll() {
        cancelled = true
    }

    override fun removeAll() {
        removedAll = true
        _ids.value = emptySet()
    }

    fun finish(id: String) {
        _ids.value = _ids.value + id
    }
}

/** Pages, the song menu, queue editing and downloads, with the network-facing parts left idle. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryActionsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val fakeRepo = FakeMusicRepository()
    private val playerController = NoOpPlayerController()
    private val fakeDownloads = FakeDownloads()

    private val settings = InMemoryAppSettings()

    private fun createViewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        unmetered: Boolean = true,
    ): MainViewModel =
        MainViewModel(
            playerController = playerController,
            repository = fakeRepo,
            youTubeAuth = YouTubeAuth(),
            updateChecker = FakeUpdateChecker(),
            externalScope = scope,
            autoLoadHome = false,
            autoRefillQueue = false,
            downloads = fakeDownloads,
            settings = settings,
            isUnmetered = { unmetered },
        )

    private fun song(id: String, artistId: String? = null) =
        SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist", artistId)))

    private fun playlist(id: String) = CollectionItem(browseId = id, title = "Playlist $id", kind = CollectionKind.PLAYLIST)

    @Test
    fun pagesStackAndBackPopsThem() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        assertFalse(vm.popRoute())

        vm.openCollection(playlist("VL1"))
        vm.openArtist("UC1", "Tarkan")
        assertEquals(2, vm.routes.value.size)
        assertEquals(Route.Artist("UC1", "Tarkan"), vm.routes.value.last())

        assertTrue(vm.popRoute())
        assertTrue(vm.routes.value.last() is Route.Collection)
        assertTrue(vm.popRoute())
        assertFalse(vm.popRoute())
        assertTrue(vm.routes.value.isEmpty())
    }

    @Test
    fun anArtistCardOpensTheArtistPageNotAPlaylist() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        vm.openCollection(CollectionItem("UCsezen", "Sezen Aksu", kind = CollectionKind.ARTIST))

        assertEquals(Route.Artist("UCsezen", "Sezen Aksu"), vm.routes.value.single())
    }

    @Test
    fun openingTheSamePageTwiceDoesNotStackIt() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        vm.openArtist("UC1", "Tarkan")
        vm.openArtist("UC1", "Tarkan")

        assertEquals(1, vm.routes.value.size)
        vm.clearRoutes()
        assertTrue(vm.routes.value.isEmpty())
    }

    @Test
    fun songMenuAndPlaylistPickerHandOverTheSameSong() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val song = song("a")

        vm.showSongMenu(song)
        assertEquals(song, vm.menuSong.value)

        vm.showPlaylistPicker(song)
        assertNull(vm.menuSong.value)
        assertEquals(song, vm.playlistPickerSong.value)

        vm.dismissPlaylistPicker()
        assertNull(vm.playlistPickerSong.value)
    }

    @Test
    fun creatingAPlaylistFromTheSongMenuAddsTheSong() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val song = song("a")

        vm.createPlaylistAndAdd("  Road trip  ", song)
        testDispatcher.scheduler.runCurrent()

        val created = fakeRepo.playlistsList.single()
        assertEquals("Road trip", created.name)
        assertEquals(listOf("a"), fakeRepo.playlistSongsMap[created.id]?.map { it.id })

        // A blank name creates nothing.
        vm.createPlaylistAndAdd("   ", song)
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, fakeRepo.playlistsList.size)
    }

    @Test
    fun queueEditingPlayNextAddRemoveAndSkip() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"), song("c"))
        vm.play(songs[0], songs)

        vm.playNext(song("x"))
        assertEquals(listOf("a", "x", "b", "c"), vm.queue.value.map { it.id })

        vm.addToQueue(song("y"))
        assertEquals(listOf("a", "x", "b", "c", "y"), vm.queue.value.map { it.id })

        vm.removeFromQueue(2)
        assertEquals(listOf("a", "x", "c", "y"), vm.queue.value.map { it.id })

        vm.skipToQueueIndex(3)
        assertEquals("y", vm.currentTrack.value?.id)
        assertEquals(3, vm.queueIndex.value)

        // Removing a song before the current one keeps the same song playing.
        vm.removeFromQueue(0)
        assertEquals("y", vm.currentTrack.value?.id)
        assertEquals(2, vm.queueIndex.value)
    }

    @Test
    fun playNextAndAddToQueueStartPlaybackWhenNothingPlays() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        vm.playNext(song("a"))

        assertEquals("a", vm.currentTrack.value?.id)
    }

    @Test
    fun repeatCyclesOffAllOne() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        assertEquals(RepeatMode.OFF, vm.repeatMode.value)

        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, vm.repeatMode.value)
    }

    @Test
    fun repeatAllWrapsAroundAtTheEnd() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"))
        vm.play(songs[1], songs)

        vm.next()
        assertEquals("b", vm.currentTrack.value?.id)

        vm.cycleRepeatMode()
        vm.next()
        assertEquals("a", vm.currentTrack.value?.id)
    }

    @Test
    fun shuffleAllTurnsShuffleOnAndStartsPlayback() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"), song("c"))

        vm.shuffleAll(songs)

        assertTrue(vm.shuffleEnabled.value)
        assertTrue(vm.currentTrack.value?.id in setOf("a", "b", "c"))
        vm.toggleShuffle()
        assertFalse(vm.shuffleEnabled.value)

        vm.shuffleAll(emptyList())
        assertFalse(vm.shuffleEnabled.value)
    }

    @Test
    fun sleepTimerIsForwardedToThePlayer() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        vm.setSleepTimer(15)
        assertTrue(vm.sleepTimer.value.active)
        assertEquals(15 * 60_000L, vm.sleepTimer.value.remainingMs)

        vm.setSleepAtEndOfTrack()
        assertTrue(vm.sleepTimer.value.atEndOfTrack)

        vm.setSleepTimer(0)
        assertFalse(vm.sleepTimer.value.active)
    }

    @Test
    fun downloadsAreRememberedAndOnlyListedOnceTheFileIsComplete() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.downloadedSongs.collect { } }
        val song = song("a")

        vm.download(song)
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("a"), fakeDownloads.requested)
        // Requested but not finished yet: the library does not show it.
        assertTrue(vm.downloadedSongs.value.isEmpty())

        fakeDownloads.finish("a")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("a"), vm.downloadedSongs.value.map { it.id })

        vm.removeDownload(song)
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.downloadedSongs.value.isEmpty())
    }

    @Test
    fun removingASongFromAPlaylistAndRenamingAndDeleting() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val id = fakeRepo.createPlaylist("Old")
        fakeRepo.addSongToPlaylist(id, song("a"))
        fakeRepo.addSongToPlaylist(id, song("b"))
        vm.openLocalPlaylist(id, "Old")

        vm.removeSongFromPlaylist(id, song("a"))
        vm.renamePlaylist(id, "New")
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("b"), fakeRepo.playlistSongsMap[id]?.map { it.id })
        assertEquals("New", fakeRepo.playlistsList.single().name)
        assertEquals(Route.LocalPlaylist(id, "New"), vm.routes.value.single())

        vm.deletePlaylist(id)
        testDispatcher.scheduler.runCurrent()
        assertTrue(fakeRepo.playlistsList.isEmpty())
        assertTrue(vm.routes.value.isEmpty())
    }

    @Test
    fun theClipIsOnlyShownWhenAskedForAndOnlyWhileTheFullPlayerIsOpen() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        // The picture comes first: opening the player does not start any clip.
        vm.setPlayerExpanded(true)
        assertFalse(vm.videoMode.value)

        vm.setVideoPreferred(true)
        assertTrue(vm.videoMode.value)

        // Collapsing drops back to audio so nothing is downloaded or drawn for a picture nobody sees.
        vm.setPlayerExpanded(false)
        assertFalse(vm.videoMode.value)
    }

    @Test
    fun theVideoChoiceSticksAcrossClosingAndReopeningThePlayer() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        vm.setPlayerExpanded(true)
        vm.setVideoPreferred(true)

        vm.setPlayerExpanded(false)
        vm.setPlayerExpanded(true)
        assertTrue(vm.videoMode.value)

        vm.setVideoPreferred(false)
        assertFalse(vm.videoMode.value)
    }

    @Test
    fun autoDownloadSavesEveryLikedSongOnce() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        fakeRepo.toggleFavorite(song("a"))
        fakeRepo.toggleFavorite(song("b"))
        testDispatcher.scheduler.runCurrent()
        assertTrue(fakeDownloads.requested.isEmpty())

        vm.setAutoDownloadLiked(true)
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("a", "b"), fakeDownloads.requested.toSet())

        // A song liked later is picked up too, and nothing already handed over is queued twice.
        fakeRepo.toggleFavorite(song("c"))
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("a", "b", "c").sorted(), fakeDownloads.requested.sorted())
    }

    @Test
    fun autoDownloadWaitsForWifiWhenWifiOnly() = testScope.runTest {
        val vm = createViewModel(backgroundScope, unmetered = false)
        fakeRepo.toggleFavorite(song("a"))
        vm.setAutoDownloadLiked(true)
        testDispatcher.scheduler.runCurrent()
        assertTrue(fakeDownloads.requested.isEmpty())

        // With "Wi-Fi only" off, mobile data is fine.
        vm.setWifiOnly(false)
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("a"), fakeDownloads.requested)
    }

    @Test
    fun downloadAllSkipsWhatIsAlreadyOnTheDevice() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        fakeDownloads.finish("a")

        vm.downloadAll(listOf(song("a"), song("b")))
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("b"), fakeDownloads.requested)
    }

    @Test
    fun removeAllDownloadsClearsTheDeviceAndTheLibraryFlags() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.downloadedSongs.collect { } }
        vm.download(song("a"))
        fakeDownloads.finish("a")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("a"), vm.downloadedSongs.value.map { it.id })

        vm.removeAllDownloads()
        testDispatcher.scheduler.runCurrent()

        assertTrue(fakeDownloads.removedAll)
        assertTrue(vm.downloadedSongs.value.isEmpty())
        assertTrue(fakeRepo.downloadedFlowIdsForTest().isEmpty())
    }
}
