package com.qmusic.spotify

import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SpotifyIds
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spotify Web API answers (trimmed copies of real ones) turned into the app's songs, pages and results. */
@OptIn(InternalAPI::class)
class SpotifyWebApiTest {

    private class FakeEngine(
        private val handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) : HttpClientEngineBase("FakeSpotify") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val (status, body) = handler(data)
            return HttpResponseData(
                statusCode = status,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(body.encodeToByteArray()),
                callContext = Job().apply { complete() },
            )
        }
    }

    private fun api(token: suspend (Boolean) -> String? = { "token" }, answer: (String) -> Pair<HttpStatusCode, String>) =
        SpotifyWebApi(token, HttpClient(FakeEngine { answer(it.url.toString()) }))

    private fun track(id: String, name: String, local: Boolean = false) = """
        {"id":"$id","name":"$name","type":"track","is_local":$local,"duration_ms":215000,"explicit":true,
         "artists":[{"id":"art1","name":"Tame Impala"}],
         "album":{"id":"alb1","name":"Currents","images":[{"url":"https://i.scdn.co/big","width":640,"height":640},{"url":"https://i.scdn.co/small","width":64,"height":64}]}}
    """

    @Test
    fun likedSongsFollowEveryPageAndSkipLocalFiles() = runTest {
        val api = api { url ->
            when {
                url.contains("offset=50") -> HttpStatusCode.OK to """{"items":[{"track":${track("t3", "Third")}}],"next":null}"""
                url.contains("/me/tracks") -> HttpStatusCode.OK to
                    """{"items":[{"track":${track("t1", "First")}},{"track":${track("t2", "Local", local = true)}}],
                        "next":"https://api.spotify.com/v1/me/tracks?offset=50&limit=50"}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        val songs = api.savedTracks()

        assertEquals(listOf(SpotifyIds.track("t1"), SpotifyIds.track("t3")), songs.map { it.id })
        val first = songs.first()
        assertEquals("Tame Impala", first.artists.single().name)
        assertEquals(SpotifyIds.artist("art1"), first.artists.single().id)
        assertEquals(SpotifyIds.album("alb1"), first.album?.id)
        assertEquals(215, first.duration)
        assertTrue(first.explicit)
        // The sharpest cover is the last one, as everywhere in the app.
        assertEquals("https://i.scdn.co/big", first.thumbnail)
        assertEquals("https://open.spotify.com/track/t1", first.shareLink)
    }

    @Test
    fun anExpiredTokenIsRenewedOnceThenTheSessionIsOver() = runTest {
        val asked = mutableListOf<Boolean>()
        var calls = 0
        val renewed = api(token = { refresh -> asked += refresh; "token" }) { _ ->
            calls++
            if (calls == 1) HttpStatusCode.Unauthorized to "{}" else HttpStatusCode.OK to """{"id":"me"}"""
        }
        assertEquals("me", renewed.me())
        assertEquals(listOf(false, true), asked)

        val ended = api { HttpStatusCode.Unauthorized to "{}" }
        assertFailsWith<SpotifySignedOutException> { ended.me() }

        val signedOut = api(token = { null }) { HttpStatusCode.OK to "{}" }
        assertFailsWith<SpotifySignedOutException> { signedOut.me() }
    }

    @Test
    fun aFilteredSearchPagesOnWithItsOffset() = runTest {
        val urls = mutableListOf<String>()
        val api = api { url ->
            urls += url
            HttpStatusCode.OK to """{"tracks":{"items":[${track("t1", "Dracula")}],"next":"more"}}"""
        }
        val page = api.search("dracula", SearchFilter.SONGS)
        assertEquals(listOf(SpotifyIds.track("t1")), page.songs.map { it.id })
        val token = page.continuation!!

        api.searchMore("dracula", token)
        assertTrue(urls.last().contains("offset=30"), urls.last())
        assertTrue(urls.last().contains("type=track"), urls.last())
        assertNull(api.searchMore("dracula", "youtube-token"))
    }

    @Test
    fun theTopCardIsTheArtistNamedLikeTheSearch() = runTest {
        val api = api {
            HttpStatusCode.OK to """{"artists":{"items":[{"id":"a1","name":"Tame Impala","images":[]},{"id":"a2","name":"Tame Impala Tribute","images":[]}]},
                "tracks":{"items":[]},"albums":{"items":[]},"playlists":{"items":[null]}}"""
        }
        val page = api.search("tame impala", null)
        assertEquals(SpotifyIds.artist("a1"), page.top?.browseId)
        assertEquals(listOf(SpotifyIds.artist("a2")), page.artists.map { it.browseId })
        assertNull(page.continuation)
    }

    @Test
    fun ownPlaylistsCanBeChangedOthersCannot() = runTest {
        fun answer(owner: String): (String) -> Pair<HttpStatusCode, String> = { url ->
            when {
                url.endsWith("/me") -> HttpStatusCode.OK to """{"id":"me"}"""
                url.contains("/tracks") -> HttpStatusCode.OK to """{"items":[{"track":${track("t1", "One")}}],"next":null}"""
                else -> HttpStatusCode.OK to """{"name":"Road","description":"<b>Long</b> drive","images":[],"owner":{"id":"$owner","display_name":"Mert"}}"""
            }
        }
        val mine = api(answer = answer("me")).playlistPage("p1")
        assertTrue(mine.editable)
        assertEquals("Long drive", mine.description)
        assertEquals(SpotifyIds.uri("t1"), mine.setVideoIds[SpotifyIds.track("t1")])

        val theirs = api(answer = answer("someone")).playlistPage("p1")
        assertTrue(!theirs.editable)
        assertEquals("Mert", theirs.subtitle)
    }

    @Test
    fun idsKeepTheirKind() {
        assertEquals("abc", SpotifyIds.trackId(SpotifyIds.track("abc")))
        assertNull(SpotifyIds.trackId(SpotifyIds.album("abc")))
        assertNull(SpotifyIds.trackId("kOn-HdEg6AQ"))
        assertEquals("abc", SpotifyIds.playlistId(SpotifyIds.playlist("abc")))
        assertEquals("abc", SpotifyIds.artistId(SpotifyIds.artist("abc")))
    }
}
