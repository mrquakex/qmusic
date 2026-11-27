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

    @Test
    fun tabletLayoutBreakpointsAndFeedGridChunkingWork() {
        // Phones stay single-column and single-pane
        assertFalse(com.qmusic.ui.videos.isTabletVideoWidth(412f))
        assertFalse(com.qmusic.ui.videos.isTwoPaneWatch(412f, 915f))
        assertEquals(1, com.qmusic.ui.videos.feedColumnCount(412f, compact = false))
        assertEquals(1, com.qmusic.ui.videos.feedColumnCount(412f, compact = true))

        // Portrait 8"-10" tablet: 2-column feed, left rail active
        assertTrue(com.qmusic.ui.videos.isTabletVideoWidth(700f))
        assertEquals(2, com.qmusic.ui.videos.feedColumnCount(700f, compact = false))
        assertEquals(1, com.qmusic.ui.videos.feedColumnCount(700f, compact = true))

        // Landscape 8"-11" tablet: 3-column feed, 2-column compact, 2-pane watch even when height < 600dp
        assertTrue(com.qmusic.ui.videos.isExpandedTabletWidth(1024f))
        assertTrue(com.qmusic.ui.videos.isTwoPaneWatch(800f, 520f))
        assertEquals(3, com.qmusic.ui.videos.feedColumnCount(1024f, compact = false))
        assertEquals(2, com.qmusic.ui.videos.feedColumnCount(1024f, compact = true))
        assertEquals(4, com.qmusic.ui.videos.feedColumnCount(1440f, compact = false))

        // Mixed feed chunking keeps Shorts full-width without breaking adjacent video rows
        val entries = listOf(
            VideoEntry.Video(VideoItem("v1", "One")),
            VideoEntry.Video(VideoItem("v2", "Two")),
            VideoEntry.Video(VideoItem("v3", "Three")),
            VideoEntry.Shorts(listOf(VideoItem("s1", "Short"))),
            VideoEntry.Video(VideoItem("v4", "Four")),
            VideoEntry.Video(VideoItem("v5", "Five")),
        )
        val rows2 = com.qmusic.ui.videos.groupFeedForGrid(entries, columns = 2)
        assertEquals(4, rows2.size)
        assertEquals(listOf("v1", "v2"), (rows2[0] as com.qmusic.ui.videos.FeedGridRow.VideoChunk).items.map { it.item.id })
        assertEquals(listOf("v3"), (rows2[1] as com.qmusic.ui.videos.FeedGridRow.VideoChunk).items.map { it.item.id })
        assertTrue(rows2[2] is com.qmusic.ui.videos.FeedGridRow.FullWidth)
        assertEquals(listOf("v4", "v5"), (rows2[3] as com.qmusic.ui.videos.FeedGridRow.VideoChunk).items.map { it.item.id })

        val rows3 = com.qmusic.ui.videos.groupFeedForGrid(entries, columns = 3)
        assertEquals(3, rows3.size)
        assertEquals(listOf("v1", "v2", "v3"), (rows3[0] as com.qmusic.ui.videos.FeedGridRow.VideoChunk).items.map { it.item.id })
        assertTrue(rows3[1] is com.qmusic.ui.videos.FeedGridRow.FullWidth)
        assertEquals(listOf("v4", "v5"), (rows3[2] as com.qmusic.ui.videos.FeedGridRow.VideoChunk).items.map { it.item.id })

        // Music tablet shell aligns on the same 600dp threshold as Videos, with collapsed rail on portrait tablets
        assertFalse(com.qmusic.ui.components.isMusicTabletWidth(412f))
        assertTrue(com.qmusic.ui.components.isMusicTabletWidth(600f))
        assertFalse(com.qmusic.ui.components.shouldExpandMusicSidebar(720f))
        assertTrue(com.qmusic.ui.components.shouldExpandMusicSidebar(1024f))
        assertTrue(com.qmusic.ui.components.isMusicCompactTopBar(720f))
        assertFalse(com.qmusic.ui.components.isMusicCompactTopBar(1024f))
        assertFalse(com.qmusic.ui.components.isMusicTwoPaneNowPlaying(720f))
        assertTrue(com.qmusic.ui.components.isMusicTwoPaneNowPlaying(1024f))
    }

    @Test
    fun heatmapParserDislikeStatsAndVideoPowerSettingsWork() = runTest(UnconfinedTestDispatcher()) {
        // 1. Return YouTube Dislike ratio & compact formatting (Turkish + English)
        val stats = com.qmusic.ui.videos.VideoDislikeStats(likes = 900L, dislikes = 100L)
        assertEquals(0.9f, stats.likeRatio)
        assertNull(com.qmusic.ui.videos.VideoDislikeStats(0L, 0L).likeRatio)
        assertEquals("842", com.qmusic.ui.videos.formatCompactCount(842L, english = false))
        assertEquals("12,5 B", com.qmusic.ui.videos.formatCompactCount(12_500L, english = false))
        assertEquals("12.5K", com.qmusic.ui.videos.formatCompactCount(12_500L, english = true))
        assertEquals("1,4 Mn", com.qmusic.ui.videos.formatCompactCount(1_400_000L, english = false))
        assertEquals("1.4M", com.qmusic.ui.videos.formatCompactCount(1_400_000L, english = true))

        // 2. Settings persistence for dislikes and heatmap toggles
        val settings = InMemoryAppSettings()
        val c = VideosController(PolishFakeVideos(), backgroundScope, settings, musicSession = { null })
        assertEquals("1", c.showDislikes.value)
        assertEquals("1", c.showHeatmap.value)
        c.setShowDislikes(false)
        c.setShowHeatmap(false)
        assertEquals("0", settings.string(VideosController.KEY_DISLIKES, "1").value)
        assertEquals("0", settings.string(VideosController.KEY_HEATMAP, "1").value)
    }

    @Test
    fun movieScraperParserAndExternalUrlHelpersWork() {
        val p = com.qmusic.ui.videos.MovieHtmlParser
        // Direct media URL detection
        assertTrue(p.isDirectMediaUrl("https://cdn.example.com/movie/master.m3u8?token=abc"))
        assertTrue(p.isDirectMediaUrl("https://cdn.example.com/clip.mp4"))
        assertFalse(p.isDirectMediaUrl("https://cinema.example.com/watch/matrix"))

        // Source URL building (catalog + search)
        val src = com.qmusic.ui.chat.MovieSource(
            id = "cinema",
            name = "Ev Sineması",
            baseUrl = "https://cinema.example.com/",
            searchPath = "/ara?q={query}",
            catalogPath = "/filmler",
            headers = "Referer: https://cinema.example.com/\nX-Test: 1",
        )
        assertEquals("https://cinema.example.com/filmler", p.buildSourceUrl(src, ""))
        assertEquals("https://cinema.example.com/ara?q=sci+fi", p.buildSourceUrl(src, "sci fi"))
        assertEquals(mapOf("Referer" to "https://cinema.example.com/", "X-Test" to "1"), p.parseHeaders(src.headers))

        // HTML stream, iframe, subtitle, title, poster extraction
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Interstellar &amp; Beyond" />
                <meta property="og:image" content="/posters/inter.jpg" />
              </head>
              <body>
                <video>
                  <source src="https://cdn.example.com/video_720p.mp4" />
                  <track kind="subtitles" srclang="tr" label="Türkçe" src="/subs/tr.vtt" />
                </video>
                <script>var cfg = { file: "https:\/\/cdn.example.com\/master_1080p.m3u8?sig=1" };</script>
                <iframe src="https://embed.example.com/e/123"></iframe>
              </body>
            </html>
        """.trimIndent()
        val streams = p.extractStreams(html, "https://cinema.example.com/watch/inter")
        assertEquals(2, streams.size)
        assertTrue(streams.first().videoUrl.endsWith("master_1080p.m3u8?sig=1"))
        assertEquals("https://cinema.example.com/", streams.first().headers["Referer"])
        assertEquals("Interstellar & Beyond", p.extractTitle(html))
        assertEquals("https://cinema.example.com/posters/inter.jpg", p.extractPoster(html, "https://cinema.example.com/watch/inter"))
        assertEquals(listOf("https://embed.example.com/e/123"), p.extractEmbedIframes(html, "https://cinema.example.com/watch/inter"))
        val subs = p.extractCaptions(html, "https://cinema.example.com/watch/inter")
        assertEquals(1, subs.size)
        assertEquals("https://cinema.example.com/subs/tr.vtt", subs.first().url)

        // Catalog scraping (full page with badges, pagination & genre links)
        val catalogHtml = """
            <html>
              <body>
                <nav>
                  <a href="/tur/aksiyon">Aksiyon</a>
                  <a href="/tur/bilim-kurgu">Bilim Kurgu</a>
                </nav>
                <div class="grid">
                  <a href="/film/dune" title="Dune: Part Two"><img data-src="/img/dune.jpg" alt="Dune" /><span>8.6</span><span>2024</span><span>1080p</span></a>
                  <a href="/film/inception"><img src="https://img.example.com/inc.jpg" /><h3>Inception</h3><span>8.8</span><span>2010</span></a>
                </div>
                <a rel="next" href="/sayfa/2">Sonraki</a>
              </body>
            </html>
        """.trimIndent()
        val page = p.extractCatalogPage(catalogHtml, "https://cinema.example.com/", "Ev Sineması")
        assertEquals(2, page.items.size)
        assertEquals("Dune: Part Two", page.items[0].title)
        assertEquals("https://cinema.example.com/img/dune.jpg", page.items[0].poster)
        assertEquals("8.6", page.items[0].rating)
        assertEquals("2024", page.items[0].year)
        assertEquals("1080P", page.items[0].qualityBadge)
        assertEquals("Inception", page.items[1].title)
        assertEquals("https://cinema.example.com/sayfa/2", page.nextPageUrl)
        assertEquals(listOf("Aksiyon", "Bilim Kurgu"), page.genres.map { it.first })

        // Dean Edwards p,a,c,k,e,d JS unpacker + Base64 atob() stream decoder
        val packedHtml = """
            <script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1={2:"3://4.5.6/7/8.9"};',10,10,'var|cfg|file|https|cdn|cinema|org|hls|master|m3u8'.split('|'),0,{}))
            var backup = atob("aHR0cHM6Ly9jZG4uY2luZW1hLm9yZy9maWxtXzcyMHAubXA0");
            </script>
        """.trimIndent()
        val unpackedStreams = p.extractStreams(packedHtml, "https://cinema.example.com/watch/1")
        assertEquals(2, unpackedStreams.size)
        assertEquals("https://cdn.cinema.org/hls/master.m3u8", unpackedStreams[0].videoUrl)
        assertEquals("https://cdn.cinema.org/film_720p.mp4", unpackedStreams[1].videoUrl)

        // JSON-LD metadata extraction (synopsis, year, rating)
        val ldHtml = """
            <script type="application/ld+json">
            {"@type":"Movie","name":"Dune","datePublished":"2024-03-01","description":"Paul Atreides unites with the Fremen on Arrakis.","aggregateRating":{"ratingValue":"8.6"}}
            </script>
        """.trimIndent()
        assertEquals("2024", p.extractYear(ldHtml))
        assertEquals("8.6", p.extractRating(ldHtml))
        assertTrue(p.extractSynopsis(ldHtml)!!.contains("Arrakis"))

        // HLS master playlist quality variant parsing
        val hlsMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=854x480
            480p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()
        val variants = p.parseHlsVariants(hlsMaster, "https://cdn.cinema.org/hls/master.m3u8", emptyMap())
        assertEquals(listOf(0, 1080, 720, 480), variants.map { it.height })
        assertEquals("https://cdn.cinema.org/hls/1080p/index.m3u8", variants[1].videoUrl)

        // YouTube URL helper + external ID prefix + adaptive grid columns
        assertEquals("dQw4w9WgXcQ", com.qmusic.ui.videos.extractYouTubeIdFromUrl("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals("dQw4w9WgXcQ", com.qmusic.ui.videos.extractYouTubeIdFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(com.qmusic.ui.videos.extractYouTubeIdFromUrl("https://cinema.example.com/watch/1"))
        assertEquals("https://a.com/v.m3u8", com.qmusic.ui.videos.externalUrlOf(com.qmusic.ui.videos.externalVideoId("https://a.com/v.m3u8")))
        assertEquals(2, com.qmusic.ui.videos.movieGridColumnCount(412f))
        assertEquals(3, com.qmusic.ui.videos.movieGridColumnCount(720f))
        assertEquals(5, com.qmusic.ui.videos.movieGridColumnCount(1280f))
    }
}
