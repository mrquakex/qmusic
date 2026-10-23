package com.qmusic.engine

import com.qmusic.engine.cipher.FaradayCipherEngine
import com.qmusic.engine.cipher.InMemoryPlayerConfigRepository
import com.qmusic.engine.cipher.QuickJsEngine
import com.qmusic.engine.cipher.RemotePlayerConfigParser
import com.qmusic.engine.cipher.RemotePlayerConfigStore
import com.qmusic.engine.cipher.ZemerCipherSolver
import com.qmusic.engine.cipher.getTextWithoutRedirects
import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.ITAG
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResult
import com.qmusic.engine.models.SongInfo
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class YtEngineTest {

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class MockHttpEngine(
        private val handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) : HttpClientEngineBase("MockHttpEngine") {
        override val config: io.ktor.client.engine.HttpClientEngineConfig = io.ktor.client.engine.HttpClientEngineConfig()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            println("MOCK REQ: ${data.url}")
            val (status, body) = handler(data)
            println("MOCK RESP for ${data.url}: status=$status bodyLength=${body.length}")
            val callJob = kotlinx.coroutines.Job().apply { complete() }
            return HttpResponseData(
                statusCode = status,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(body.encodeToByteArray()),
                callContext = callJob,
            )
        }
    }

    private fun createMockHttpClient(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockHttpEngine(handler)) {
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

    @Test
    fun testQuickJsEngineEvaluation() = runTest {
        val engine = QuickJsEngine()
        engine.initialize()
        engine.setupYoutubeGlobals()

        // Check DOM globals
        val windowCheck = engine.evaluate("typeof window", 100)
        assertEquals("object", windowCheck)
        val documentCheck = engine.evaluate("typeof document", 100)
        assertEquals("object", documentCheck)
        val locationCheck = engine.evaluate("typeof location", 100)
        assertEquals("object", locationCheck)
        val navigatorCheck = engine.evaluate("typeof navigator", 100)
        assertEquals("object", navigatorCheck)

        // Basic arithmetic
        val result = engine.evaluate("2 + 2", 100)
        assertEquals("4", result)

        engine.dispose()
    }

    @Test
    fun testZemerCipherSolver() = runTest {
        val playerCode = """
            var _yt_player = {};
            (function(g) {
                g.testSig = function(a, b, s) {
                    return s.split("").reverse().join("");
                };
                g.NTransformClass = function(url, flag) {
                    this.get = function(key) {
                        return "transformed_n_token";
                    };
                };
            })(_yt_player);
        """.trimIndent()

        val config = RemotePlayerConfigParser.HardcodedPlayerConfig(
            sigFuncName = "_expr_sig",
            sigConstantArg = null,
            sigJsExpression = "g.testSig(0,0,INPUT)",
            nFuncName = "_expr_n",
            nArrayIndex = null,
            nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.NTransformClass('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return t;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20000,
        )

        val solver = ZemerCipherSolver.create(playerCode, config)
        val sig = solver.solveSignature("abcdef")
        assertEquals("fedcba", sig)

        val n = solver.solveN("KdrqFlzJXl9EcCwlmEy")
        assertEquals("transformed_n_token", n)

        solver.dispose()
    }

    @Test
    fun testRemotePlayerConfigParser() {
        val jsonText = """
            {
                "schemaVersion": 1,
                "players": {
                    "abcd1234": {
                        "sig": "g.sigFunc(1,2,INPUT)",
                        "nClass": "SomeClass",
                        "sts": 20073,
                        "aliases": ["ef012345"]
                    }
                }
            }
        """.trimIndent()

        val parseResult = RemotePlayerConfigParser.parse(jsonText)
        assertTrue(parseResult is RemotePlayerConfigParser.ParseResult.Success)
        val configs = (parseResult as RemotePlayerConfigParser.ParseResult.Success).configs
        assertTrue(configs.containsKey("abcd1234"))
        assertTrue(configs.containsKey("ef012345"))
        assertEquals(20073, configs["abcd1234"]?.signatureTimestamp)
        assertEquals("g.sigFunc(1,2,INPUT)", configs["abcd1234"]?.sigJsExpression)

        // Player hash extraction
        val url = "https://www.youtube.com/s/player/abcd1234/player_ias.vflset/en_GB/base.js"
        assertEquals("abcd1234", RemotePlayerConfigParser.extractPlayerHash(url))
    }

    @Test
    fun testItagConstants() {
        assertEquals(251, ITAG.AUDIO_OPUS_MEDIUM)
        assertEquals(140, ITAG.AUDIO_AAC_MEDIUM)
        assertEquals(250, ITAG.AUDIO_OPUS_LOW)
        assertEquals(774, ITAG.AUDIO_OPUS_HIGH)
        assertTrue(ITAG.isAudio(251))
        assertTrue(ITAG.isAudio(140))
        assertFalse(ITAG.isAudio(137))
        assertFalse(ITAG.isAudio(136))
    }

    @Test
    fun testYouTubeSearchParsing() = runTest {
        val searchJson = """
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
                                                            {
                                                                "musicResponsiveListItemRenderer": {
                                                                    "playlistItemData": {
                                                                        "videoId": "test_video_123"
                                                                    },
                                                                    "flexColumns": [
                                                                        {
                                                                            "musicResponsiveListItemFlexColumnRenderer": {
                                                                                "text": {
                                                                                    "runs": [{"text": "Test Song"}]
                                                                                }
                                                                            }
                                                                        },
                                                                        {
                                                                            "musicResponsiveListItemFlexColumnRenderer": {
                                                                                "text": {
                                                                                    "runs": [
                                                                                        {
                                                                                            "text": "Test Artist",
                                                                                            "navigationEndpoint": {
                                                                                                "browseEndpoint": {"browseId": "UC12345678"}
                                                                                            }
                                                                                        },
                                                                                        {
                                                                                            "text": "Test Album",
                                                                                            "navigationEndpoint": {
                                                                                                "browseEndpoint": {"browseId": "MPREb_12345"}
                                                                                            }
                                                                                        },
                                                                                        {"text": "3:45"}
                                                                                    ]
                                                                                }
                                                                            }
                                                                        }
                                                                    ],
                                                                    "thumbnail": {
                                                                        "musicThumbnailRenderer": {
                                                                            "thumbnail": {
                                                                                "thumbnails": [
                                                                                    {"url": "https://example.com/thumb.jpg", "width": 540, "height": 540}
                                                                                ]
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
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

        val mockClient = createMockHttpClient { _ ->
            HttpStatusCode.OK to searchJson
        }

        val ytmusic = Ytmusic(httpClient = mockClient)
        val youtube = YouTube(ytmusic = ytmusic)

        val searchResult = youtube.search("test song").getOrThrow()
        assertEquals(1, searchResult.items.size)
        val item = searchResult.items.first()
        assertEquals("test_video_123", item.id)
        assertEquals("Test Song", item.title)
        assertEquals("Test Artist", item.artists.firstOrNull()?.name)
        assertEquals("Test Album", item.album?.name)
        assertEquals(225, item.duration)
        assertEquals("https://example.com/thumb.jpg", item.thumbnail)
        mockClient.close()
    }

    @Test
    fun testStreamUrlResolutionWithCipher() = runTest {
        val playerJson = """
            {
                "playabilityStatus": {"status": "OK"},
                "streamingData": {
                    "adaptiveFormats": [
                        {
                            "itag": 251,
                            "mimeType": "audio/webm; codecs=\"opus\"",
                            "bitrate": 160000,
                            "contentLength": "3500000",
                            "signatureCipher": "s=sig123&sp=sig&url=https%3A%2F%2Frr1---sn-test.googlevideo.com%2Fvideoplayback%3Fitag%3D251%26n%3DtestNParam"
                        },
                        {
                            "itag": 140,
                            "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                            "bitrate": 128000,
                            "contentLength": "2800000",
                            "url": "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140&n=testNParamDirect"
                        }
                    ]
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient { req ->
            when {
                req.url.toString().contains("iframe_api") -> {
                    HttpStatusCode.OK to "player\\/abcd1234\\/"
                }
                req.url.toString().contains("player_configs.json") -> {
                    HttpStatusCode.OK to """
                        {
                            "schemaVersion": 1,
                            "players": {
                                "abcd1234": {
                                    "sig": "g.sigFunc(0,0,INPUT)",
                                    "nClass": "NClass",
                                    "sts": 20073
                                }
                            }
                        }
                    """.trimIndent()
                }
                req.url.toString().contains("base.js") -> {
                    HttpStatusCode.OK to """
                        var _yt_player = {};
                        (function(g) {
                            g.sigFunc = function(a, b, s) { return s.split("").reverse().join(""); };
                            g.NClass = function(u, f) { this.get = function(k) { return "solvedNToken"; }; };
                        })(_yt_player);
                    """.trimIndent()
                }
                else -> {
                    HttpStatusCode.OK to playerJson
                }
            }
        }

        val ytmusic = Ytmusic(httpClient = mockClient)
        val cipherEngine = FaradayCipherEngine(httpClient = mockClient)
        val youtube = YouTube(ytmusic = ytmusic, cipherEngine = cipherEngine)

        val streams = youtube.resolveStreamUrls("test_video_123").getOrThrow()
        assertEquals(2, streams.size)

        // 251 (Opus 160k) has higher bitrate, should be first
        val opusStream = streams.first { it.itag == 251 }
        assertEquals(251, opusStream.itag)
        assertTrue(opusStream.url.contains("sig=321gis")) // reversed signature
        assertTrue(opusStream.url.contains("n=solvedNToken")) // transformed n-parameter

        val aacStream = streams.first { it.itag == 140 }
        assertEquals(140, aacStream.itag)
        assertTrue(aacStream.url.contains("n=solvedNToken"))

        cipherEngine.invalidate()
        mockClient.close()
    }
}
