package com.qmusic.ui.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.withTimeoutOrNull

/** What a YouTube / YouTube Music link points at. */
sealed interface AppLink {
    data class Song(val videoId: String) : AppLink
    /** A playlist id as it appears in `list=` (no "VL" in front). */
    data class Playlist(val listId: String) : AppLink
    data class Album(val browseId: String) : AppLink
    data class Artist(val channelId: String) : AppLink

    /** open.spotify.com/track|album|playlist|artist/... (kind, Spotify id). */
    data class Spotify(val kind: String, val id: String) : AppLink

    /** Shortened Spotify link (spotify.link / spoti.fi) that redirects to open.spotify.com. */
    data class SpotifyShort(val url: String) : AppLink
}

/** Reads music.youtube.com, youtube.com, youtu.be and Spotify links, also inside shared text ("Listen to ... https://..."). */
object AppLinks {
    private val URL = Regex("https?://\\S+")
    private val SPOTIFY_KINDS = setOf("track", "album", "playlist", "artist")
    private val OPEN_SPOTIFY_REGEX = Regex("""https?://(?:www\.|m\.)?open\.spotify\.com/(?:intl-[^/"'\s<>]+/)?(track|album|playlist|artist)/([A-Za-z0-9]+)""")
    private val SPOTIFY_URI_REGEX = Regex("""spotify:(?://)?(track|album|playlist|artist)[:/]([A-Za-z0-9]+)""")
    private const val MAX_REDIRECTS = 5
    private const val SHORT_LINK_TIMEOUT_MS = 10_000L
    private const val SHORT_LINK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun parse(text: String): AppLink? {
        extractSpotifyUri(text)?.let { return it }
        val link = URL.find(text)?.value?.trimEnd('.', ',', ')', '!') ?: return null
        val rest = link.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "").substringBefore('#').split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (host == "open.spotify.com") {
            // "/intl-tr/track/ID" as well as "/track/ID"
            val parts = segments.dropWhile { it.startsWith("intl-") }
            val kind = parts.getOrNull(0)?.takeIf { it in SPOTIFY_KINDS } ?: return null
            return parts.getOrNull(1)?.let { AppLink.Spotify(kind, it) }
        }
        if (host == "spotify.link" || host == "spoti.fi") {
            if (segments.isEmpty()) return null
            val parts = segments.dropWhile { it.startsWith("intl-") }
            val kind = parts.getOrNull(0)?.takeIf { it in SPOTIFY_KINDS }
            val id = parts.getOrNull(1)
            if (kind != null && id != null) return AppLink.Spotify(kind, id)
            return AppLink.SpotifyShort(link)
        }
        if (host == "youtu.be") return segments.firstOrNull()?.takeIf(::isVideoId)?.let(AppLink::Song)
        if (host != "music.youtube.com" && host != "youtube.com") return null
        return when (segments.firstOrNull()) {
            "watch" -> query["v"]?.takeIf(::isVideoId)?.let(AppLink::Song) ?: query["list"]?.let(AppLink::Playlist)
            "shorts" -> segments.getOrNull(1)?.takeIf(::isVideoId)?.let(AppLink::Song)
            "playlist" -> query["list"]?.let(AppLink::Playlist)
            "browse" -> segments.getOrNull(1)?.let { id ->
                when {
                    id.startsWith("MPREb") -> AppLink.Album(id)
                    id.startsWith("VL") -> AppLink.Playlist(id.removePrefix("VL"))
                    id.startsWith("UC") -> AppLink.Artist(id)
                    else -> null
                }
            }
            "channel" -> segments.getOrNull(1)?.let(AppLink::Artist)
            else -> null
        }
    }

    /** Follows redirects or Branch.io HTML metadata on a short Spotify link (`spotify.link` / `spoti.fi`). */
    suspend fun resolveShortSpotify(url: String): AppLink.Spotify? = runCatching {
        withTimeoutOrNull(SHORT_LINK_TIMEOUT_MS) {
            val client = HttpClient { followRedirects = false }
            try {
                var current = url
                repeat(MAX_REDIRECTS) {
                    val response = client.get(current) {
                        header(HttpHeaders.UserAgent, SHORT_LINK_UA)
                    }
                    val finalUrl = response.request.url.toString()
                    (parse(finalUrl) as? AppLink.Spotify)?.let { return@withTimeoutOrNull it }
                    val location = response.headers[HttpHeaders.Location]?.trim()
                    if (response.status.value in 300..399 && !location.isNullOrEmpty()) {
                        extractSpotifyUri(location)?.let { return@withTimeoutOrNull it }
                        val next = resolveRedirectUrl(current, location) ?: return@withTimeoutOrNull null
                        (parse(next) as? AppLink.Spotify)?.let { return@withTimeoutOrNull it }
                        current = next
                    } else {
                        if (response.status.value in 200..299) {
                            extractSpotifyFromHtml(response.bodyAsText())?.let { return@withTimeoutOrNull it }
                        }
                        return@withTimeoutOrNull null
                    }
                }
                null
            } finally {
                client.close()
            }
        }
    }.getOrNull()

    internal fun extractSpotifyFromHtml(html: String): AppLink.Spotify? {
        OPEN_SPOTIFY_REGEX.find(html)?.let { match ->
            return AppLink.Spotify(match.groupValues[1], match.groupValues[2])
        }
        return extractSpotifyUri(html)
    }

    private fun extractSpotifyUri(text: String): AppLink.Spotify? =
        SPOTIFY_URI_REGEX.find(text)?.let { match ->
            AppLink.Spotify(match.groupValues[1], match.groupValues[2])
        }

    private fun resolveRedirectUrl(base: String, location: String): String? = when {
        location.startsWith("https://", ignoreCase = true) || location.startsWith("http://", ignoreCase = true) -> location
        location.startsWith("//") -> "${base.substringBefore("://")}:$location"
        location.startsWith("/") -> "${base.substringBefore("://")}://${base.substringAfter("://").substringBefore('/')}$location"
        else -> null
    }

    private fun isVideoId(id: String) = id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
