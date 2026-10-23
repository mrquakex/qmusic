package com.qmusic.spotify

import com.qmusic.engine.YouTube
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SongItem
import com.qmusic.spotify.model.SpotifyPlaylist
import com.qmusic.spotify.model.SpotifyTrack
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Service interface for importing Spotify playlists and matching Spotify tracks to YouTube Music songs.
 */
interface SpotifyImporter {
    /**
     * Imports playlist metadata and track list from a Spotify playlist URL or ID.
     */
    suspend fun importPlaylistFromUrl(url: String): Result<SpotifyPlaylist>

    /**
     * Finds the best matching YouTube Music [SongItem] for a given [SpotifyTrack].
     */
    suspend fun matchTrackToYouTube(track: SpotifyTrack, youTube: YouTube): Result<SongItem>

    companion object {
        fun create(httpClient: HttpClient? = null): SpotifyImporter =
            DefaultSpotifyImporter(httpClient ?: createDefaultSpotifyHttpClient())

        fun createDefaultSpotifyHttpClient(): HttpClient =
            HttpClient {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            encodeDefaults = true
                        },
                    )
                }
            }
    }
}

/**
 * Default implementation of [SpotifyImporter] scraping public Spotify embed metadata.
 */
class DefaultSpotifyImporter(
    private val httpClient: HttpClient = SpotifyImporter.createDefaultSpotifyHttpClient(),
) : SpotifyImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun importPlaylistFromUrl(url: String): Result<SpotifyPlaylist> = runCatching {
        val playlistId = extractPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Spotify playlist ID from: $url")

        val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
        val response = httpClient.get(embedUrl) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
        }

        val html = response.bodyAsText()
        parsePlaylistFromHtml(playlistId, html)
    }

    override suspend fun matchTrackToYouTube(track: SpotifyTrack, youTube: YouTube): Result<SongItem> = runCatching {
        val artistQuery = track.artists.firstOrNull() ?: track.artist
        val query = if (artistQuery.isNotBlank()) {
            "${track.title} $artistQuery"
        } else {
            track.title
        }

        // Try SONGS filter first, fallback to ALL
        val songsResult = youTube.search(query, SearchFilter.SONGS).getOrNull()
        val candidates = if (!songsResult?.items.isNullOrEmpty()) {
            songsResult!!.items
        } else {
            val allResult = youTube.search(query, SearchFilter.ALL).getOrNull()
            if (!allResult?.items.isNullOrEmpty()) {
                allResult!!.items
            } else {
                youTube.search(track.title).getOrNull()?.items ?: emptyList()
            }
        }

        if (candidates.isEmpty()) {
            throw NoSuchElementException("No YouTube match found for track: ${track.title}")
        }

        candidates.maxByOrNull { scoreCandidate(track, it) } ?: candidates.first()
    }

    private fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        val regex = Regex("""(?:playlist[/:])([a-zA-Z0-9]+)""")
        val match = regex.find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }
        if (trimmed.matches(Regex("^[a-zA-Z0-9]{15,35}$"))) {
            return trimmed
        }
        return null
    }

    private fun parsePlaylistFromHtml(playlistId: String, html: String): SpotifyPlaylist {
        // Try __NEXT_DATA__ JSON embedded in Spotify embed page
        val nextDataRegex = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val nextDataMatch = nextDataRegex.find(html)

        if (nextDataMatch != null) {
            val jsonText = nextDataMatch.groupValues[1]
            return parseNextDataJson(playlistId, jsonText)
        }

        // Fallback: Check for base64 encoded initial-state
        val initialStateRegex = Regex("""<script id="initial-state"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val initialStateMatch = initialStateRegex.find(html)
        if (initialStateMatch != null) {
            @OptIn(ExperimentalEncodingApi::class)
            val decodedJson = Base64.Default.decode(initialStateMatch.groupValues[1].trim()).decodeToString()
            return parseInitialStateJson(playlistId, decodedJson)
        }

        throw IllegalStateException("Failed to extract playlist data from HTML for playlist $playlistId")
    }

    private fun parseNextDataJson(playlistId: String, jsonText: String): SpotifyPlaylist {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val entity = root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("state")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("entity")?.jsonObject
            ?: throw IllegalStateException("entity node not found in __NEXT_DATA__")

        val title = entity["name"]?.jsonPrimitive?.contentOrNull
            ?: entity["title"]?.jsonPrimitive?.contentOrNull
            ?: "Spotify Playlist"

        val description = entity["subtitle"]?.jsonPrimitive?.contentOrNull

        val thumbnailUrl = entity["visualIdentity"]?.jsonObject
            ?.get("image")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val tracks = mutableListOf<SpotifyTrack>()
        val trackList = entity["trackList"]?.jsonArray
        trackList?.forEach { element ->
            val trackObj = element.jsonObject
            val uri = trackObj["uri"]?.jsonPrimitive?.contentOrNull ?: ""
            val id = uri.removePrefix("spotify:track:")
            val trackTitle = trackObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val subtitle = trackObj["subtitle"]?.jsonPrimitive?.contentOrNull ?: ""
            val duration = trackObj["duration"]?.jsonPrimitive?.longOrNull

            val artists = subtitle.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (trackTitle.isNotBlank()) {
                tracks.add(
                    SpotifyTrack(
                        id = id,
                        title = trackTitle,
                        artists = artists,
                        durationMs = duration,
                    ),
                )
            }
        }

        return SpotifyPlaylist(
            id = playlistId,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            trackCount = tracks.size,
            tracks = tracks,
        )
    }

    private fun parseInitialStateJson(playlistId: String, jsonText: String): SpotifyPlaylist {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val entity = root["entity"]?.jsonObject ?: root

        val title = entity["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify Playlist"
        val description = entity["description"]?.jsonPrimitive?.contentOrNull

        val thumbnailUrl = entity["images"]?.jsonObject
            ?.get("items")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("sources")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val tracks = mutableListOf<SpotifyTrack>()
        val items = entity["trackList"]?.jsonArray
            ?: entity["items"]?.jsonArray
            ?: emptyList()

        items.forEach { element ->
            val obj = element.jsonObject
            val trackObj = obj["track"]?.jsonObject ?: obj
            val uri = trackObj["uri"]?.jsonPrimitive?.contentOrNull ?: ""
            val id = uri.removePrefix("spotify:track:")
            val trackTitle = trackObj["name"]?.jsonPrimitive?.contentOrNull
                ?: trackObj["title"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val durationMs = trackObj["duration"]?.jsonObject
                ?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
                ?: trackObj["duration"]?.jsonPrimitive?.longOrNull

            val artistNames = mutableListOf<String>()
            val artistsArray = trackObj["artists"]?.jsonObject?.get("items")?.jsonArray
                ?: trackObj["artists"]?.jsonArray
            artistsArray?.forEach { a ->
                val name = a.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    ?: a.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                if (!name.isNullOrBlank()) {
                    artistNames.add(name)
                }
            }

            if (trackTitle.isNotBlank()) {
                tracks.add(
                    SpotifyTrack(
                        id = id,
                        title = trackTitle,
                        artists = artistNames,
                        durationMs = durationMs,
                    ),
                )
            }
        }

        return SpotifyPlaylist(
            id = playlistId,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            trackCount = tracks.size,
            tracks = tracks,
        )
    }

    private fun scoreCandidate(track: SpotifyTrack, candidate: SongItem): Int {
        var score = 0
        val trackTitle = track.title.lowercase().trim()
        val candidateTitle = candidate.title.lowercase().trim()

        if (trackTitle == candidateTitle) {
            score += 50
        } else {
            val cleanCand = cleanTitle(candidateTitle)
            val cleanTrk = cleanTitle(trackTitle)
            if (cleanCand == cleanTrk && cleanCand.isNotEmpty()) {
                score += 40
            } else if (cleanCand.contains(cleanTrk) || cleanTrk.contains(cleanCand)) {
                score += 25
            }
        }

        val trackArtists = track.artists.map { it.lowercase().trim() }
        val candidateArtists = candidate.artists.map { it.name.lowercase().trim() }
        val artistMatch = trackArtists.any { ta ->
            candidateArtists.any { ca -> ca == ta || ca.contains(ta) || ta.contains(ca) }
        }
        if (artistMatch) {
            score += 30
        }

        val candDuration = candidate.duration
        if (track.durationMs != null && candDuration != null) {
            val trackSec = (track.durationMs / 1000).toInt()
            val delta = abs(candDuration - trackSec)
            when {
                delta <= 3 -> score += 25
                delta <= 7 -> score += 15
                delta <= 15 -> score += 5
                delta > 60 -> score -= 20
            }
        }

        val penalties = listOf("live", "karaoke", "instrumental", "tribute", "cover", "slowed", "reverb", "remix")
        for (unwanted in penalties) {
            if (candidateTitle.contains(unwanted) && !trackTitle.contains(unwanted)) {
                score -= 20
            }
        }

        return score
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\(feat\.[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(ft\.[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[feat\.[^\]]*\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[ft\.[^\]]*\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""- .*remaster.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(.*remaster.*\)""", RegexOption.IGNORE_CASE), "")
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .trim()
    }
}
