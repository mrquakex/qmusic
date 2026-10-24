package com.qmusic.ui.viewmodel

/** What a YouTube / YouTube Music link points at. */
sealed interface AppLink {
    data class Song(val videoId: String) : AppLink
    /** A playlist id as it appears in `list=` (no "VL" in front). */
    data class Playlist(val listId: String) : AppLink
    data class Album(val browseId: String) : AppLink
    data class Artist(val channelId: String) : AppLink

    /** open.spotify.com/track|album|playlist|artist/... (kind, Spotify id). */
    data class Spotify(val kind: String, val id: String) : AppLink
}

/** Reads music.youtube.com, youtube.com and youtu.be links, also inside shared text ("Listen to ... https://..."). */
object AppLinks {
    private val URL = Regex("https?://\\S+")

    fun parse(text: String): AppLink? {
        val link = URL.find(text)?.value?.trimEnd('.', ',', ')', '!') ?: return null
        val rest = link.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "").substringBefore('#').split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (host == "open.spotify.com" || host == "spotify.link") {
            // "/intl-tr/track/ID" as well as "/track/ID"
            val parts = segments.dropWhile { it.startsWith("intl-") }
            val kind = parts.getOrNull(0)?.takeIf { it in listOf("track", "album", "playlist", "artist") } ?: return null
            return parts.getOrNull(1)?.let { AppLink.Spotify(kind, it) }
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

    private fun isVideoId(id: String) = id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
