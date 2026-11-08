package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.player.NoOpPlayerController
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.ScreenTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The previews of "For you" borrow the player; leaving the page gives it back as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForYouSessionTest {

    private val testScope = TestScope(StandardTestDispatcher())
    private val player = NoOpPlayerController()

    private fun createViewModel(scope: kotlinx.coroutines.CoroutineScope) = MainViewModel(
        playerController = player,
        repository = FakeMusicRepository(),
        youTubeAuth = YouTubeAuth(),
        updateChecker = FakeUpdateChecker(),
        externalScope = scope,
        autoLoadHome = false,
        autoRefillQueue = false,
    )

    private fun song(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist")), duration = 200)

    @Test
    fun leavingForYouPutsTheEarlierMusicBackPaused() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        val earlier = listOf(song("a"), song("b"), song("c"))
        vm.play(earlier[1], earlier)
        player.pause()

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        assertEquals("clip1", vm.currentTrack.value?.id)

        vm.selectTab(ScreenTab.HOME)

        // The preview is gone from the player (so from the mini player), and what was there before is back.
        assertEquals("b", vm.currentTrack.value?.id)
        assertEquals(earlier, player.queue.value)
        assertEquals(1, player.queueIndex.value)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun musicThatWasPlayingKeepsPlayingAfterThePreviews() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        vm.play(song("a"))
        assertTrue(player.isPlaying.value)

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        vm.playSample(song("clip2"))
        vm.selectTab(ScreenTab.LIBRARY)

        assertEquals("a", vm.currentTrack.value?.id)
        assertTrue(player.isPlaying.value)
    }

    @Test
    fun withNothingPlayingBeforeNothingIsLeftInThePlayer() = testScope.runTest {
        val vm = createViewModel(backgroundScope)

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        vm.selectTab(ScreenTab.SEARCH)

        assertNull(vm.currentTrack.value)
        assertTrue(player.queue.value.isEmpty())
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun playingAPreviewInFullKeepsItAfterLeaving() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        vm.play(song("a"))

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        vm.playSampleFully(song("clip1"))
        vm.selectTab(ScreenTab.HOME)

        // It is the music now: it stays, and the full player was opened for it.
        assertEquals("clip1", vm.currentTrack.value?.id)
        assertTrue(vm.isPlayerExpanded.value)
    }

    @Test
    fun theSecondVisitStartsANewSession() = testScope.runTest {
        val vm = createViewModel(backgroundScope)
        vm.play(song("a"))

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        vm.selectTab(ScreenTab.HOME)
        vm.play(song("b"))

        vm.selectTab(ScreenTab.FOR_YOU)
        vm.playSample(song("clip1"))
        vm.selectTab(ScreenTab.HOME)

        // What was playing at the second visit comes back, not what played at the first.
        assertEquals("b", vm.currentTrack.value?.id)
    }
}
