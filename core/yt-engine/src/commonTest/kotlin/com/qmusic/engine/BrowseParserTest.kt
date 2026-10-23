package com.qmusic.engine

import com.qmusic.engine.models.CollectionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trimmed copies of real YouTube Music responses (anonymous session, October 2026). */
class BrowseParserTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private val homeJson = """
    {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
      "contents":[
        {"musicCarouselShelfRenderer":{
          "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Quick picks"}]}}},
          "contents":[
            {"musicResponsiveListItemRenderer":{
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/a.jpg","width":60,"height":60}]}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Beat It","navigationEndpoint":{"watchEndpoint":{"videoId":"kOn-HdEg6AQ"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Michael Jackson","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}},
                  {"text":" • "},{"text":"8M plays"}]}}}
              ]}}
          ]}},
        {"musicCarouselShelfRenderer":{
          "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Made for you"}]}}},
          "contents":[
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Chill mix"}]},
              "subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"YouTube Music"}]},
              "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/mix.jpg"}]}}},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDCLAK5uy_x","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Revival"}]},
              "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Eminem"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abc","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Some Artist"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Video Song"}]},
              "subtitle":{"runs":[{"text":"Some Singer","navigationEndpoint":{"browseEndpoint":{"browseId":"UCsinger"}}}]},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"vid12345678"}}}}
          ]}},
        {"musicTastebuilderShelfRenderer":{}}
      ],
      "continuations":[{"nextContinuationData":{"continuation":"NEXT_PAGE"}}]}}}}]}}}
    """

    @Test
    fun homeShelvesSplitIntoSongsAndCollections() {
        val sections = BrowseParser.homeSections(parse(homeJson))

        assertEquals(listOf("Quick picks", "Made for you"), sections.map { it.title })

        val quick = sections[0]
        assertEquals("kOn-HdEg6AQ", quick.songs.single().id)
        assertEquals("Michael Jackson", quick.songs.single().artists.single().name)
        assertEquals("https://img/a.jpg", quick.songs.single().thumbnail)

        val made = sections[1]
        assertEquals(listOf("VLRDCLAK5uy_x", "MPREb_abc", "UCartist"), made.collections.map { it.browseId })
        assertEquals(
            listOf(CollectionKind.PLAYLIST, CollectionKind.ALBUM, CollectionKind.ARTIST),
            made.collections.map { it.kind },
        )
        assertEquals("Playlist • YouTube Music", made.collections.first().subtitle)
        // A two-row card with a watch endpoint is a playable song.
        assertEquals("vid12345678", made.songs.single().id)
        assertEquals("Some Singer", made.songs.single().artists.single().name)
    }

    @Test
    fun homeContinuationTokenIsFound() {
        assertEquals("NEXT_PAGE", BrowseParser.homeContinuation(parse(homeJson)))
        assertNull(BrowseParser.homeContinuation(parse("""{"contents":{}}""")))
    }

    @Test
    fun continuationPagesAreParsedAsMoreShelves() {
        val json = """
        {"continuationContents":{"sectionListContinuation":{
          "contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"New releases"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Single"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_new","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}]}}],
          "continuations":[{"nextContinuationData":{"continuation":"PAGE_3"}}]}}}
        """
        val root = parse(json)
        assertEquals("New releases", BrowseParser.homeSectionsFromContinuation(root).single().title)
        assertEquals("PAGE_3", BrowseParser.homeContinuationToken(root))
    }

    @Test
    fun malformedOrEmptyResponsesYieldNothingInsteadOfCrashing() {
        assertTrue(BrowseParser.homeSections(parse("{}")).isEmpty())
        assertTrue(BrowseParser.searchSuggestions(parse("""{"contents":[{"unexpected":1}]}""")).isEmpty())
        assertTrue(BrowseParser.upNext(parse("{}")).isEmpty())
        assertTrue(BrowseParser.collectionPage(parse("{}")).songs.isEmpty())
    }

    @Test
    fun searchSuggestionsKeepHistoryApart() {
        val json = """
        {"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
          {"historySuggestionRenderer":{"suggestion":{"runs":[{"text":"kero"},{"text":"sene"}]},
            "navigationEndpoint":{"searchEndpoint":{"query":"kerosene"}}}},
          {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"kero","bold":true},{"text":"sene slowed"}]},
            "navigationEndpoint":{"searchEndpoint":{"query":"kerosene slowed"}}}},
          {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"   "}]}}}
        ]}}]}
        """
        val suggestions = BrowseParser.searchSuggestions(parse(json))

        assertEquals(listOf("kerosene", "kerosene slowed"), suggestions.map { it.text })
        assertEquals(listOf(true, false), suggestions.map { it.isHistory })
    }

    @Test
    fun playlistPageReadsTracksAndPageMetadata() {
        val json = """
        {"microformat":{"microformatDataRenderer":{"title":"karışık","description":"Playlist • Külüstür Hafıza",
          "thumbnail":{"thumbnails":[{"url":"https://img/cover.jpg","width":1200,"height":1200}]}}},
         "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{
           "contents":[{"musicPlaylistShelfRenderer":{"contents":[
             {"musicResponsiveListItemRenderer":{
               "playlistItemData":{"videoId":"Xh55tq-T1eE"},
               "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/t.jpg"}]}}},
               "flexColumns":[
                 {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"git","navigationEndpoint":{"watchEndpoint":{"videoId":"Xh55tq-T1eE"}}}]}}},
                 {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"BLOK3","navigationEndpoint":{"browseEndpoint":{"browseId":"UCblok"}}}]}}}],
               "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"2:39"}]}}}]}}
           ]}}],
           "continuations":[{"nextContinuationData":{"continuation":"MORE"}}]}}}}}
        """
        val root = parse(json)
        val page = BrowseParser.collectionPage(root)

        assertEquals("karışık", page.title)
        assertEquals("Külüstür Hafıza", page.subtitle)
        assertEquals("https://img/cover.jpg", page.thumbnail)
        val track = page.songs.single()
        assertEquals("Xh55tq-T1eE", track.id)
        assertEquals("git", track.title)
        assertEquals("BLOK3", track.artists.single().name)
        assertEquals(159, track.duration)
        assertEquals("MORE", BrowseParser.playlistContinuation(root))
    }

    @Test
    fun albumTracksFallBackToThePageArtistAndCover() {
        val json = """
        {"microformat":{"microformatDataRenderer":{"title":"Revival","description":"Album • Eminem",
          "thumbnail":{"thumbnails":[{"url":"https://img/album.jpg"}]}}},
         "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{
           "contents":[{"musicShelfRenderer":{"contents":[
             {"musicResponsiveListItemRenderer":{
               "playlistItemData":{"videoId":"ryr75N0nki0"},
               "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Walk On Water"}]}}}]}}
           ]}}]}}}}}
        """
        val track = BrowseParser.collectionPage(parse(json)).songs.single()

        assertEquals("Eminem", track.artists.single().name)
        assertEquals("https://img/album.jpg", track.thumbnail)
    }

    @Test
    fun upNextQueueKeepsOrderAndDuration() {
        val json = """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
          {"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{"videoId":"seed","title":{"runs":[{"text":"Seed"}]},
              "longBylineText":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1"}}}]},
              "lengthText":{"runs":[{"text":"3:30"}]},"thumbnail":{"thumbnails":[{"url":"https://img/s.jpg"}]}}},
            {"playlistPanelVideoRenderer":{"videoId":"next1","title":{"runs":[{"text":"Next"}]},
              "longBylineText":{"runs":[{"text":"Other"}]},"lengthText":{"runs":[{"text":"1:02:03"}]}}}
          ]}}}}}}]}}}}}
        """
        val queue = BrowseParser.upNext(parse(json))

        assertEquals(listOf("seed", "next1"), queue.map { it.id })
        assertEquals(210, queue[0].duration)
        assertEquals(3723, queue[1].duration)
        assertEquals("Other", queue[1].artists.single().name)
    }

    @Test
    fun searchPageGroupsMixedRowsAndFillsTheTopArtistsSongs() {
        val json = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicCardShelfRenderer":{
            "title":{"runs":[{"text":"Tarkan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtarkan","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]},
            "subtitle":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"31M monthly audience"}]},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/tarkan.jpg"}]}}},
            "contents":[{"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"cardSong1"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Simarik","navigationEndpoint":{"watchEndpoint":{"videoId":"cardSong1"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"3:56"}]}}}]}}]}},
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"song2"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Kuzu Kuzu","navigationEndpoint":{"watchEndpoint":{"videoId":"song2"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"Tarkan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtarkan"}}}]}}}]}}]}},
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_hop","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Hop De"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Single"},{"text":" • "},{"text":"Tarkan"},{"text":" • "},{"text":"2014"}]}}}]}},
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UCsezen","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Sezen Aksu"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}},
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLbest","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Tarkan - En iyiler"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Mert"},{"text":" • "},{"text":"20K views"}]}}}]}}]}}
        ]}}}}]}}}
        """
        val page = BrowseParser.searchPage(parse(json))

        assertEquals("UCtarkan", page.top?.browseId)
        assertEquals("Tarkan", page.top?.name)
        // The card's songs name the card's artist, and the top artist is not repeated in the artists list.
        assertEquals(listOf("cardSong1", "song2"), page.songs.map { it.id })
        assertEquals("Tarkan", page.songs[0].artists.single().name)
        assertEquals(236, page.songs[0].duration)
        assertEquals("Tarkan", page.songs[1].artists.single().name)
        assertEquals(listOf("MPREb_hop"), page.albums.map { it.browseId })
        assertEquals("Single • Tarkan • 2014", page.albums.single().subtitle)
        assertEquals(listOf("UCsezen"), page.artists.map { it.browseId })
        assertEquals(listOf("VLPLbest"), page.playlists.map { it.browseId })
        assertTrue(!page.isEmpty)
        assertTrue(BrowseParser.searchPage(parse("{}")).isEmpty)
    }

    @Test
    fun artistPageReadsTopSongsAndShelves() {
        val json = """
        {"header":{"musicImmersiveHeaderRenderer":{
           "title":{"runs":[{"text":"Tarkan"}]},
           "description":{"runs":[{"text":"A Turkish singer."}]},
           "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/a.jpg"}]}}}}},
         "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
           {"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"top1"},
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Dudu","navigationEndpoint":{"watchEndpoint":{"videoId":"top1"}}}]}}}]}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Karma"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_karma","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}]}},
           {"musicDescriptionShelfRenderer":{}}
         ]}}}}]}}}
        """
        val page = BrowseParser.artistPage(parse(json))

        assertEquals("Tarkan", page.name)
        assertEquals("A Turkish singer.", page.description)
        assertEquals("https://img/a.jpg", page.thumbnail)
        assertEquals(listOf("top1"), page.topSongs.map { it.id })
        assertEquals(listOf("Albums"), page.sections.map { it.title })
        assertEquals("MPREb_karma", page.sections.single().collections.single().browseId)
    }

    @Test
    fun lyricsTabAndTextAreRead() {
        val next = """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
          {"tabRenderer":{"title":"Up next"}},
          {"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_abc"}}}},
          {"tabRenderer":{"title":"Related","endpoint":{"browseEndpoint":{"browseId":"MPTRt_abc"}}}}]}}}}}
        """
        assertEquals("MPLYt_abc", BrowseParser.lyricsBrowseId(parse(next)))
        assertNull(BrowseParser.lyricsBrowseId(parse("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Up next"}}]}}}}}""")))

        val lyrics = """
        {"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{
          "description":{"runs":[{"text":"Line one\r\nLine two"}]},
          "footer":{"runs":[{"text":"Source: LyricFind"}]}}}]}}}
        """
        val parsed = BrowseParser.lyrics(parse(lyrics))
        assertEquals("Line one\nLine two", parsed?.text)
        assertEquals("Source: LyricFind", parsed?.source)
        assertNull(BrowseParser.lyrics(parse("{}")))
    }

    @Test
    fun videoTypeMarksClipsAndLeavesAlbumTracksAudioOnly() {
        fun row(videoId: String, type: String) = """
          {"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"$videoId"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T","navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId",
              "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}}]}}}]}}
        """
        val json = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[
          {"musicPlaylistShelfRenderer":{"contents":[
            ${row("clip", "MUSIC_VIDEO_TYPE_OMV")},
            ${row("track", "MUSIC_VIDEO_TYPE_ATV")},
            ${row("upload", "MUSIC_VIDEO_TYPE_UGC")}
          ]}}]}}}}}
        """
        val songs = BrowseParser.collectionPage(parse(json)).songs

        assertEquals(listOf(true, false, true), songs.map { it.hasVideo })
        assertEquals("MUSIC_VIDEO_TYPE_ATV", songs[1].videoType)
        // No type information at all means audio only.
        assertTrue(!com.qmusic.engine.models.SongItem(id = "x", title = "x").hasVideo)
    }
}
