package com.qmusic.engine.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video side's parsers, on real YouTube answers (see [VideoFixtures]). */
class VideoParserTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun List<VideoEntry>.videos() = filterIsInstance<VideoEntry.Video>().map { it.item }

    @Test
    fun searchResultsKeepTheirOrderAndGroupTheShortsTogether() {
        val feed = VideoParser.feed(parse(VideoFixtures.SEARCH))

        // video, video, [short, short], video: the two Shorts are one row, where they were.
        assertEquals(4, feed.entries.size)
        assertTrue(feed.entries[2] is VideoEntry.Shorts)
        assertEquals(2, (feed.entries[2] as VideoEntry.Shorts).items.size)

        val first = (feed.entries[0] as VideoEntry.Video).item
        assertEquals("xnP7qKxwzjg", first.id)
        assertEquals("Tame Impala - Dracula (Official Video)", first.title)
        assertEquals("Tame Impala", first.channelName)
        assertEquals("UCdI8MAC5HoPJSJ4zrgDDI-Q", first.channelId)
        assertEquals("3:54", first.duration)
        assertEquals("1 yıl önce", first.published)
        assertEquals("86.046.058 görüntüleme", first.views)
        assertNotNull(first.thumbnail)
        assertFalse(first.isLive)

        val short = (feed.entries[2] as VideoEntry.Shorts).items.first()
        assertEquals("Tv5a4XHOOhU", short.id)
        assertTrue(short.isShort)
        assertEquals("172 B görüntüleme", short.views)
        assertTrue(short.title.startsWith("Tame Impala's"))
        assertNotNull(short.thumbnail)

        assertNotNull(feed.continuation)
    }

    @Test
    fun theWatchPageGivesTheVideoItsChannelAndWhatComesNext() {
        val details = VideoParser.details(parse(VideoFixtures.WATCH), "XbGs_qK2PQA")!!

        assertEquals("Eminem - Rap God (Explicit)", details.title)
        assertEquals("1.581.574.562 görüntüleme", details.views)
        assertEquals("27 Kas 2013", details.date)
        assertEquals("20 Mn", details.likes)
        assertEquals("UCfM3zsQsOnfWNUppiycmBuw", details.channelId)
        assertEquals("67,5 Mn abone", details.subscribers)
        assertEquals(false, details.subscribed)
        assertNotNull(details.channelAvatar)
        assertTrue(details.description!!.startsWith("Download Eminem's 'MMLP2'"))
        assertNotNull(details.commentsToken)

        // What comes next: cards with their channel line, and a way to ask for more.
        val related = details.related.entries.videos()
        assertEquals(3, related.size)
        assertTrue(related.all { it.title.isNotBlank() && it.thumbnail != null && it.channelName != null && it.views != null })
        assertNotNull(details.related.continuation)
        // The comments' token is the comments', not the related list's.
        assertTrue(details.commentsToken != details.related.continuation)
    }

    @Test
    fun aChannelPageHasItsHeaderItsTabsAndItsVideos() {
        val page = VideoParser.channel(parse(VideoFixtures.CHANNEL), "UCdI8MAC5HoPJSJ4zrgDDI-Q")!!

        assertEquals("Tame Impala", page.name)
        assertEquals("UCdI8MAC5HoPJSJ4zrgDDI-Q", page.id)
        assertEquals("@TameImpala", page.handle)
        assertEquals("3,76 Mn abone", page.subscribers)
        assertEquals("150 video", page.videoCount)
        assertTrue(page.description!!.startsWith("Tame Impala. Clips, music, videos."))
        assertNotNull(page.avatar)
        assertTrue(page.tabs.any { it.title == "Videolar" && it.params == "EgZ2aWRlb3PyBgQKAjoA" })
        assertTrue(page.tabs.size >= 5)

        // A channel's own list has no channel line on its cards (the page is the channel).
        val videos = page.videos.entries.videos()
        assertEquals(3, videos.size)
        val first = videos.first()
        assertEquals("pVU5RBJT7ek", first.id)
        assertEquals("Tame Impala, Fcukers - Loser (Fcukers Remix - Official Audio)", first.title)
        assertEquals("301 B görüntüleme", first.views)
        assertEquals("1 ay önce", first.published)
        assertEquals("4:30", first.duration)
        assertNull(first.channelName)
        assertNotNull(page.videos.continuation)
    }

    @Test
    fun commentsAreReadFromTheEntityUpdates() {
        val page = VideoParser.comments(parse(VideoFixtures.COMMENTS))

        assertEquals("1.303.964 Yorum", page.total)
        assertEquals(3, page.comments.size)
        val first = page.comments.first()
        assertEquals("UgwOhfK5-pQ4Hm-_MKt4AaABAg", first.id)
        assertEquals("@willdabeast8509", first.author)
        assertEquals("Eminem: Yes\n\nMe: Do you rap faster than time?", first.text)
        assertEquals("6 yıl önce (düzenlendi)", first.published)
        assertEquals("351 B", first.likes)
        assertEquals("928", first.replies)
        assertNotNull(first.avatar)
        assertTrue(page.comments.all { it.text.isNotBlank() })
        // The page's own "more" entry, not one of a comment's replies.
        assertNotNull(page.continuation)
        assertTrue(page.continuation != "")
    }

    @Test
    fun theHomeFeedReadsCardsShortsFiltersAndTheNextPage() {
        val feed = VideoParser.feed(parse(VideoFixtures.HOME))

        assertEquals(listOf("Tümü", "Müzik"), feed.chips.map { it.text })
        assertEquals(listOf("CHIP_ALL", "CHIP_MUSIC"), feed.chips.map { it.token })
        assertEquals(listOf(true, false), feed.chips.map { it.selected })

        // card, card, [Shorts], card, card
        assertEquals(5, feed.entries.size)
        assertTrue(feed.entries[2] is VideoEntry.Shorts)
        assertEquals("HOME_NEXT", feed.continuation)
        val cards = feed.entries.videos()
        assertEquals(4, cards.size)
        // The cards of a feed name their channel, and carry its picture.
        assertEquals(emptyList<VideoItem>(), cards.take(2).filter { it.channelName == null || it.channelAvatar == null })
    }

    @Test
    fun aFilterChipsFeedAndItsNextPageAreRead() {
        val feed = VideoParser.feed(parse(VideoFixtures.CHIP_FEED))

        assertEquals(2, feed.entries.size)
        assertEquals("CHIP_NEXT", feed.continuation)
        assertTrue(feed.chips.isEmpty())
    }

    @Test
    fun channelsPlaylistsLiveStreamsAndReelsAreRead() {
        val entries = VideoParser.entries(parse(VideoFixtures.MIXED))

        val channel = (entries[0] as VideoEntry.Channel).item
        assertEquals("UCdI8MAC5HoPJSJ4zrgDDI-Q", channel.id)
        assertEquals("Tame Impala", channel.name)
        // The @handle and the subscriber count are told apart whichever field YouTube put them in.
        assertEquals("@TameImpala", channel.handle)
        assertEquals("3,76 Mn abone", channel.subscribers)
        assertEquals("https://yt3.ggpht.com/a=s88", channel.avatar)
        assertEquals("Clips, music", channel.description)

        val playlist = (entries[1] as VideoEntry.Playlist).item
        assertEquals("PLabc", playlist.id)
        assertEquals("Currents", playlist.title)
        assertEquals("13", playlist.videoCount)
        assertEquals("Tame Impala", playlist.channelName)

        val live = (entries[2] as VideoEntry.Video).item
        assertTrue(live.isLive)
        assertNull(live.duration)
        assertEquals("1,2 B kişi izliyor", live.views)
        assertEquals("UCradio", live.channelId)

        val reel = (entries[3] as VideoEntry.Shorts).items.single()
        assertEquals("reel1234567", reel.id)
        assertTrue(reel.isShort)
    }

    @Test
    fun emptyAndUnknownAnswersGiveNothingInsteadOfCrashing() {
        // What an anonymous home feed really is: a sign-in nudge and no videos.
        val empty = VideoParser.feed(parse("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[{"feedNudgeRenderer":{"title":{"simpleText":"Sign in"}}}]}}}}]}}}"""))
        assertTrue(empty.isEmpty)
        assertNull(empty.continuation)
        assertTrue(VideoParser.feed(parse("{}")).isEmpty)
        assertNull(VideoParser.details(parse("{}"), "x"))
        assertNull(VideoParser.channel(parse("{}"), "x"))
        assertTrue(VideoParser.comments(parse("{}")).comments.isEmpty())
        // A card without an id or a title is left out, not shown half-empty.
        assertTrue(VideoParser.entries(parse("""{"a":[{"videoRenderer":{"title":{"simpleText":"x"}}},{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}]}""")).isEmpty())
    }
}
