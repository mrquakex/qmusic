package com.qmusic.engine

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.BrowseBody
import com.qmusic.engine.models.InnertubeContext
import com.qmusic.engine.models.LikeBody
import com.qmusic.engine.models.NextBody
import com.qmusic.engine.models.PlayerBody
import com.qmusic.engine.models.SearchBody
import com.qmusic.engine.models.SearchSuggestionsBody
import com.qmusic.engine.models.SubscribeBody
import com.qmusic.engine.models.YouTubeClient
import com.qmusic.engine.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ktor 3.x client handling YouTube Music Innertube API requests.
 */
class Ytmusic(
    private val httpClient: HttpClient = createDefaultHttpClient(),
    var cookie: String? = null,
    var auth: YouTubeAuth? = null,
    var visitorData: String? = null,
    var locale: YouTubeLocale = YouTubeLocale(),
) {
    companion object {
        const val BASE_URL = "https://music.youtube.com/youtubei/v1/"

        fun createDefaultHttpClient(): HttpClient =
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

    /** A plain GET (used for the lyrics service); null when the server does not answer with success. */
    suspend fun fetchText(url: String): String? {
        val response = httpClient.get(url) { userAgent("QMusic (https://github.com/mrquakex/qmusic)") }
        return if (response.status.value in 200..299) response.bodyAsText() else null
    }

    private fun HttpRequestBuilder.applyInnertubeHeaders(
        client: YouTubeClient,
        setLogin: Boolean = false,
    ) {
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", "${client.xClientName ?: 1}")
            append("X-YouTube-Client-Version", client.clientVersion)
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (client != YouTubeClient.IOS && client != YouTubeClient.ANDROID && client != YouTubeClient.ANDROID_VR) {
                append("x-origin", "https://music.youtube.com")
            }
            if (client.referer != null) {
                append("Referer", client.referer)
            }
            // ANDROID_VR does not support logged-in requests; a cookie makes it reject the player call.
            if (setLogin && client != YouTubeClient.ANDROID_VR) {
                val account = auth?.takeIf { it.isLoggedIn }
                val cookies = account?.rawCookies ?: cookie
                if (!cookies.isNullOrBlank()) append("Cookie", cookies)
                // Logged-in Innertube calls are authorised by SAPISIDHASH, not by the cookie alone.
                account?.authorizationHeader?.let {
                    append("Authorization", it)
                    append("X-Goog-AuthUser", "0")
                    append("Origin", "https://music.youtube.com")
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    /**
     * Fetches an anonymous visitor id once. Without it YouTube treats player requests as bot traffic
     * ("Sign in to confirm you're not a bot") and caps stream URLs at the first MiB.
     */
    suspend fun ensureVisitorData(): String? {
        visitorData?.let { return it }
        return runCatching {
            val client = YouTubeClient.WEB
            val text = httpClient.post("https://www.youtube.com/youtubei/v1/visitor_id") {
                contentType(ContentType.Application.Json)
                userAgent(client.userAgent)
                parameter("prettyPrint", false)
                setBody(
                    """{"context":{"client":{"clientName":"${client.clientName}","clientVersion":"${client.clientVersion}","hl":"${locale.hl}","gl":"${locale.gl}"}}}""",
                )
            }.bodyAsText()
            Json.parseToJsonElement(text).jsonObject["responseContext"]
                ?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.also { visitorData = it }
    }

    suspend fun search(
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): HttpResponse =
        httpClient.post("${BASE_URL}search") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(
                SearchBody(
                    context = client.toContext(locale, visitorData),
                    query = query,
                    params = params,
                ),
            )
            if (continuation != null) {
                parameter("continuation", continuation)
                parameter("ctoken", continuation)
            }
        }

    suspend fun searchSuggestions(
        input: String,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): HttpResponse =
        httpClient.post("${BASE_URL}music/get_search_suggestions") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(
                SearchSuggestionsBody(
                    context = client.toContext(locale, visitorData),
                    input = input,
                ),
            )
        }

    /** Likes or un-likes a song on the signed-in account (this is what fills "Liked music"). */
    suspend fun like(
        videoId: String,
        liked: Boolean,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): HttpResponse =
        httpClient.post("${BASE_URL}like/${if (liked) "like" else "removelike"}") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(
                LikeBody(
                    context = client.toContext(locale, visitorData),
                    target = LikeBody.Target(videoId),
                ),
            )
        }

    suspend fun subscribe(
        channelId: String,
        subscribed: Boolean,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): HttpResponse =
        httpClient.post("${BASE_URL}subscription/${if (subscribed) "subscribe" else "unsubscribe"}") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(SubscribeBody(context = client.toContext(locale, visitorData), channelIds = listOf(channelId)))
        }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        cpn: String? = null,
        signatureTimestamp: Int? = null,
        client: YouTubeClient = YouTubeClient.ANDROID_VR,
    ): HttpResponse {
        ensureVisitorData()
        val endpoint =if (client != YouTubeClient.ANDROID_MUSIC && client != YouTubeClient.WEB_REMIX) {
            "https://www.youtube.com/youtubei/v1/player"
        } else {
            "${BASE_URL}player"
        }
        return httpClient.post(endpoint) {
            applyInnertubeHeaders(client, setLogin = true)
            val context = client.toContext(locale, visitorData).let {
                if (client == YouTubeClient.TVHTML5) {
                    it.copy(
                        thirdParty = InnertubeContext.ThirdParty(
                            embedUrl = "https://www.youtube.com/watch?v=$videoId",
                        ),
                    )
                } else {
                    it
                }
            }
            setBody(
                PlayerBody(
                    context = context,
                    videoId = videoId,
                    playlistId = playlistId,
                    cpn = cpn,
                    contentCheckOk = true,
                    racyCheckOk = true,
                    playbackContext = signatureTimestamp?.let { sts ->
                        PlayerBody.PlaybackContext(
                            contentPlaybackContext = PlayerBody.PlaybackContext.ContentPlaybackContext(
                                signatureTimestamp = sts,
                            ),
                        )
                    },
                ),
            )
        }
    }

    suspend fun next(
        videoId: String? = null,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ): HttpResponse =
        httpClient.post("${BASE_URL}next") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData),
                    videoId = videoId,
                    playlistId = playlistId,
                    playlistSetVideoId = playlistSetVideoId,
                    index = index,
                    params = params,
                    continuation = continuation,
                ),
            )
        }

    suspend fun browse(
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): HttpResponse =
        httpClient.post("${BASE_URL}browse") {
            applyInnertubeHeaders(client, setLogin = true)
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData),
                    browseId = browseId,
                    params = params,
                    continuation = continuation,
                ),
            )
            if (continuation != null) {
                parameter("continuation", continuation)
                parameter("ctoken", continuation)
                parameter("type", "next")
            }
        }
}
