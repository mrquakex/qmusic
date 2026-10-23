package com.qmusic.engine

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.cipher.FaradayCipherEngine
import com.qmusic.engine.extractor.ExtractorStreamResolver
import com.qmusic.engine.extractor.createExtractorStreamResolver
import com.qmusic.engine.models.Album
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.ArtistPage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionPage
import com.qmusic.engine.models.Lyrics
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.ExplorePage
import com.qmusic.engine.models.HomePage
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SearchSuggestion
import com.qmusic.engine.models.ITAG
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResult
import com.qmusic.engine.models.SongInfo
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.VideoSource
import com.qmusic.engine.models.Thumbnail
import com.qmusic.engine.models.YouTubeClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * High-level YouTube Music facade providing search, song metadata, and stream URL resolution.
 */
class YouTube(
    private val ytmusic: Ytmusic = Ytmusic(),
    private val cipherEngine: FaradayCipherEngine = FaradayCipherEngine(),
) {
    private companion object {
        /** 100 songs per page: 30 pages covers libraries of up to ~3000 liked songs. */
        const val LIKED_MAX_PAGES = 30

        /** The home feed ends after about six pages; this is only a safety stop. */
        const val HOME_MAX_PAGES = 10

        val STREAM_CLIENTS = listOf(
            YouTubeClient.ANDROID_VR,
            YouTubeClient.ANDROID_MUSIC,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.IOS,
        )
    }

    private val extractor: ExtractorStreamResolver? by lazy { createExtractorStreamResolver() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Search YouTube Music for tracks, albums, artists, or playlists.
     */
    suspend fun search(query: String, filter: SearchFilter? = null): Result<SearchResult> =
        runCatching {
            val response = ytmusic.search(query = query, params = filter?.value)
            val text = response.bodyAsText()
            val root = json.parseToJsonElement(text).jsonObject

            val items = mutableListOf<SongItem>()
            var continuation: String? = null

            // Traverse tabbedSearchResultsRenderer -> tabs -> tabRenderer -> content -> sectionListRenderer -> contents
            val sectionList = root["contents"]
                ?.jsonObject?.get("tabbedSearchResultsRenderer")
                ?.jsonObject?.get("tabs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("tabRenderer")
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("sectionListRenderer")
                ?.jsonObject?.get("contents")
                ?.jsonArray

            sectionList?.forEach { sectionElement ->
                val shelf = sectionElement.jsonObject["musicShelfRenderer"]?.jsonObject
                val contents = shelf?.get("contents")?.jsonArray
                contents?.forEach { itemElement ->
                    val renderer = itemElement.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject
                    if (renderer != null) {
                        parseSongItem(renderer)?.let { items.add(it) }
                    }
                }
                if (continuation == null) {
                    continuation = shelf?.get("continuations")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("nextContinuationData")
                        ?.jsonObject?.get("continuation")
                        ?.jsonPrimitive?.contentOrNull
                }
            }

            // Fallback for search response if structured differently
            if (items.isEmpty()) {
                extractAllSongItems(root, items)
            }

            SearchResult(items = items, continuation = continuation)
        }

    /**
     * Applies (or clears, with null) the signed-in account to every Innertube request. Without this the
     * session cookies are captured but never sent, so YouTube Music keeps answering as an anonymous user.
     */
    fun setAuth(auth: YouTubeAuth?) {
        ytmusic.auth = auth
    }

    /**
     * Sets the Innertube request language and country context (e.g. "tr" -> hl=tr, gl=TR; "en" -> hl=en, gl=US).
     */
    fun setLanguage(code: String) {
        ytmusic.locale = when (code) {
            "en" -> com.qmusic.engine.models.YouTubeLocale(gl = "US", hl = "en")
            else -> com.qmusic.engine.models.YouTubeLocale(gl = "TR", hl = "tr")
        }
    }

    /**
     * The YouTube Music home page: personalised when an account is set via [setAuth], the generic
     * charts-style shelves otherwise. Follows up to [maxContinuations] extra pages of shelves.
     */
    /**
     * The home feed, handed over page by page so the first shelves can be shown while the rest still load.
     * [params] is the mood chip being applied ("Energize"); null is the plain feed.
     */
    suspend fun home(
        params: String? = null,
        maxContinuations: Int = HOME_MAX_PAGES,
        onPage: suspend (HomePage) -> Unit,
    ): Result<Unit> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val first = json.parseToJsonElement(ytmusic.browse("FEmusic_home", params = params).bodyAsText()).jsonObject
            onPage(HomePage(BrowseParser.homeChips(first), BrowseParser.homeSections(first)))
            var token = BrowseParser.homeContinuation(first)
            var pages = 0
            while (token != null && pages < maxContinuations) {
                val next = json.parseToJsonElement(ytmusic.browse(continuation = token).bodyAsText()).jsonObject
                onPage(HomePage(emptyList(), BrowseParser.homeSectionsFromContinuation(next)))
                token = BrowseParser.homeContinuationToken(next)
                pages++
            }
        }

    /** Autocomplete for the search box; for a signed-in account this includes its own past searches. */
    suspend fun searchSuggestions(input: String): Result<List<SearchSuggestion>> =
        runCatching {
            if (input.isBlank()) return@runCatching emptyList()
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val root = json.parseToJsonElement(ytmusic.searchSuggestions(input).bodyAsText()).jsonObject
            BrowseParser.searchSuggestions(root)
        }

    /**
     * Songs that sound like [videoId] (YouTube Music's "radio"), without the seed itself. Used to keep
     * playback going after a song that was started on its own.
     */
    suspend fun radio(videoId: String): Result<List<SongItem>> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val response = ytmusic.next(
                videoId = videoId,
                playlistId = "RDAMVM$videoId",
                params = "wAEB",
                client = YouTubeClient.WEB_REMIX,
            )
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            BrowseParser.upNext(root).filter { it.id != videoId }
        }

    /** The music video of [videoId] (picture, plus a separate sound track when needed), or null when none plays. */
    suspend fun resolveVideoSource(videoId: String, maxHeight: Int = 720): Result<VideoSource?> =
        runCatching { extractor?.videoSource(videoId, maxHeight) }

    /** Search grouped into songs, albums, artists and playlists (or just one group with a [filter]). */
    suspend fun searchPage(query: String, filter: SearchFilter? = null): Result<SearchResultsPage> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val response = ytmusic.search(query = query, params = filter?.value?.takeIf { it.isNotEmpty() })
            BrowseParser.searchPage(json.parseToJsonElement(response.bodyAsText()).jsonObject)
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val page = BrowseParser.artistPage(json.parseToJsonElement(ytmusic.browse(browseId).bodyAsText()).jsonObject)
            if (page.channelId == null && browseId.startsWith("UC")) page.copy(channelId = browseId) else page
        }

    /**
     * How loud YouTube measured the song, in dB against its reference (positive = louder): what normalisation
     * corrects. Null when the answer has no measurement.
     */
    suspend fun loudnessDb(videoId: String): Result<Float?> =
        runCatching {
            val root = json.parseToJsonElement(ytmusic.player(videoId).bodyAsText()).jsonObject
            root["playerConfig"]?.jsonObject?.get("audioConfig")?.jsonObject?.get("loudnessDb")
                ?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        }

    /** An Explore page: "FEmusic_new_releases", "FEmusic_charts", "FEmusic_moods_and_genres", or one mood with [params]. */
    suspend fun explore(browseId: String, params: String? = null): Result<ExplorePage> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            BrowseParser.explorePage(json.parseToJsonElement(ytmusic.browse(browseId, params = params).bodyAsText()).jsonObject)
        }

    /**
     * The signed-in account's library page [browseId]: "FEmusic_liked_playlists", "FEmusic_liked_albums" or
     * "FEmusic_library_corpus_artists" (subscriptions). Empty while signed out.
     */
    suspend fun library(browseId: String): Result<List<CollectionItem>> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) return@runCatching emptyList()
            BrowseParser.libraryItems(json.parseToJsonElement(ytmusic.browse(browseId).bodyAsText()).jsonObject)
        }

    /** Adds a song to one of the account's playlists ([playlistBrowseId] as listed, "VL..."). */
    suspend fun addToPlaylist(playlistBrowseId: String, videoId: String): Result<Unit> =
        editPlaylist(playlistBrowseId, buildJsonObject { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", videoId) })

    /** Takes a song out of an owned playlist; [setVideoId] is its place in the list (from the playlist page). */
    suspend fun removeFromPlaylist(playlistBrowseId: String, videoId: String, setVideoId: String): Result<Unit> =
        editPlaylist(
            playlistBrowseId,
            buildJsonObject { put("action", "ACTION_REMOVE_VIDEO"); put("removedVideoId", videoId); put("setVideoId", setVideoId) },
        )

    /** Moves a song of an owned playlist to just before [beforeSetVideoId] (to the end when null). */
    suspend fun movePlaylistItem(playlistBrowseId: String, setVideoId: String, beforeSetVideoId: String?): Result<Unit> =
        editPlaylist(
            playlistBrowseId,
            buildJsonObject {
                put("action", "ACTION_MOVE_VIDEO_BEFORE")
                put("setVideoId", setVideoId)
                beforeSetVideoId?.let { put("movedSetVideoIdSuccessor", it) }
            },
        )

    private suspend fun editPlaylist(playlistBrowseId: String, action: JsonObject): Result<Unit> =
        runCatching {
            check(ytmusic.auth?.isLoggedIn == true) { "Not signed in" }
            val response = ytmusic.editPlaylist(playlistBrowseId.removePrefix("VL"), listOf(action))
            check(response.status.value in 200..299) { "YouTube Music answered ${response.status.value}" }
            check("STATUS_SUCCEEDED" in response.bodyAsText()) { "YouTube Music did not change it" }
        }

    /** Creates a private playlist on the account; returns its browse id ("VL..."). */
    suspend fun createPlaylist(title: String, videoIds: List<String> = emptyList()): Result<String> =
        runCatching {
            check(ytmusic.auth?.isLoggedIn == true) { "Not signed in" }
            val root = json.parseToJsonElement(ytmusic.createPlaylist(title, videoIds).bodyAsText()).jsonObject
            val id = root["playlistId"]?.jsonPrimitive?.contentOrNull ?: error("YouTube Music did not create it")
            "VL$id"
        }

    suspend fun deletePlaylist(playlistBrowseId: String): Result<Unit> =
        runCatching {
            check(ytmusic.auth?.isLoggedIn == true) { "Not signed in" }
            val response = ytmusic.deletePlaylist(playlistBrowseId.removePrefix("VL"))
            check(response.status.value in 200..299) { "YouTube Music answered ${response.status.value}" }
        }

    /** Songs, albums and artists like [videoId] (the player's "Related" tab). */
    suspend fun related(videoId: String): Result<List<HomeSection>> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val next = json.parseToJsonElement(ytmusic.next(videoId = videoId, client = YouTubeClient.WEB_REMIX).bodyAsText()).jsonObject
            val browseId = BrowseParser.relatedBrowseId(next) ?: return@runCatching emptyList()
            BrowseParser.relatedSections(json.parseToJsonElement(ytmusic.browse(browseId).bodyAsText()).jsonObject)
        }

    /** Subscribes the signed-in account to an artist's channel, or ends the subscription. */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        runCatching {
            check(ytmusic.auth?.isLoggedIn == true) { "Not signed in" }
            val response = ytmusic.subscribe(channelId, subscribed)
            check(response.status.value in 200..299) { "YouTube Music answered ${response.status.value}" }
        }

    /** Unsynchronised lyrics, or null when YouTube Music has none for this song. */
    suspend fun lyrics(videoId: String): Result<Lyrics?> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val next = json.parseToJsonElement(
                ytmusic.next(videoId = videoId, client = YouTubeClient.WEB_REMIX).bodyAsText(),
            ).jsonObject
            val browseId = BrowseParser.lyricsBrowseId(next) ?: return@runCatching null
            BrowseParser.lyrics(json.parseToJsonElement(ytmusic.browse(browseId).bodyAsText()).jsonObject)
        }

    /**
     * Lyrics that move with the song (from the open LRCLIB service), or null when there are none for it.
     * They are matched on title, artist and length, so a different recording is not picked up by mistake.
     */
    suspend fun syncedLyrics(title: String, artist: String, durationSeconds: Int?): Result<Lyrics?> =
        runCatching {
            val query = buildString {
                append("https://lrclib.net/api/get?track_name=").append(title.encodeURLParameter())
                append("&artist_name=").append(artist.encodeURLParameter())
                if (durationSeconds != null && durationSeconds > 0) append("&duration=").append(durationSeconds)
            }
            val body = ytmusic.fetchText(query) ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject
            val synced = root["syncedLyrics"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val lines = LrcParser.parse(synced)
            if (lines.isEmpty()) return@runCatching null
            Lyrics(text = lines.joinToString("\n") { it.text }, source = "LRCLIB", lines = lines)
        }

    /** The signed-in account's "Liked music" playlist (empty when nobody is signed in). */
    suspend fun likedSongs(): Result<List<SongItem>> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) return@runCatching emptyList()
            collection("VLLM", maxContinuations = LIKED_MAX_PAGES).getOrThrow().songs
        }

    /** Likes ([liked] = true) or un-likes a song on the signed-in account. */
    suspend fun setLiked(videoId: String, liked: Boolean): Result<Unit> =
        runCatching {
            check(ytmusic.auth?.isLoggedIn == true) { "Not signed in" }
            val response = ytmusic.like(videoId, liked)
            check(response.status.value in 200..299) { "YouTube Music answered ${response.status.value}" }
        }

    /** The tracks of a playlist (`VL...` id) or album (`MPREb_...` id). */
    suspend fun collection(browseId: String, maxContinuations: Int = 4): Result<CollectionPage> =
        runCatching {
            if (ytmusic.auth?.isLoggedIn != true) ytmusic.ensureVisitorData()
            val root = json.parseToJsonElement(ytmusic.browse(browseId).bodyAsText()).jsonObject
            var page = BrowseParser.collectionPage(root)
            var token = BrowseParser.playlistContinuation(root)
            var pages = 0
            while (token != null && pages < maxContinuations) {
                val next = json.parseToJsonElement(ytmusic.browse(continuation = token).bodyAsText()).jsonObject
                page = page.copy(songs = page.songs + BrowseParser.collectionContinuationTracks(next))
                token = BrowseParser.collectionContinuationToken(next)
                pages++
            }
            page
        }

    /**
     * Fetch metadata and statistics for a given video ID.
     */
    suspend fun getSongInfo(videoId: String): Result<SongInfo> =
        runCatching {
            // First check player response for high-reliability videoDetails
            val playerResp = ytmusic.player(videoId = videoId, client = YouTubeClient.ANDROID_VR)
            val playerText = playerResp.bodyAsText()
            val playerRoot = json.parseToJsonElement(playerText).jsonObject
            val videoDetails = playerRoot["videoDetails"]?.jsonObject

            if (videoDetails != null) {
                val title = videoDetails["title"]?.jsonPrimitive?.contentOrNull
                val author = videoDetails["author"]?.jsonPrimitive?.contentOrNull
                val authorId = videoDetails["channelId"]?.jsonPrimitive?.contentOrNull
                val description = videoDetails["description"]?.jsonPrimitive?.contentOrNull
                val viewCount = videoDetails["viewCount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val authorThumbnail = videoDetails["thumbnail"]?.jsonObject
                    ?.get("thumbnails")?.jsonArray?.lastOrNull()
                    ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

                SongInfo(
                    videoId = videoId,
                    title = title,
                    author = author,
                    authorId = authorId,
                    authorThumbnail = authorThumbnail,
                    description = description,
                    viewCount = viewCount,
                )
            } else {
                // Fallback to next response
                val nextResp = ytmusic.next(videoId = videoId)
                val nextText = nextResp.bodyAsText()
                val nextRoot = json.parseToJsonElement(nextText).jsonObject
                parseSongInfoFromNext(videoId, nextRoot)
            }
        }

    /**
     * Resolve playable audio stream URLs with deciphered signatures and deobfuscated n-parameters.
     */
    suspend fun resolveStreamUrls(videoId: String): Result<List<AudioStream>> {
        // The extractor tracks YouTube's PO-token and cipher changes; the Innertube path below is the fallback.
        val extracted = extractor?.let { resolver ->
            runCatching { resolver.audioStreams(videoId) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.sortedByDescending { it.bitrate }
        }
        if (extracted != null) return Result.success(extracted)
        return resolveStreamUrlsInnertube(videoId)
    }

    private suspend fun resolveStreamUrlsInnertube(videoId: String): Result<List<AudioStream>> =
        runCatching {
            val playerInfo = cipherEngine.playerInfo()
            val signatureTimestamp = playerInfo?.signatureTimestamp ?: 20000
            val playerId = playerInfo?.playerId

            // ANDROID_VR first: its URLs are not PO-token gated, so ranges past the first MiB still work.
            // IOS URLs return 403 beyond ~1 MiB, so it is only a last-resort fallback.
            var root: JsonObject = JsonObject(emptyMap())
            var streamingData: JsonObject? = null
            for (client in STREAM_CLIENTS) {
                val response = ytmusic.player(
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp,
                    client = client,
                )
                root = json.parseToJsonElement(response.bodyAsText()).jsonObject
                streamingData = root["streamingData"]?.jsonObject
                if (streamingData != null) break
            }

            if (streamingData == null) {
                val reason = root["playabilityStatus"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException("No streamingData found for video $videoId: ${reason ?: "unknown reason"}")
            }

            val formats = mutableListOf<JsonObject>()
            streamingData["adaptiveFormats"]?.jsonArray?.forEach {
                (it as? JsonObject)?.let { fmt -> formats.add(fmt) }
            }
            streamingData["formats"]?.jsonArray?.forEach {
                (it as? JsonObject)?.let { fmt -> formats.add(fmt) }
            }

            val audioStreams = mutableListOf<AudioStream>()

            for (format in formats) {
                val itag = format["itag"]?.jsonPrimitive?.intOrNull ?: continue
                val mimeType = format["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isAudio = ITAG.isAudio(itag) || mimeType.startsWith("audio/")
                if (!isAudio) continue

                val bitrate = format["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
                val contentLength = format["contentLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val directUrl = format["url"]?.jsonPrimitive?.contentOrNull
                val signatureCipher = format["signatureCipher"]?.jsonPrimitive?.contentOrNull
                    ?: format["cipher"]?.jsonPrimitive?.contentOrNull

                val resolvedUrl = when {
                    directUrl != null -> {
                        val n = extractNParameter(directUrl)
                        if (n != null) {
                            val solvedN = cipherEngine.deobfuscate(
                                signature = null,
                                nParameter = n,
                                playerId = playerId,
                            ).second ?: n
                            replaceNParameter(directUrl, solvedN)
                        } else {
                            directUrl
                        }
                    }

                    signatureCipher != null -> {
                        val parsedCipher = parseCipherString(signatureCipher)
                        val cipherUrl = parsedCipher["url"] ?: continue
                        val s = parsedCipher["s"]
                        val sp = parsedCipher["sp"] ?: "sig"

                        val (solvedSig, solvedN) = cipherEngine.deobfuscate(
                            signature = s,
                            nParameter = extractNParameter(cipherUrl),
                            playerId = playerId,
                        )

                        var url = cipherUrl
                        if (solvedN != null) {
                            url = replaceNParameter(url, solvedN)
                        }
                        if (solvedSig != null) {
                            val separator = if (url.contains("?")) "&" else "?"
                            url = "$url$separator$sp=$solvedSig"
                        }
                        url
                    }

                    else -> null
                }

                if (resolvedUrl != null) {
                    audioStreams.add(
                        AudioStream(
                            itag = itag,
                            url = resolvedUrl,
                            bitrate = bitrate,
                            mimeType = mimeType,
                            contentLength = contentLength,
                            isDrm = false,
                        ),
                    )
                }
            }

            // Sort with highest bitrate first
            audioStreams.sortedByDescending { it.bitrate }
        }

    private fun parseSongItem(renderer: JsonObject): SongItem? {
        val videoId = renderer["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: renderer["navigationEndpoint"]?.jsonObject?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: renderer["overlay"]?.jsonObject?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
                ?.get("content")?.jsonObject?.get("musicPlayButtonRenderer")?.jsonObject
                ?.get("playNavigationEndpoint")?.jsonObject?.get("watchEndpoint")?.jsonObject
                ?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: return null

        val flexColumns = renderer["flexColumns"]?.jsonArray
        val firstColRuns = flexColumns?.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray

        val title = firstColRuns?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

        val secondColRuns = flexColumns?.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray

        val artists = mutableListOf<Artist>()
        var album: Album? = null
        var duration: Int? = null

        secondColRuns?.forEach { runElement ->
            val runObj = runElement.jsonObject
            val text = runObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val browseId = runObj["navigationEndpoint"]?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseId")?.jsonPrimitive?.contentOrNull

            when {
                browseId?.startsWith("UC") == true || browseId?.startsWith("FEmusic_library_privately_owned_artist") == true -> {
                    artists.add(Artist(name = text, id = browseId))
                }
                browseId?.startsWith("MPREb_") == true || browseId?.startsWith("FEmusic_library_privately_owned_release") == true -> {
                    album = Album(name = text, id = browseId)
                }
                text.contains(":") -> {
                    duration = parseDuration(text)
                }
            }
        }

        // Thumbnails
        val thumbnailList = mutableListOf<Thumbnail>()
        val thumbs = renderer["thumbnail"]?.jsonObject?.get("musicThumbnailRenderer")?.jsonObject
            ?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
        thumbs?.forEach { tElement ->
            val tObj = tElement.jsonObject
            val url = tObj["url"]?.jsonPrimitive?.contentOrNull
            val width = tObj["width"]?.jsonPrimitive?.intOrNull
            val height = tObj["height"]?.jsonPrimitive?.intOrNull
            if (url != null) {
                thumbnailList.add(Thumbnail(url, width, height))
            }
        }

        return SongItem(
            id = videoId,
            title = title,
            artists = artists,
            album = album,
            duration = duration,
            thumbnail = thumbnailList.lastOrNull()?.url,
            thumbnails = thumbnailList,
        )
    }

    private fun extractAllSongItems(element: JsonElement, results: MutableList<SongItem>) {
        when (element) {
            is JsonObject -> {
                val renderer = element["musicResponsiveListItemRenderer"]?.jsonObject
                if (renderer != null) {
                    parseSongItem(renderer)?.let { results.add(it) }
                } else {
                    element.values.forEach { extractAllSongItems(it, results) }
                }
            }
            is JsonArray -> {
                element.forEach { extractAllSongItems(it, results) }
            }
            else -> {}
        }
    }

    private fun parseSongInfoFromNext(videoId: String, root: JsonObject): SongInfo {
        var title: String? = null
        var author: String? = null
        var authorId: String? = null

        val results = root["contents"]?.jsonObject
            ?.get("twoColumnWatchNextResults")?.jsonObject
            ?.get("results")?.jsonObject
            ?.get("results")?.jsonObject
            ?.get("contents")?.jsonArray

        results?.forEach { item ->
            val primary = item.jsonObject["videoPrimaryInfoRenderer"]?.jsonObject
            if (primary != null) {
                title = primary["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            }
            val secondary = item.jsonObject["videoSecondaryInfoRenderer"]?.jsonObject
            if (secondary != null) {
                val owner = secondary["owner"]?.jsonObject?.get("videoOwnerRenderer")?.jsonObject
                author = owner?.get("title")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                authorId = owner?.get("navigationEndpoint")?.jsonObject
                    ?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
            }
        }

        return SongInfo(
            videoId = videoId,
            title = title,
            author = author,
            authorId = authorId,
        )
    }

    private fun parseDuration(text: String): Int? {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private fun parseCipherString(cipher: String): Map<String, String> =
        cipher.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = decodeUrlComponent(parts[0])
            val value = if (parts.size > 1) decodeUrlComponent(parts[1]) else ""
            key to value
        }

    private fun extractNParameter(url: String): String? {
        val regex = Regex("""[?&]n=([^&]+)""")
        return regex.find(url)?.groupValues?.getOrNull(1)?.let { decodeUrlComponent(it) }
    }

    private fun replaceNParameter(url: String, newN: String): String {
        val regex = Regex("""([?&]n=)([^&]+)""")
        return regex.replace(url) { matchResult ->
            "${matchResult.groupValues[1]}$newN"
        }
    }

    private fun decodeUrlComponent(s: String): String {
        val bytes = ByteArray(s.length)
        var count = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    bytes[count++] = byteVal.toByte()
                    i += 3
                    continue
                }
            } else if (c == '+') {
                bytes[count++] = ' '.code.toByte()
                i++
                continue
            }
            bytes[count++] = c.code.toByte()
            i++
        }
        return bytes.decodeToString(0, count)
    }
}
