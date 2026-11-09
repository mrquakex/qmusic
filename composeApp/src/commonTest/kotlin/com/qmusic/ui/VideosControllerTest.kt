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
private open class FakeVideos : VideoService {
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
    override suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) = Result.success(Unit)
    override suspend fun captions(videoId: String) = Result.success(emptyList<com.qmusic.engine.video.VideoCaption>())
    override suspend fun postComment(createToken: String, text: String) = Result.success(Unit)
    override suspend fun reportWatch(videoId: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class VideosControllerTest {
    private fun controller(service: VideoService, scope: TestScope) =
        VideosController(service, scope.backgroundScope, InMemoryAppSettings(), musicSession = { null })

    @Test
    fun theHomeFeedLoadsAndAsksForSignInWhenThereIsNoAccount() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos().apply { homeFeed = VideoFeed() }, this)
        c.home.load()
        val state = c.home.state.value
        assertTrue(state.loaded)
        // No account: a search stands in for the empty home, so there is something to watch.
        assertEquals(listOf("s1"), state.entries.map { (it as VideoEntry.Video).item.id })
    }

    @Test
    fun moreOfAFeedSkipsWhatIsAlreadyThereAndStopsWhenNothingNewComes() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.home.load()
        c.home.loadMore()
        // a, b and c, plus one search result: a home with few new videos is topped up from a search.
        assertEquals(4, c.home.state.value.entries.size)
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
        // Back leaves the page but the video goes on small, until it is closed.
        assertEquals("two", c.watch.value?.video?.id)
        assertTrue(!c.pop())
        c.closeWatch()
        assertNull(c.watch.value)
    }

    @Test
    fun whenAVideoEndsTheFirstOfUpNextPlays() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(object : VideoService by FakeVideos() {
            override suspend fun watch(videoId: String) =
                Result.success(VideoDetails(id = videoId, title = "T", related = VideoFeed(listOf(video("n1"), video("n2")))))
        }, this)
        c.open(VideoItem("one", "One"))
        c.playNext()
        assertEquals("n1", c.watch.value?.video?.id)
        assertEquals(1, c.routes.value.size)
    }

    @Test
    fun searchesAreRememberedNewestFirstWithoutRepeats() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.submitSearch("one")
        c.submitSearch("two")
        c.submitSearch("ONE")
        assertEquals(listOf("ONE", "two"), c.searchHistory.value)
        c.forgetSearch("two")
        assertEquals(listOf("ONE"), c.searchHistory.value)
        c.clearSearches()
        assertTrue(c.searchHistory.value.isEmpty())
    }

    @Test
    fun shortsOpenFullScreenAtTheTappedClipAndStopAnyVideo() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.open(VideoItem("v", "V"))
        val clips = listOf(VideoItem("a", "A", isShort = true), VideoItem("b", "B", isShort = true))
        c.openShorts(clips, 1)
        assertNull(c.watch.value)
        assertEquals(1, c.shorts.value.index)
        assertEquals(listOf<VideoRoute>(VideoRoute.Shorts), c.routes.value)
    }

    @Test
    fun aListOpenedInOrderGoesThroughItsVideos() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        val list = listOf(VideoItem("a", "A"), VideoItem("b", "B"), VideoItem("c", "C"))
        c.openFrom(list, 0)
        assertEquals("a", c.watch.value?.video?.id)
        c.playNext()
        assertEquals("b", c.watch.value?.video?.id)
        assertEquals(1, c.watch.value?.queueIndex)
        c.playNext()
        assertEquals("c", c.watch.value?.video?.id)
        assertTrue(c.playPrevious())
        assertEquals("b", c.watch.value?.video?.id)
        assertEquals(1, c.routes.value.size)
    }

    @Test
    fun aVideoLeftHalfWayIsPickedUpAgainButNotAShortOneOrAnAlmostFinishedOne() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeVideos(), this)
        c.savePosition("long", 300_000, 1_200_000)
        assertEquals(300_000, c.resumeFor("long"))
        c.savePosition("short", 30_000, 60_000)
        assertEquals(0, c.resumeFor("short"))
        c.savePosition("long", 1_190_000, 1_200_000)
        assertEquals(0, c.resumeFor("long"))
    }

    @Test
    fun theQualityStartsAtWhatTheConnectionAllowsAndStepsDownWhenAsked() = runTest(UnconfinedTestDispatcher()) {
        val settings = InMemoryAppSettings()
        var wifi = true
        val c = VideosController(FakeVideos(), backgroundScope, settings, musicSession = { null }, isUnmetered = { wifi })
        c.setWifiQuality("1080")
        c.setMobileQuality("360")
        c.open(VideoItem("one", "One"))
        assertEquals(1080, c.watch.value?.quality?.height)
        c.stepDownQuality()
        assertEquals(720, c.watch.value?.quality?.height)
        wifi = false
        c.open(VideoItem("two", "Two"))
        // Nothing is as low as 360 here: the lowest there is plays.
        assertEquals(720, c.watch.value?.quality?.height)
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
