package com.qmusic.engine

import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.ArtistItem
import com.qmusic.engine.models.ArtistPage
import com.qmusic.engine.models.Lyrics
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.CollectionPage
import com.qmusic.engine.models.HomeChip
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SectionStyle
import com.qmusic.engine.models.SearchSuggestion
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.Thumbnail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsers for the YouTube Music `browse` and `get_search_suggestions` responses.
 *
 * Every lookup is null-tolerant on purpose: the responses are undocumented and differ between
 * anonymous and signed-in sessions, so a missing node means "skip this entry", never a crash.
 */
internal object BrowseParser {

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = current.obj()?.get(key)
        return current
    }

    private fun JsonElement?.runs(): List<JsonObject> =
        path("runs").arr()?.mapNotNull { it.obj() } ?: emptyList()

    private fun JsonElement?.text(): String = runs().joinToString("") { it["text"].str().orEmpty() }

    // ---- Home ----------------------------------------------------------------------------------

    /** The shelves of the first home page. */
    fun homeSections(root: JsonObject): List<HomeSection> {
        val list = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs")
            .arr()?.firstOrNull()
            .path("tabRenderer", "content", "sectionListRenderer")
        return sections(list.path("contents").arr())
    }

    fun homeContinuation(root: JsonObject): String? {
        val list = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs")
            .arr()?.firstOrNull()
            .path("tabRenderer", "content", "sectionListRenderer")
        return continuationToken(list)
    }

    fun homeSectionsFromContinuation(root: JsonObject): List<HomeSection> =
        sections(root.path("continuationContents", "sectionListContinuation", "contents").arr())

    fun homeContinuationToken(root: JsonObject): String? =
        continuationToken(root.path("continuationContents", "sectionListContinuation"))

    private fun continuationToken(container: JsonElement?): String? =
        container.path("continuations").arr()?.firstOrNull()
            .path("nextContinuationData", "continuation").str()

    /** The mood filters above the first shelf ("Energize", "Relax", ...). */
    fun homeChips(root: JsonObject): List<HomeChip> {
        val list = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs")
            .arr()?.firstOrNull()
            .path("tabRenderer", "content", "sectionListRenderer")
        return list.path("header", "chipCloudRenderer", "chips").arr().orEmpty().mapNotNull { chip ->
            val renderer = chip.path("chipCloudChipRenderer")
            val text = renderer.path("text").text()
            val params = renderer.path("navigationEndpoint", "browseEndpoint", "params").str()
            if (text.isBlank() || params == null) null else HomeChip(text, params)
        }
    }

    private fun sections(contents: JsonArray?): List<HomeSection> =
        contents.orEmpty().mapNotNull { element ->
            element.path("musicCarouselShelfRenderer").obj()?.let(::carouselSection)
                ?: element.path("musicShelfRenderer").obj()?.let(::listSection)
        }

    /** A horizontal shelf: cards, videos or (for "Quick picks") song rows. */
    private fun carouselSection(shelf: JsonObject): HomeSection? {
        val header = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer")
        val title = header.path("title").text()
        if (title.isBlank()) return null

        val songs = mutableListOf<SongItem>()
        val collections = mutableListOf<CollectionItem>()
        var rows = 0
        var wide = 0
        shelf["contents"].arr().orEmpty().forEach { item ->
            item.path("musicResponsiveListItemRenderer").obj()?.let { renderer ->
                track(renderer)?.let {
                    songs.add(it)
                    rows++
                }
            }
            item.path("musicTwoRowItemRenderer").obj()?.let { renderer ->
                val song = twoRowSong(renderer)
                if (song != null) {
                    songs.add(song)
                    if (renderer["aspectRatio"].str()?.endsWith("RECTANGLE_16_9") == true) wide++
                } else {
                    twoRowCollection(renderer)?.let(collections::add)
                }
            }
        }
        if (songs.isEmpty() && collections.isEmpty()) return null

        val strapline = header.path("strapline").text().takeIf { it.isNotBlank() }
        val total = songs.size + collections.size
        val style = when {
            rows > 0 && collections.isEmpty() -> SectionStyle.ROWS
            // Shelves made of clips ("Music videos for you") are drawn wide; "Listen again" keeps square covers.
            wide > 0 && wide * 5 >= total * 4 && strapline == null -> SectionStyle.VIDEOS
            else -> SectionStyle.CARDS
        }
        return HomeSection(
            title = title,
            songs = songs,
            collections = collections,
            strapline = strapline,
            avatar = thumbnails(header.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")).lastOrNull()?.url,
            style = style,
            canPlayAll = header.path("moreContentButton", "buttonRenderer", "navigationEndpoint", "watchEndpoint") != null,
        )
    }

    /** A vertical list shelf ("Long tracks"): drawn as song rows. */
    private fun listSection(shelf: JsonObject): HomeSection? {
        val title = shelf["title"].text()
        if (title.isBlank()) return null
        val songs = shelf["contents"].arr().orEmpty().mapNotNull { it.path("musicResponsiveListItemRenderer").obj()?.let(::track) }
        if (songs.isEmpty()) return null
        return HomeSection(title = title, songs = songs, style = SectionStyle.ROWS)
    }

    private fun twoRowSong(renderer: JsonObject): SongItem? {
        val videoId = renderer.path("navigationEndpoint", "watchEndpoint", "videoId").str() ?: return null
        val subtitleRuns = renderer["subtitle"].runs()
        val artists = subtitleRuns.mapNotNull { run ->
            val id = run.path("navigationEndpoint", "browseEndpoint", "browseId").str()
            if (id?.startsWith("UC") == true) Artist(run["text"].str().orEmpty(), id) else null
        }
        val thumbs = thumbnails(renderer.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        return SongItem(
            id = videoId,
            title = renderer["title"].text(),
            artists = artists.ifEmpty { subtitleRuns.firstOrNull()?.get("text").str()?.let { listOf(Artist(it)) } ?: emptyList() },
            thumbnail = thumbs.lastOrNull()?.url,
            thumbnails = thumbs,
            videoType = videoTypeOf(renderer),
        )
    }

    private fun twoRowCollection(renderer: JsonObject): CollectionItem? {
        val endpoint = renderer.path("navigationEndpoint", "browseEndpoint").obj() ?: return null
        val browseId = endpoint["browseId"].str() ?: return null
        val pageType = endpoint.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()
        val kind = when (pageType) {
            "MUSIC_PAGE_TYPE_PLAYLIST" -> CollectionKind.PLAYLIST
            "MUSIC_PAGE_TYPE_ALBUM" -> CollectionKind.ALBUM
            "MUSIC_PAGE_TYPE_ARTIST" -> CollectionKind.ARTIST
            else -> return null // channels and podcasts have no page in the app yet
        }
        val thumbs = thumbnails(renderer.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        return CollectionItem(
            browseId = browseId,
            title = renderer["title"].text(),
            subtitle = renderer["subtitle"].text().takeIf { it.isNotBlank() },
            thumbnail = thumbs.lastOrNull()?.url,
            kind = kind,
        )
    }

    // ---- Playlist / album page -----------------------------------------------------------------

    fun collectionPage(root: JsonObject): CollectionPage {
        val micro = root.path("microformat", "microformatDataRenderer")
        val title = micro.path("title").str().orEmpty()
        // "Album • Eminem" / "Playlist • Külüstür Hafıza": whatever follows the bullet names the owner.
        val owner = micro.path("description").str()?.substringAfter('•', "")?.trim()?.takeIf { it.isNotEmpty() }
        val thumb = thumbnails(micro.path("thumbnail", "thumbnails")).lastOrNull()?.url

        val sectionContents = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").arr()
        val songs = sectionContents.orEmpty().flatMap { section ->
            val shelf = section.path("musicPlaylistShelfRenderer").obj() ?: section.path("musicShelfRenderer").obj()
            shelf?.get("contents").arr().orEmpty().mapNotNull { item ->
                item.path("musicResponsiveListItemRenderer").obj()?.let { track(it, owner?.let { name -> listOf(Artist(name)) }, thumb) }
            }
        }
        return CollectionPage(title = title, subtitle = owner, thumbnail = thumb, songs = songs)
    }

    fun collectionContinuationTracks(root: JsonObject): List<SongItem> =
        root.path("continuationContents", "musicPlaylistShelfContinuation", "contents").arr().orEmpty().mapNotNull { item ->
            item.path("musicResponsiveListItemRenderer").obj()?.let { track(it) }
        }

    fun collectionContinuationToken(root: JsonObject): String? =
        continuationToken(root.path("continuationContents", "musicPlaylistShelfContinuation"))

    fun playlistContinuation(root: JsonObject): String? {
        val shelfContainer = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
        return continuationToken(shelfContainer)
    }

    private fun videoTypeOfEndpoint(navigationEndpoint: JsonElement?): String? =
        navigationEndpoint.path(
            "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType",
        ).str()

    private fun videoTypeOf(owner: JsonElement?): String? = videoTypeOfEndpoint(owner.path("navigationEndpoint"))

    // ---- Track rows ----------------------------------------------------------------------------

    /**
     * A `musicResponsiveListItemRenderer` row. Album rows carry no artwork or artist column, so the
     * page-level values are passed in as fallbacks.
     */
    fun track(
        renderer: JsonObject,
        fallbackArtists: List<Artist>? = null,
        fallbackThumbnail: String? = null,
    ): SongItem? {
        val flex = renderer["flexColumns"].arr().orEmpty()
        fun columnRuns(index: Int) =
            flex.getOrNull(index).path("musicResponsiveListItemFlexColumnRenderer", "text").runs()

        val titleRuns = columnRuns(0)
        val videoId = renderer.path("playlistItemData", "videoId").str()
            ?: titleRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").str() }
            ?: return null

        val subtitleRuns = columnRuns(1)
        val artists = subtitleRuns.mapNotNull { run ->
            val id = run.path("navigationEndpoint", "browseEndpoint", "browseId").str()
            if (id?.startsWith("UC") == true) Artist(run["text"].str().orEmpty(), id) else null
        }.ifEmpty { plainArtist(subtitleRuns) }

        val duration = renderer["fixedColumns"].arr()?.firstOrNull()
            .path("musicResponsiveListItemFixedColumnRenderer", "text").text()
            .let(::parseDuration)
            ?: columnRuns(1).firstNotNullOfOrNull { run -> run["text"].str()?.takeIf(DURATION::matches)?.let(::parseDuration) }

        val thumbs = thumbnails(renderer.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val videoType = titleRuns.firstNotNullOfOrNull { videoTypeOf(it) }
            ?: videoTypeOfEndpoint(
                renderer.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint"),
            )
        return SongItem(
            id = videoId,
            title = titleRuns.joinToString("") { it["text"].str().orEmpty() },
            artists = artists.ifEmpty { fallbackArtists.orEmpty() },
            duration = duration,
            thumbnail = thumbs.lastOrNull()?.url ?: fallbackThumbnail,
            thumbnails = thumbs,
            videoType = videoType,
        )
    }

    private val NOT_AN_ARTIST = Regex("""(?i)(song|video|single|album|ep|playlist|artist|\d[\d.,]*\s?[kmb]?\s+(plays|views|monthly audience|subscribers).*)""")

    /**
     * Search rows print "Song • Artist • Album • 3:30" with plain text, no link, for artists YouTube Music
     * has no page for (or when signed out); the first piece that is not a label, count or duration is the artist.
     */
    private fun plainArtist(runs: List<JsonObject>): List<Artist> {
        val name = runs.mapNotNull { it["text"].str()?.trim() }
            .firstOrNull { it.isNotEmpty() && it != "•" && !DURATION.matches(it) && !NOT_AN_ARTIST.matches(it) }
        return if (name == null) emptyList() else listOf(Artist(name))
    }

    private val DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")

    // ---- Search results ------------------------------------------------------------------------

    fun searchPage(root: JsonObject): SearchResultsPage {
        val sections = root.path("contents", "tabbedSearchResultsRenderer", "tabs").arr()?.firstOrNull()
            .path("tabRenderer", "content", "sectionListRenderer", "contents").arr().orEmpty()

        var top: ArtistItem? = null
        val songs = LinkedHashMap<String, SongItem>()
        val albums = LinkedHashMap<String, CollectionItem>()
        val artists = LinkedHashMap<String, ArtistItem>()
        val playlists = LinkedHashMap<String, CollectionItem>()

        // The top-result card lists its own artist's songs without repeating the artist on each row.
        var cardArtists: List<Artist>? = null

        fun addRow(renderer: JsonObject) {
            track(renderer, cardArtists)?.let { songs.putIfAbsent(it.id, it); return }
            val endpoint = renderer.path("navigationEndpoint", "browseEndpoint").obj() ?: return
            val browseId = endpoint["browseId"].str() ?: return
            val pageType = endpoint.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()
            val flex = renderer["flexColumns"].arr().orEmpty()
            fun column(index: Int) = flex.getOrNull(index)
                .path("musicResponsiveListItemFlexColumnRenderer", "text").text()
            val title = column(0)
            val subtitle = column(1).takeIf { it.isNotBlank() }
            val thumb = thumbnails(renderer.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")).lastOrNull()?.url
            when (pageType) {
                "MUSIC_PAGE_TYPE_ALBUM" -> albums.putIfAbsent(browseId, CollectionItem(browseId, title, subtitle, thumb, CollectionKind.ALBUM))
                "MUSIC_PAGE_TYPE_PLAYLIST" -> playlists.putIfAbsent(browseId, CollectionItem(browseId, title, subtitle, thumb, CollectionKind.PLAYLIST))
                "MUSIC_PAGE_TYPE_ARTIST" -> artists.putIfAbsent(browseId, ArtistItem(browseId, title, subtitle, thumb))
            }
        }

        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    val row = element["musicResponsiveListItemRenderer"].obj()
                    if (row != null) addRow(row) else element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> {}
            }
        }

        sections.forEach { section ->
            val card = section.path("musicCardShelfRenderer").obj()
            if (card != null) {
                val endpoint = card["title"].runs().firstOrNull().path("navigationEndpoint", "browseEndpoint")
                val id = endpoint.path("browseId").str()
                val type = endpoint.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()
                if (id != null && type == "MUSIC_PAGE_TYPE_ARTIST") {
                    top = ArtistItem(
                        browseId = id,
                        name = card["title"].text(),
                        subtitle = card["subtitle"].text().takeIf { it.isNotBlank() },
                        thumbnail = thumbnails(card.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")).lastOrNull()?.url,
                    ).also { artists.putIfAbsent(it.browseId, it) }
                }
                cardArtists = top?.let { listOf(Artist(it.name, it.browseId)) }
                visit(card["contents"])
                cardArtists = null
            } else {
                visit(section)
            }
        }

        // The top artist is shown on its own card, so it is not repeated in the Artists list.
        top?.let { artists.remove(it.browseId) }
        return SearchResultsPage(top, songs.values.toList(), albums.values.toList(), artists.values.toList(), playlists.values.toList())
    }

    // ---- Artist page ---------------------------------------------------------------------------

    fun artistPage(root: JsonObject): ArtistPage {
        val header = root.path("header", "musicImmersiveHeaderRenderer")
        val contents = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs").arr()?.firstOrNull()
            .path("tabRenderer", "content", "sectionListRenderer", "contents").arr()

        val topSongs = contents.orEmpty().flatMap { section ->
            section.path("musicShelfRenderer", "contents").arr().orEmpty().mapNotNull { row ->
                row.path("musicResponsiveListItemRenderer").obj()?.let { track(it) }
            }
        }
        return ArtistPage(
            name = header.path("title").text(),
            thumbnail = thumbnails(header.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")).lastOrNull()?.url,
            description = header.path("description").text().takeIf { it.isNotBlank() },
            topSongs = topSongs,
            sections = sections(contents),
        )
    }

    // ---- Lyrics --------------------------------------------------------------------------------

    /** The browse id of the "Lyrics" tab of a `next` response, or null when the song has none. */
    fun lyricsBrowseId(nextRoot: JsonObject): String? =
        nextRoot.path(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer", "tabs",
        ).arr().orEmpty().firstNotNullOfOrNull { tab ->
            val renderer = tab.path("tabRenderer").obj() ?: return@firstNotNullOfOrNull null
            if (renderer["title"].str() == "Lyrics") renderer.path("endpoint", "browseEndpoint", "browseId").str() else null
        }

    fun lyrics(root: JsonObject): Lyrics? {
        val shelf = root.path("contents", "sectionListRenderer", "contents").arr().orEmpty()
            .firstNotNullOfOrNull { it.path("musicDescriptionShelfRenderer").obj() } ?: return null
        val text = shelf["description"].text().replace("\r\n", "\n").trim()
        if (text.isEmpty()) return null
        return Lyrics(text, shelf["footer"].text().takeIf { it.isNotBlank() })
    }

    // ---- Radio / up next -----------------------------------------------------------------------

    /** The auto-generated "up next" queue of a `next` response (the seed song comes first). */
    fun upNext(root: JsonObject): List<SongItem> {
        val panel = root.path(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer", "tabs",
        ).arr()?.firstOrNull()
            .path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
        return panel.path("contents").arr().orEmpty().mapNotNull { entry ->
            val renderer = entry.path("playlistPanelVideoRenderer").obj() ?: return@mapNotNull null
            val videoId = renderer["videoId"].str() ?: return@mapNotNull null
            val bylineRuns = renderer["longBylineText"].runs()
            val artists = bylineRuns.mapNotNull { run ->
                val id = run.path("navigationEndpoint", "browseEndpoint", "browseId").str()
                if (id?.startsWith("UC") == true) Artist(run["text"].str().orEmpty(), id) else null
            }
            val thumbs = thumbnails(renderer.path("thumbnail", "thumbnails"))
            SongItem(
                id = videoId,
                title = renderer["title"].text(),
                artists = artists.ifEmpty { bylineRuns.firstOrNull()?.get("text").str()?.let { listOf(Artist(it)) } ?: emptyList() },
                duration = parseDuration(renderer["lengthText"].text()),
                thumbnail = thumbs.lastOrNull()?.url,
                thumbnails = thumbs,
                videoType = videoTypeOf(renderer),
            )
        }
    }

    // ---- Search suggestions --------------------------------------------------------------------

    fun searchSuggestions(root: JsonObject): List<SearchSuggestion> =
        root["contents"].arr().orEmpty().flatMap { section ->
            section.path("searchSuggestionsSectionRenderer", "contents").arr().orEmpty().mapNotNull { entry ->
                val history = entry.path("historySuggestionRenderer").obj()
                val renderer = history ?: entry.path("searchSuggestionRenderer").obj() ?: return@mapNotNull null
                val text = renderer.path("navigationEndpoint", "searchEndpoint", "query").str()
                    ?: renderer["suggestion"].text()
                text.takeIf { it.isNotBlank() }?.let { SearchSuggestion(it, isHistory = history != null) }
            }
        }

    // ---- Shared --------------------------------------------------------------------------------

    private fun thumbnails(element: JsonElement?): List<Thumbnail> =
        element.arr().orEmpty().mapNotNull { entry ->
            val url = entry.path("url").str() ?: return@mapNotNull null
            Thumbnail(url, entry.path("width").let { (it as? JsonPrimitive)?.intOrNull }, entry.path("height").let { (it as? JsonPrimitive)?.intOrNull })
        }

    private fun parseDuration(text: String): Int? {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }
}
