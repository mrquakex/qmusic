package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.player.NoOpPlayerController
import com.qmusic.ui.viewmodel.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Queue navigation and the search box states. None of these start the debounce timer or a network call:
 * the test clock is only advanced to "now", never past the suggestion debounce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueAndSearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val fakeRepo = FakeMusicRepository()
    private val playerController = NoOpPlayerController()

    private fun createViewModel(scope: kotlinx.coroutines.CoroutineScope): MainViewModel =
        MainViewModel(
            playerController = playerController,
            repository = fakeRepo,
            youTubeAuth = YouTubeAuth(),
            updateChecker = FakeUpdateChecker(),
            externalScope = scope,
            autoLoadHome = false,
            autoRefillQueue = false,
        )

    private fun song(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist")))

    @Test
    fun playingFromAListQueuesTheWholeList() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"), song("c"))

        vm.play(songs[1], songs)

        assertEquals("b", vm.currentTrack.value?.id)
        assertEquals(songs, playerController.queue.value)
        assertEquals(1, playerController.queueIndex.value)
    }

    @Test
    fun nextAndPreviousMoveThroughTheQueue() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"), song("c"))
        vm.play(songs[0], songs)

        vm.next()
        assertEquals("b", vm.currentTrack.value?.id)
        vm.next()
        assertEquals("c", vm.currentTrack.value?.id)

        // End of the queue: next is a no-op rather than wrapping around.
        vm.next()
        assertEquals("c", vm.currentTrack.value?.id)

        vm.previous()
        assertEquals("b", vm.currentTrack.value?.id)
        vm.previous()
        assertEquals("a", vm.currentTrack.value?.id)

        // Start of the queue: previous just restarts the song.
        vm.previous()
        assertEquals("a", vm.currentTrack.value?.id)
        assertEquals(0L, vm.playbackPosition.value)
    }

    @Test
    fun playAllStartsFromTheFirstSong() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"))

        vm.playAll(songs)

        assertEquals("a", vm.currentTrack.value?.id)
        assertEquals(songs, playerController.queue.value)

        vm.playAll(emptyList())
        assertEquals("a", vm.currentTrack.value?.id)
    }

    @Test
    fun historyFollowsEverySongThatPlaysIncludingSkips() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val songs = listOf(song("a"), song("b"), song("c"))

        vm.play(songs[0], songs)
        testDispatcher.scheduler.runCurrent()
        vm.next()
        testDispatcher.scheduler.runCurrent()

        // The repository lists the most recent song first.
        assertEquals(listOf("b", "a"), fakeRepo.historyList.map { it.id })
    }

    @Test
    fun emptyBoxShowsHistoryNotResults() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.searchHistory.collect { } }
        fakeRepo.recordSearch("kerosene")
        fakeRepo.recordSearch("tarkan")
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("tarkan", "kerosene"), vm.searchHistory.value)
        assertFalse(vm.showSearchResults.value)
    }

    @Test
    fun typingSuggestsMatchingPastSearchesFirst() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.searchSuggestions.collect { } }
        fakeRepo.recordSearch("kerosene")
        fakeRepo.recordSearch("tarkan")

        vm.setSearchQuery("kero")
        testDispatcher.scheduler.runCurrent()

        val suggestions = vm.searchSuggestions.value
        assertEquals(listOf("kerosene"), suggestions.map { it.text })
        assertTrue(suggestions.single().isHistory)
        assertFalse(vm.showSearchResults.value)
    }

    @Test
    fun clearingTheBoxDropsSuggestionsAndResults() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.searchSuggestions.collect { } }
        fakeRepo.recordSearch("kerosene")
        vm.setSearchQuery("kero")
        testDispatcher.scheduler.runCurrent()

        vm.setSearchQuery("")
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.searchSuggestions.value.isEmpty())
        assertTrue(vm.searchResults.value.isEmpty())
        assertFalse(vm.showSearchResults.value)
    }

    @Test
    fun historyEntriesCanBeRemovedOneByOneOrAllAtOnce() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        backgroundScope.launch { vm.searchHistory.collect { } }
        fakeRepo.recordSearch("a")
        fakeRepo.recordSearch("b")
        testDispatcher.scheduler.runCurrent()

        vm.deleteSearchHistoryEntry("a")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("b"), vm.searchHistory.value)

        vm.clearSearchHistory()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.searchHistory.value.isEmpty())
    }
}
