package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.YouTubeLocale
import com.qmusic.engine.video.VideoAudioTrack
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoChannelPage
import com.qmusic.engine.video.VideoComment
import com.qmusic.engine.video.VideoComments
import com.qmusic.engine.video.VideoDetails
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoFeed
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoLiveChatMessage
import com.qmusic.engine.video.VideoLiveChatPage
import com.qmusic.engine.video.VideoQuality
import com.qmusic.engine.video.VideoSearchFilter
import com.qmusic.engine.video.VideoService
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.ui.videos.DownloadState
import com.qmusic.ui.videos.DownloadedVideo
import com.qmusic.ui.videos.SkipSegment
import com.qmusic.ui.videos.VideoDownloads
import com.qmusic.ui.videos.VideoStrings
import com.qmusic.ui.videos.VideosController
import com.qmusic.ui.videos.buildTranslatedCaption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PolishFakeVideos : VideoService {
    override var auth: YouTubeAuth? = null
    override var locale: YouTubeLocale = YouTubeLocale(hl = "tr", gl = "TR")
    override val isSignedIn: Boolean get() = auth?.isLoggedIn == true
    val channelSearches = mutableListOf<Pair<String, String>>()
    var tracks: List<VideoAudioTrack> = listOf(
        VideoAudioTrack("en.4", "English", "https://audio.example/en", isOriginal = true),
        VideoAudioTrack("tr.4", "Türkçe", "https://audio.example/tr", isOriginal = false),
    )
    var subs: List<VideoCaption> = listOf(
        VideoCaption("en", "English", "https://www.youtube.com/api/timedtext?v=v1&lang=en", auto = false, mimeType = "text/vtt"),
    )

    override suspend fun home() = Result.success(VideoFeed())
    override suspend fun more(continuation: String) = Result.success(VideoFeed())
    override suspend fun subscriptions() = Result.success(VideoFeed())
    override suspend fun history() = Result.success(VideoFeed())
    override suspend fun likedVideos() = Result.success(VideoFeed())
    override suspend fun watchLater() = Result.success(VideoFeed())
    override suspend fun playlists() = Result.success(VideoFeed())
    override suspend fun subscribedChannels() = Result.success(VideoFeed())
    override suspend fun playlist(playlistId: String) = Result.success(VideoFeed())
    override suspend fun search(query: String, params: String?) = Result.success(VideoFeed())
    override suspend fun suggestions(input: String) = Result.success(emptyList<String>())
    override suspend fun watch(videoId: String) = Result.success(
        VideoDetails(
            id = videoId,
            title = "Video $videoId",
            channelId = "UC1",
            commentsToken = "tok-comments",
            isLive = videoId == "live1",
            liveChatToken = if (videoId == "live1") "tok-chat" else null,
            related = VideoFeed(listOf(VideoEntry.Video(VideoItem("next1", "Next")))),
        ),
    )
    override suspend fun comments(token: String) = Result.success(
        when (token) {
            "tok-comments" -> VideoComments(
                total = "2",
                comments = listOf(VideoComment("c1", "Ali", null, "0:42 harika", "1d", "5", replies = "1", repliesToken = "tok-rep")),
                continuation = "tok-page2",
                createToken = "tok-create",
            )
            "tok-page2" -> VideoComments(
                comments = listOf(VideoComment("c2", "Veli", null, "Devamı", "2d", null)),
                continuation = null,
            )
            "tok-rep" -> VideoComments(
                comments = listOf(VideoComment("r1", "Ayşe", null, "Katılıyorum", "1h", null)),
                continuation = null,
            )
            else -> VideoComments()
        },
    )
    override suspend fun qualities(videoId: String) = Result.success(
        listOf(
            VideoQuality(1080, 30, "https://v.example/1080", "https://audio.example/en"),
            VideoQuality(720, 30, "https://v.example/720", "https://audio.example/en"),
            VideoQuality(360, 30, "https://v.example/360", null),
        ),
    )
    override suspend fun channel(channelId: String, params: String?) = Result.success(
        VideoChannelPage(id = channelId, name = "Kanal", videos = VideoFeed(listOf(VideoEntry.Video(VideoItem("ch1", "V1"))))),
    )
    override suspend fun channelSearch(channelId: String, query: String): Result<VideoFeed> {
        channelSearches += channelId to query
        return Result.success(VideoFeed(listOf(VideoEntry.Video(VideoItem("hit1", "Found $query")))))
    }
    override suspend fun setLike(videoId: String, like: Boolean) = Result.success(Unit)
    override suspend fun setSubscribed(channelId: String, subscribed: Boolean) = Result.success(Unit)
    override suspend fun setWatchLater(videoId: String, saved: Boolean) = Result.success(Unit)
    override suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) = Result.success(Unit)
    override suspend fun captions(videoId: String) = Result.success(subs)
    override suspend fun audioTracks(videoId: String) = Result.success(tracks)
    override suspend fun liveChat(continuation: String) = Result.success(
        VideoLiveChatPage(
            messages = listOf(VideoLiveChatMessage("m1", "Can", null, "Selam!", amount = "₺50")),
            continuation = null,
            timeoutMs = 3_000L,
        ),
    )
    override suspend fun postComment(createToken: String, text: String) = Result.success(Unit)
    override suspend fun reportWatch(videoId: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPolishTest {

    @Test
    fun translatedCaptionAppendsTlangWhenTargetLanguageMissing() {
        val enOnly = listOf(
            VideoCaption("en", "English", "https://www.youtube.com/api/timedtext?v=abc&lang=en", auto = false, mimeType = "text/vtt"),
        )
        val tr = buildTranslatedCaption(enOnly, "tr", "Türkçe (otomatik çeviri)")
        assertNotNull(tr)
        assertEquals("tr-auto-tlang", tr.language)
        assertTrue(tr.url.endsWith("&tlang=tr"))

        // Native Turkish track already present -> no redundant auto-translate track.
        val withTr = enOnly + VideoCaption("tr", "Türkçe", "https://www.youtube.com/api/timedtext?v=abc&lang=tr", auto = false, mimeType = "text/vtt")
        assertNull(buildTranslatedCaption(withTr, "tr", "Türkçe (otomatik çeviri)"))
    }

    @Test
    fun loopSpeedSponsorCategoriesAndAudioTrackSwitchingWork() = runTest(UnconfinedTestDispatcher()) {
        val settings = InMemoryAppSettings()
        val service = PolishFakeVideos()
        val c = VideosController(service, backgroundScope, settings, musicSession = { null })

        // Speed persists in settings
        c.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, c.playbackSpeed.value)
        assertEquals("1.5", settings.string(VideosController.KEY_SPEED, "1.0").value)

        // SponsorBlock category toggle persists
        assertTrue("sponsor" in c.sponsorCategories.value)
        assertFalse("intro" in c.sponsorCategories.value)
        c.toggleSponsorCategory("intro")
        assertTrue("intro" in c.sponsorCategories.value)
        c.noteSkipped(SkipSegment(10_000L, 25_000L, "sponsor"))
        assertEquals(10_000L, c.lastSkipped.value?.startMs)
        c.clearLastSkipped()
        assertNull(c.lastSkipped.value)

        // Watch loads auto-translated caption + multi-language audio tracks
        c.open(VideoItem("v1", "Video 1"))
        val w = c.watch.value!!
        assertTrue(w.captions.any { it.language == "tr-auto-tlang" })
        assertEquals(2, w.audioTracks.size)
        assertEquals("English", w.audioTrack?.label)

        // Switching to Turkish dub updates active audioUrl
        val trTrack = w.audioTracks.first { it.label == "Türkçe" }
        c.selectAudioTrack(trTrack)
        assertEquals("https://audio.example/tr", c.watch.value?.quality?.audioUrl)

        // Loop prevents autoplay advance onEnded
        c.setLooping(true)
        c.onEnded()
        assertEquals("v1", c.watch.value?.video?.id)
        c.setLooping(false)
        c.onEnded()
        assertEquals("next1", c.watch.value?.video?.id)
    }

    @Test
    fun queuePlayNextAddToQueueAndDownloadQualitySelectionWork() = runTest(UnconfinedTestDispatcher()) {
        val service = PolishFakeVideos()
        val c = VideosController(service, backgroundScope, InMemoryAppSettings(), musicSession = { null })
        val started = mutableListOf<Triple<String, Int, String?>>()
        c.attachDownloads(
            object : VideoDownloads {
                override val items: StateFlow<List<DownloadedVideo>> = MutableStateFlow(emptyList())
                override fun start(item: VideoItem, quality: VideoQuality, caption: VideoCaption?) {
                    started += Triple(item.id, quality.height, caption?.language)
                }
                override fun cancel(id: String) {}
                override fun remove(id: String) {}
            },
        )

        c.open(VideoItem("v1", "First"))
        c.addToQueue(VideoItem("v3", "Third"))
        c.playNextInQueue(VideoItem("v2", "Second"))
        assertEquals(listOf("v1", "v2", "v3"), c.watch.value?.queue?.map { it.id })

        val q1080 = c.watch.value!!.qualities.first { it.height == 1080 }
        c.selectCaption(c.watch.value!!.captions.first())
        c.downloadCurrent(q1080)
        assertEquals(listOf(Triple<String, Int, String?>("v1", 1080, "en")), started)
    }

    @Test
    fun shortsCommentsPaginationRepliesChannelSearchAndLiveChatWork() = runTest(UnconfinedTestDispatcher()) {
        val service = PolishFakeVideos()
        val c = VideosController(service, backgroundScope, InMemoryAppSettings(), musicSession = { null })

        // Shorts comments load + pagination + replies
        c.openShortComments(VideoItem("sh1", "Short"))
        assertEquals(1, c.shortComments.value?.comments?.size)
        assertEquals("tok-page2", c.shortComments.value?.continuation)
        c.loadMoreShortComments()
        assertEquals(2, c.shortComments.value?.comments?.size)
        val first = c.shortComments.value!!.comments.first()
        c.loadShortReplies(first)
        assertEquals(1, c.shortComments.value?.replies?.get(first.id)?.size)
        c.hideShortReplies(first.id)
        assertFalse(c.shortComments.value!!.replies.containsKey(first.id))

        // Channel search
        c.push(com.qmusic.ui.videos.VideoRoute.Channel("UC1", "Kanal"))
        c.searchChannel("kotlin")
        assertEquals(listOf("UC1" to "kotlin"), service.channelSearches)
        assertTrue(c.channel.value?.searchActive == true)
        assertEquals(1, c.channel.value?.feed?.entries?.size)

        // Live chat
        c.open(VideoItem("live1", "Live", isLive = true))
        assertEquals("tok-chat", c.watch.value?.details?.liveChatToken)
        c.toggleLiveChat()
        assertTrue(c.watch.value?.liveChatOpen == true)
        assertEquals(1, c.watch.value?.liveChatMessages?.size)
        assertEquals("₺50", c.watch.value?.liveChatMessages?.first()?.amount)

        // Branding polish & search filter constants
        assertEquals("Video ayarları", VideoStrings.Turkish.settingsTitle)
        assertEquals("Video settings", VideoStrings.English.settingsTitle)
        assertTrue(VideoSearchFilter.SORT_UPLOAD_DATE.isNotBlank())
        assertTrue(VideoSearchFilter.DURATION_LONG.isNotBlank())
    }
}
