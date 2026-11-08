package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.YouTubeLocale
import com.qmusic.engine.video.VideoChannelPage
import com.qmusic.engine.video.VideoComments
import com.qmusic.engine.video.VideoDetails
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoFeed
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import com.qmusic.engine.video.VideoService
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.ui.videos.VideoRoute
import com.qmusic.ui.videos.VideosController
import com.qmusic.ui.videos.pickDefault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun video(id: String) = VideoEntry.Video(VideoItem(id = id, title = "t$id"))

/** A service that answers from lists, so the section's own logic can be checked without the network. */
private class FakeVideos : VideoService {
    override var auth: YouTubeAuth? = null
    override var locale: YouTubeLocale = YouTubeLocale()
    override val isSignedIn: Boolean get() = auth?.isLoggedIn == true
    var homeFeed = VideoFeed(listOf(video("a"), video("b")), continuation = "next")
    var nextFeed = VideoFeed(listOf(video("b"), video("c")), continuation = "more")
    var emptyNext = VideoFeed(emptyList(), continuation = "again")
    val searched = mutableListOf<Pair<String, String?>>()

    override suspend fun home() = Result.success(homeFeed)
    override suspend fun more(continuation: String) = Result.success(if (continuation == "more") emptyNext else nextFeed)
    override suspend fun subscriptions() = Result.success(VideoFeed())
    override suspend fun history() = Result.success(VideoFeed())
    override suspend fun likedVideos() = Result.success(VideoFeed())
    override suspend fun watchLater() = Result.success(VideoFeed())
    override suspend fun playlists() = Result.success(VideoFeed())
    override suspend fun subscribedChannels() = Result.success(VideoFeed())
    override suspend fun playlist(playlistId: String) = Result.success(VideoFeed())
    override suspend fun search(query: String, params: String?): Result<VideoFeed> {
        searched += query to params
        return Result.success(VideoFeed(listOf(video("s1"))))
    }
    override suspend fun suggestions(input: String) = Result.success(listOf("$input one"))
    override suspend fun watch(videoId: String) = Result.success(VideoDetails(id = videoId, title = "T", channelId = "UC1"))
    override suspend fun comments(token: String) = Result.success(VideoComments())
    override suspend fun qualities(videoId: String) = Result.success(listOf(VideoQuality(1080, 30, "v", "a"), VideoQuality(720, 30, "v", "a")))
    override suspend fun channel(channelId: String, params: String?) = Result.failure<VideoChannelPage>(IllegalStateException("x"))
    override suspend fun setLike(videoId: String, like: Boolean) = Result.success(Unit)
    override suspend fun setSubscribed(channelId: String, subscribed: Boolean) = Result.success(Unit)
    override suspend fun setWatchLater(videoId: String, saved: Boolean) = Result.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class VideosControllerTest {
    private fun controller(service: FakeVideos, scope: TestScope) =
        VideosController(service, scope.backgroundScope, InMemoryAppSettings(), musicSession = { null })

    @Test
    fun theHomeFeedLoadsAndAsksForSignInWhenThereIsNoAccount() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos().apply { homeFeed = VideoFeed() }, this)
        c.home.load()
        val state = c.home.state.value
        assertTrue(state.loaded)
        assertTrue(state.signInNeeded)
    }

    @Test
    fun moreOfAFeedSkipsWhatIsAlreadyThereAndStopsWhenNothingNewComes() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.home.load()
        c.home.loadMore()
        assertEquals(3, c.home.state.value.entries.size)
        assertEquals("more", c.home.state.value.continuation)
        c.home.loadMore()
        // A page with nothing new ends the list instead of asking again and again.
        assertNull(c.home.state.value.continuation)
    }

    @Test
    fun aSearchOpensAResultsPageAndAFilterAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val service = FakeVideos()
        val c = controller(service, this)
        c.submitSearch("  tame ")
        assertEquals(listOf<VideoRoute>(VideoRoute.Search("tame")), c.routes.value)
        c.setFilter("EgIQAQ%3D%3D")
        assertEquals(listOf("tame" to null, "tame" to "EgIQAQ%3D%3D"), service.searched)
        // Searching again from the results replaces the page instead of piling up.
        c.submitSearch("other")
        assertEquals(1, c.routes.value.size)
    }

    @Test
    fun aVideoOpenedFromAVideoTakesItsPlaceAndBackStopsIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.open(VideoItem("one", "One"))
        c.open(VideoItem("two", "Two"))
        assertEquals(1, c.routes.value.size)
        assertEquals("two", c.watch.value?.video?.id)
        assertEquals(720, c.watch.value?.quality?.height)
        assertTrue(c.pop())
        assertNull(c.watch.value)
        assertTrue(!c.pop())
    }

    @Test
    fun likingNeedsAnAccountAndAsksForOne() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.open(VideoItem("one", "One"))
        c.toggleLike()
        assertTrue(c.watch.value!!.signInPrompt)
        assertTrue(!c.watch.value!!.liked)
    }

    @Test
    fun theDefaultQualityIs720OrTheBestBelowIt() {
        val q = listOf(VideoQuality(1080, 60, "v", "a"), VideoQuality(720, 30, "v", "a"), VideoQuality(480, 30, "v", "a"))
        assertEquals(720, q.pickDefault()?.height)
        assertEquals(1080, listOf(VideoQuality(1080, 30, "v", "a")).pickDefault()?.height)
        assertEquals(0, listOf(VideoQuality(0, 0, "h", null, isLive = true), VideoQuality(720, 30, "v", "a")).pickDefault()?.height)
    }
}
