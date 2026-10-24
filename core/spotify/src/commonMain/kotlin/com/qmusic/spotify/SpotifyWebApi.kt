package com.qmusic.spotify

import com.qmusic.engine.models.Album
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.ArtistItem
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.CollectionPage
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.SpotifyIds
import com.qmusic.engine.models.Thumbnail
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The Spotify session is gone (signed out, or the sign-in expired): the user has to sign in again. */
class SpotifySignedOutException : IllegalStateException("Spotify sign-in needed")

/** What an artist page is made of; the app words the shelves in its own language. */
data class SpotifyArtist(
    val name: String,
    val image: String?,
    val followers: Long?,
    val topSongs: List<SongItem>,
    val albums: List<CollectionItem>,
    val singles: List<CollectionItem>,
    val related: List<CollectionItem>,
    val following: Boolean,
)

/** A playlist of the account, and whether the account owns it (so it can be changed). */
data class SpotifyPlaylistItem(val item: CollectionItem, val owned: Boolean)

/**
 * Spotify's Web API, called with the signed-in account's token ([token]; `refresh` asks for a new one after a 401).
 * Everything comes back as the app's own models, with ids from [SpotifyIds].
 */
class SpotifyWebApi(
    private val token: suspend (refresh: Boolean) -> String?,
    private val http: HttpClient = SpotifyImporter.createDefaultSpotifyHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var meId: String? = null

    // ------------------------------------------------------------------ the account

    /** The account's user id (used to tell its own playlists from followed ones). */
    suspend fun me(): String {
        meId?.let { return it }
        val id = call(HttpMethod.Get, "/me").obj()?.str("id") ?: error("No Spotify account")
        meId = id
        return id
    }

    suspend fun savedTracks(max: Int = MAX_LIBRARY): List<SongItem> =
        pages("/me/tracks?limit=50", max).mapNotNull { track(it.obj("track")) }

    suspend fun playlists(max: Int = MAX_PLAYLISTS): List<SpotifyPlaylistItem> {
        val me = me()
        return pages("/me/playlists?limit=50", max).mapNotNull { o ->
            playlist(o)?.let { SpotifyPlaylistItem(it, o.obj("owner")?.str("id") == me || o["collaborative"].bool()) }
        }
    }

    suspend fun savedAlbums(max: Int = MAX_PLAYLISTS): List<CollectionItem> =
        pages("/me/albums?limit=50", max).mapNotNull { album(it.obj("album")) }

    suspend fun followedArtists(max: Int = MAX_PLAYLISTS): List<CollectionItem> =
        pages("/me/following?type=artist&limit=50", max, container = "artists").mapNotNull(::artist)

    /** "short_term" (about a month), "medium_term" (half a year) or "long_term". */
    suspend fun topTracks(range: String = "short_term", max: Int = 50): List<SongItem> =
        pages("/me/top/tracks?limit=50&time_range=$range", max).mapNotNull(::track)

    suspend fun topArtists(range: String = "medium_term", max: Int = 20): List<CollectionItem> =
        pages("/me/top/artists?limit=$max&time_range=$range", max).mapNotNull(::artist)

    suspend fun recentlyPlayed(): List<SongItem> =
        pages("/me/player/recently-played?limit=50", 50).mapNotNull { track(it.obj("track")) }.distinctBy { it.id }

    suspend fun newReleases(max: Int = 20): List<CollectionItem> =
        call(HttpMethod.Get, "/browse/new-releases?limit=$max").obj()?.obj("albums")?.arr("items")
            .orEmpty().mapNotNull { album(it as? JsonObject) }

    // ------------------------------------------------------------------ pages

    suspend fun playlistPage(playlistId: String): CollectionPage {
        val meta = call(HttpMethod.Get, "/playlists/$playlistId?fields=name,description,images,collaborative,owner(id,display_name)").obj()
            ?: error("Playlist not found")
        val songs = pages("/playlists/$playlistId/tracks?limit=100&additional_types=track", MAX_LIBRARY)
            .mapNotNull { track(it.obj("track")) }
        val owned = meta.obj("owner")?.str("id") == runCatching { me() }.getOrNull() || meta["collaborative"].bool()
        return CollectionPage(
            title = meta.str("name").orEmpty(),
            subtitle = meta.obj("owner")?.str("display_name"),
            thumbnail = images(meta["images"]).lastOrNull()?.url,
            songs = songs,
            description = meta.str("description")?.replace(Regex("<[^>]+>"), "")?.takeIf { it.isNotBlank() },
            editable = owned,
            // Removing needs the track's address on Spotify: kept per song, like YouTube Music's setVideoId.
            setVideoIds = songs.mapNotNull { song -> SpotifyIds.trackId(song.id)?.let { song.id to SpotifyIds.uri(it) } }.toMap(),
        )
    }

    suspend fun albumPage(albumId: String): CollectionPage {
        val album = call(HttpMethod.Get, "/albums/$albumId").obj() ?: error("Album not found")
        val first = album.obj("tracks")?.arr("items").orEmpty().mapNotNull { it as? JsonObject }
        val more = album.obj("tracks")?.str("next")?.let { pages(it, MAX_LIBRARY) }.orEmpty()
        val songs = (first + more).mapNotNull { track(it, album) }
        return CollectionPage(
            title = album.str("name").orEmpty(),
            subtitle = album.arr("artists").mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", "),
            thumbnail = images(album["images"]).lastOrNull()?.url,
            songs = songs,
            kindLine = album.str("release_date")?.take(4),
        )
    }

    suspend fun artist(artistId: String): SpotifyArtist {
        val artist = call(HttpMethod.Get, "/artists/$artistId").obj() ?: error("Artist not found")
        val top = runCatching {
            call(HttpMethod.Get, "/artists/$artistId/top-tracks?market=from_token").obj()?.arr("tracks").orEmpty()
                .mapNotNull { track(it as? JsonObject) }
        }.getOrDefault(emptyList())
        val releases = runCatching { pages("/artists/$artistId/albums?include_groups=album,single&limit=50", 50) }.getOrDefault(emptyList())
        val related = runCatching {
            call(HttpMethod.Get, "/artists/$artistId/related-artists").obj()?.arr("artists").orEmpty().mapNotNull { artist(it as? JsonObject) }
        }.getOrDefault(emptyList())
        val following = runCatching {
            (call(HttpMethod.Get, "/me/following/contains?type=artist&ids=$artistId") as? JsonArray)?.firstOrNull().bool()
        }.getOrDefault(false)
        return SpotifyArtist(
            name = artist.str("name").orEmpty(),
            image = images(artist["images"]).lastOrNull()?.url,
            followers = artist.obj("followers")?.get("total")?.let { (it as? JsonPrimitive)?.longOrNull },
            topSongs = top,
            albums = releases.filter { it.str("album_type") == "album" }.mapNotNull(::album),
            singles = releases.filter { it.str("album_type") != "album" }.mapNotNull(::album),
            related = related,
            following = following,
        )
    }

    // ------------------------------------------------------------------ search

    /** Search; a [filter] gives one kind with [offset] paging (the page's continuation carries the next offset). */
    suspend fun search(query: String, filter: SearchFilter?, offset: Int = 0): SearchResultsPage {
        val type = when (filter) {
            null, SearchFilter.ALL -> "track,artist,album,playlist"
            SearchFilter.SONGS, SearchFilter.VIDEOS -> "track"
            SearchFilter.ALBUMS -> "album"
            SearchFilter.ARTISTS -> "artist"
            SearchFilter.COMMUNITY_PLAYLISTS, SearchFilter.FEATURED_PLAYLISTS -> "playlist"
        }
        val limit = if (filter == null || filter == SearchFilter.ALL) 10 else 30
        val root = call(HttpMethod.Get, "/search?q=${query.encodeURLParameter()}&type=$type&limit=$limit&offset=$offset").obj()
            ?: return SearchResultsPage()
        fun items(key: String) = root.obj(key)?.arr("items").orEmpty().mapNotNull { it as? JsonObject }
        val songs = items("tracks").mapNotNull(::track)
        val artists = items("artists").mapNotNull { a -> artist(a)?.let { ArtistItem(it.browseId, it.title, it.subtitle, it.thumbnail) } }
        val albums = items("albums").mapNotNull(::album)
        val playlists = items("playlists").mapNotNull(::playlist)
        // The top card: an artist whose name is what was typed.
        val top = artists.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }
        val single = filter != null && filter != SearchFilter.ALL
        val hasMore = single && listOf("tracks", "albums", "artists", "playlists").any { root.obj(it)?.str("next") != null }
        return SearchResultsPage(
            top = if (single) null else top,
            songs = songs,
            albums = albums,
            artists = if (single) artists else artists.filter { it.browseId != top?.browseId },
            playlists = playlists,
            continuation = if (hasMore) "$CONTINUATION${filter?.name}:${offset + limit}" else null,
        )
    }

    /** The next part of a filtered search started with [search]. */
    suspend fun searchMore(query: String, continuation: String): SearchResultsPage? {
        if (!continuation.startsWith(CONTINUATION)) return null
        val (name, offset) = continuation.removePrefix(CONTINUATION).split(':').let { it.getOrNull(0) to it.getOrNull(1)?.toIntOrNull() }
        val filter = SearchFilter.entries.firstOrNull { it.name == name } ?: return null
        return search(query, filter, offset ?: return null)
    }

    /** The Spotify track that is the song [title] by [artist] (for liking a song that came from elsewhere). */
    suspend fun findTrack(title: String, artist: String, durationSeconds: Int?): String? {
        val query = "track:${title.take(80)}" + if (artist.isNotBlank()) " artist:${artist.take(60)}" else ""
        val hits = call(HttpMethod.Get, "/search?q=${query.encodeURLParameter()}&type=track&limit=5").obj()?.obj("tracks")?.arr("items")
            .orEmpty().mapNotNull { it as? JsonObject }
        val wanted = title.lowercase()
        val best = hits.maxByOrNull { hit ->
            var score = 0
            if (hit.str("name")?.lowercase() == wanted) score += 3
            if (hit.arr("artists").any { (it as? JsonObject)?.str("name").equals(artist, ignoreCase = true) }) score += 2
            val ms = hit["duration_ms"].long()
            if (durationSeconds != null && ms != null && kotlin.math.abs(ms / 1000 - durationSeconds) <= 5) score += 2
            score
        }
        return best?.str("id")
    }

    // ------------------------------------------------------------------ changes

    suspend fun setTrackSaved(trackId: String, saved: Boolean) {
        call(if (saved) HttpMethod.Put else HttpMethod.Delete, "/me/tracks?ids=$trackId")
    }

    suspend fun setArtistFollowed(artistId: String, follow: Boolean) {
        call(if (follow) HttpMethod.Put else HttpMethod.Delete, "/me/following?type=artist&ids=$artistId")
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        trackIds.chunked(100).forEach { chunk ->
            val body = buildJsonObject { put("uris", buildJsonArray { chunk.forEach { add(JsonPrimitive(SpotifyIds.uri(it))) } }) }
            call(HttpMethod.Post, "/playlists/$playlistId/tracks", body.toString())
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        val body = buildJsonObject {
            put("tracks", buildJsonArray { add(buildJsonObject { put("uri", SpotifyIds.uri(trackId)) }) })
        }
        call(HttpMethod.Delete, "/playlists/$playlistId/tracks", body.toString())
    }

    /** Moves the song at [from] so it ends up at [to]. */
    suspend fun moveInPlaylist(playlistId: String, from: Int, to: Int) {
        val body = buildJsonObject {
            put("range_start", from)
            put("insert_before", if (to > from) to + 1 else to)
        }
        call(HttpMethod.Put, "/playlists/$playlistId/tracks", body.toString())
    }

    /** A new private playlist with [trackIds] in it; its Spotify id. */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val body = buildJsonObject {
            put("name", name)
            put("public", false)
        }
        val id = call(HttpMethod.Post, "/users/${me()}/playlists", body.toString()).obj()?.str("id") ?: error("Playlist not created")
        if (trackIds.isNotEmpty()) addToPlaylist(id, trackIds)
        return id
    }

    /** Spotify has no deleting: a playlist is unfollowed, which takes it out of the library. */
    suspend fun deletePlaylist(playlistId: String) {
        call(HttpMethod.Delete, "/playlists/$playlistId/followers")
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun call(method: HttpMethod, path: String, body: String? = null): JsonElement? {
        var refresh = false
        repeat(ATTEMPTS) {
            val bearer = token(refresh) ?: throw SpotifySignedOutException()
            val response = http.request(if (path.startsWith("http")) path else "$BASE$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header(HttpHeaders.Accept, "application/json")
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
            when (val code = response.status.value) {
                in 200..299 -> {
                    val text = response.bodyAsText()
                    return if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
                }
                401 -> if (refresh) throw SpotifySignedOutException() else refresh = true
                429 -> delay((response.headers["Retry-After"]?.toLongOrNull() ?: 1L).coerceIn(1L, 5L) * 1000)
                in 500..599 -> delay(RETRY_DELAY_MS)
                else -> error("Spotify answered $code")
            }
        }
        error("Spotify did not answer")
    }

    /** Every item of a paged list (following its `next` links), up to [max]. */
    private suspend fun pages(path: String, max: Int, container: String? = null): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var next: String? = path
        while (next != null && out.size < max) {
            val page = call(HttpMethod.Get, next).obj() ?: break
            val list = if (container != null) page.obj(container) ?: break else page
            list.arr("items").forEach { (it as? JsonObject)?.let(out::add) }
            next = list.str("next")
        }
        return out.take(max)
    }

    /** A track; [albumFallback] fills in the album (and cover) for tracks listed on their album's own page. */
    private fun track(o: JsonObject?, albumFallback: JsonObject? = null): SongItem? {
        o ?: return null
        val id = o.str("id") ?: return null
        if (o.str("type").let { it != null && it != "track" }) return null
        if (o["is_local"].bool()) return null
        val album = o.obj("album") ?: albumFallback
        val thumbs = images(album?.get("images"))
        return SongItem(
            id = SpotifyIds.track(id),
            title = o.str("name") ?: return null,
            artists = o.arr("artists").mapNotNull { a -> (a as? JsonObject)?.let { Artist(it.str("name").orEmpty(), it.str("id")?.let(SpotifyIds::artist)) } },
            album = album?.str("id")?.let { Album(album.str("name").orEmpty(), SpotifyIds.album(it)) },
            duration = o["duration_ms"].long()?.let { (it / 1000).toInt() },
            thumbnail = thumbs.lastOrNull()?.url,
            thumbnails = thumbs,
            explicit = o["explicit"].bool(),
            shareLink = "https://open.spotify.com/track/$id",
        )
    }

    private fun album(o: JsonObject?): CollectionItem? {
        o ?: return null
        val id = o.str("id") ?: return null
        val artists = o.arr("artists").mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
        return CollectionItem(
            browseId = SpotifyIds.album(id),
            title = o.str("name") ?: return null,
            subtitle = listOfNotNull(artists.takeIf { it.isNotBlank() }, o.str("release_date")?.take(4)).joinToString(" • "),
            thumbnail = images(o["images"]).lastOrNull()?.url,
            kind = CollectionKind.ALBUM,
        )
    }

    private fun playlist(o: JsonObject?): CollectionItem? {
        o ?: return null
        val id = o.str("id") ?: return null
        return CollectionItem(
            browseId = SpotifyIds.playlist(id),
            title = o.str("name") ?: return null,
            subtitle = o.obj("owner")?.str("display_name"),
            thumbnail = images(o["images"]).lastOrNull()?.url,
            kind = CollectionKind.PLAYLIST,
        )
    }

    private fun artist(o: JsonObject?): CollectionItem? {
        o ?: return null
        val id = o.str("id") ?: return null
        return CollectionItem(
            browseId = SpotifyIds.artist(id),
            title = o.str("name") ?: return null,
            thumbnail = images(o["images"]).lastOrNull()?.url,
            kind = CollectionKind.ARTIST,
        )
    }

    /** Spotify lists the largest picture first; the app keeps them smallest first (the last one is the sharpest). */
    private fun images(element: JsonElement?): List<Thumbnail> =
        (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { img -> img.str("url")?.let { Thumbnail(it, img["width"].long()?.toInt(), img["height"].long()?.toInt()) } }
            .sortedBy { it.width ?: 0 }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

    private companion object {
        const val BASE = "https://api.spotify.com/v1"
        const val CONTINUATION = "spotify:"
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 800L
        const val MAX_LIBRARY = 2000
        const val MAX_PLAYLISTS = 500
    }
}
