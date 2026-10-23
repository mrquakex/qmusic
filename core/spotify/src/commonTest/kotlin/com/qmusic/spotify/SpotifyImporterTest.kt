package com.qmusic.spotify

import com.qmusic.engine.YouTube
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.spotify.model.SpotifyPlaylist
import com.qmusic.spotify.model.SpotifyTrack
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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyImporterTest {

    @OptIn(InternalAPI::class)
    private class MockHttpEngine(
        private val handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) : HttpClientEngineBase("MockHttpEngine") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val (status, body) = handler(data)
            val callJob = Job().apply { complete() }
            return HttpResponseData(
                statusCode = status,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(body.encodeToByteArray()),
                callContext = callJob,
            )
        }
    }

    private val sampleNextDataHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "type": "playlist",
                        "name": "Today's Top Hits",
                        "subtitle": "The hottest 50 tracks.",
                        "trackList": [
                          {
                            "uri": "spotify:track:track123",
                            "title": "Espresso",
                            "subtitle": "Sabrina Carpenter",
                            "duration": 175459,
                            "isExplicit": false
                          },
                          {
                            "uri": "spotify:track:track456",
                            "title": "Birds of a Feather",
                            "subtitle": "Billie Eilish, FINNEAS",
                            "duration": 180123,
                            "isExplicit": true
                          }
                        ],
                        "visualIdentity": {
                          "image": [
                            { "url": "https://i.scdn.co/image/art_large.jpg", "maxWidth": 640 },
                            { "url": "https://i.scdn.co/image/art_small.jpg", "maxWidth": 64 }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
            </script>
        </head>
        <body></body>
        </html>
    """.trimIndent()

    @Test
    fun testParsePlaylistFromEmbedNextData() = runTest {
        val mockEngine = MockHttpEngine { req ->
            assertTrue(req.url.toString().contains("embed/playlist/37i9dQZF1DXcBWIGoYBM5M"))
            HttpStatusCode.OK to sampleNextDataHtml
        }
        val client = HttpClient(mockEngine)
        val importer = SpotifyImporter.create(client)

        val result = importer.importPlaylistFromUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=test1234")
        assertTrue(result.isSuccess, "Import should succeed: ${result.exceptionOrNull()}")

        val playlist = result.getOrThrow()
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.id)
        assertEquals("Today's Top Hits", playlist.title)
        assertEquals("The hottest 50 tracks.", playlist.description)
        assertEquals("https://i.scdn.co/image/art_large.jpg", playlist.thumbnailUrl)
        assertEquals(2, playlist.trackCount)
        assertEquals(2, playlist.tracks.size)

        val track1 = playlist.tracks[0]
        assertEquals("track123", track1.id)
        assertEquals("Espresso", track1.title)
        assertEquals("Sabrina Carpenter", track1.artist)
        assertEquals(listOf("Sabrina Carpenter"), track1.artists)
        assertEquals(175459L, track1.durationMs)

        val track2 = playlist.tracks[1]
        assertEquals("track456", track2.id)
        assertEquals("Birds of a Feather", track2.title)
        assertEquals(listOf("Billie Eilish", "FINNEAS"), track2.artists)
        assertEquals(180123L, track2.durationMs)
    }

    @Test
    fun testParsePlaylistFromVariousUrlFormats() = runTest {
        val mockEngine = MockHttpEngine { _ ->
            HttpStatusCode.OK to sampleNextDataHtml
        }
        val client = HttpClient(mockEngine)
        val importer = SpotifyImporter.create(client)

        val urls = listOf(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abcde12345",
            "https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            "http://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M/",
        )

        for (url in urls) {
            val result = importer.importPlaylistFromUrl(url)
            assertTrue(result.isSuccess, "Failed for URL: $url")
            assertEquals("37i9dQZF1DXcBWIGoYBM5M", result.getOrThrow().id)
        }
    }

    @Test
    fun testInvalidUrlFailsGracefully() = runTest {
        val mockEngine = MockHttpEngine { _ ->
            HttpStatusCode.NotFound to "Not Found"
        }
        val client = HttpClient(mockEngine)
        val importer = SpotifyImporter.create(client)

        val result = importer.importPlaylistFromUrl("https://youtube.com/watch?v=123")
        assertTrue(result.isFailure, "Invalid Spotify URL should fail")
    }

    @Test
    fun testMatchTrackToYouTubeFindsBestMatch() = runTest {
        val track = SpotifyTrack(
            id = "track123",
            title = "Espresso",
            artists = listOf("Sabrina Carpenter"),
            durationMs = 175000L,
        )

        val bestCandidate = SongItem(
            id = "yt_espresso_official",
            title = "Espresso",
            artists = listOf(Artist("Sabrina Carpenter", id = "UC_Sabrina_Carpenter")),
            duration = 175,
        )
        val liveCandidate = SongItem(
            id = "yt_espresso_live",
            title = "Espresso (Live)",
            artists = listOf(Artist("Sabrina Carpenter", id = "UC_Sabrina_Carpenter")),
            duration = 240,
        )

        // Mock YouTube client search
        val fakeYouTube = createFakeYouTube(listOf(liveCandidate, bestCandidate))
        val importer = SpotifyImporter.create()

        val matchResult = importer.matchTrackToYouTube(track, fakeYouTube)
        assertTrue(matchResult.isSuccess, "Matching should succeed: ${matchResult.exceptionOrNull()}")
        val matched = matchResult.getOrThrow()
        assertEquals("yt_espresso_official", matched.id)
    }

    @Test
    fun testYouTubeAuthParsingAndSapisidHash() {
        val auth = YouTubeAuth(clock = { 1700000000L })
        assertFalse(auth.isLoggedIn)
        assertNull(auth.authorizationHeader)
        assertNull(auth.getSapisidHash())

        // Set cookies with SAPISID
        val cookieString = "VISITOR_INFO1_LIVE=xyz; SAPISID=sapisid_secret_123; SID=sid123"
        auth.setCookies(cookieString)

        assertTrue(auth.isLoggedIn)
        val hash = auth.getSapisidHash("https://music.youtube.com")
        assertNotNull(hash)
        assertTrue(hash.startsWith("1700000000_"), "Hash should begin with timestamp prefix")
        assertEquals("SAPISIDHASH $hash", auth.authorizationHeader)

        // Test with __Secure-3PAPISID fallback
        val authFallback = YouTubeAuth(clock = { 1700000000L })
        authFallback.setCookies("PREF=1; __Secure-3PAPISID=secure_sapisid_value; HSID=456")
        assertTrue(authFallback.isLoggedIn)
        val fallbackHash = authFallback.getSapisidHash("https://music.youtube.com")
        assertNotNull(fallbackHash)
        assertTrue(fallbackHash.startsWith("1700000000_"))
    }

    private fun createFakeYouTube(candidates: List<SongItem>): YouTube {
        val jsonSearchResult = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    ${candidates.joinToString(",") { item ->
                                        """
                                        {
                                          "musicResponsiveListItemRenderer": {
                                            "playlistItemData": {
                                              "videoId": "${item.id}"
                                            },
                                            "flexColumns": [
                                              {
                                                "musicResponsiveListItemFlexColumnRenderer": {
                                                  "text": {
                                                    "runs": [
                                                      {
                                                        "text": "${item.title}"
                                                      }
                                                    ]
                                                  }
                                                }
                                              },
                                              {
                                                "musicResponsiveListItemFlexColumnRenderer": {
                                                  "text": {
                                                    "runs": [
                                                      ${item.artists.joinToString(",") { a ->
                                                          """
                                                          {
                                                            "text": "${a.name}",
                                                            "navigationEndpoint": {
                                                              "browseEndpoint": {
                                                                "browseId": "${a.id ?: "UC_${a.name}"}"
                                                              }
                                                            }
                                                          }
                                                          """.trimIndent()
                                                      }},
                                                      {"text": " • "},
                                                      {"text": "${item.duration?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "3:00"}"}
                                                    ]
                                                  }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                        """.trimIndent()
                                    }}
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val mockEngine = MockHttpEngine { _ ->
            HttpStatusCode.OK to jsonSearchResult
        }
        val httpClient = HttpClient(mockEngine) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(
                    kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                    },
                )
            }
        }
        val ytmusic = com.qmusic.engine.Ytmusic(httpClient = httpClient)
        return YouTube(ytmusic = ytmusic)
    }
}
