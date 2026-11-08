package com.qmusic.engine.video

import com.qmusic.engine.Ytmusic
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.extractor.ExtractorStreamResolver
import com.qmusic.engine.extractor.createExtractorStreamResolver
import com.qmusic.engine.models.YouTubeClient
import com.qmusic.engine.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.userAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** YouTube refused the request because nobody is signed in. */
class VideoSignInRequired : IllegalStateException("Sign in to YouTube")

/**
 * YouTube itself (not YouTube Music): the home feed of the signed-in account, search, the watch page with its related
 * videos and comments, channels, and the account's own lists (subscriptions, history, likes, watch later, playlists).
 * It asks the same Innertube service the website does, with the account's cookies.
 */
class YouTubeVideos(
    private val http: HttpClient = Ytmusic.createDefaultHttpClient(),
    private val extractor: ExtractorStreamResolver? = createExtractorStreamResolver(),
) : VideoService {
    /** The account whose feed this is; without one, only what needs no account (search, channels, watch pages) works. */
    override var auth: YouTubeAuth? = null
    override var locale: YouTubeLocale = YouTubeLocale()
    private var visitorData: String? = null

    override val isSignedIn: Boolean get() = auth?.isLoggedIn == true

    // ------------------------------------------------------------------ feeds of the account

    /** The home feed: what YouTube suggests to the signed-in account (nothing without one). */
    override suspend fun home(): Result<VideoFeed> = feed("browse", "FEwhat_to_watch")

    /** A chip's feed of the home page, or the next page of any feed. */
    override suspend fun more(continuation: String): Result<VideoFeed> = call("browse", continuation = continuation, parse = VideoParser::feed)

    override suspend fun subscriptions(): Result<VideoFeed> = feed("browse", "FEsubscriptions")

    override suspend fun history(): Result<VideoFeed> = feed("browse", "FEhistory")

    override suspend fun likedVideos(): Result<VideoFeed> = feed("browse", "VLLL")

    override suspend fun watchLater(): Result<VideoFeed> = feed("browse", "VLWL")

    /** The playlists of the account (and the ones it saved). */
    override suspend fun playlists(): Result<VideoFeed> = feed("browse", "FEplaylist_aggregation")

    /** The channels the account is subscribed to. */
    override suspend fun subscribedChannels(): Result<VideoFeed> = feed("browse", "FEchannels")

    /** The videos of a playlist ([playlistId] without the "VL" in front). */
    override suspend fun playlist(playlistId: String): Result<VideoFeed> = feed("browse", "VL" + playlistId.removePrefix("VL"))

    private suspend fun feed(endpoint: String, browseId: String, params: String? = null): Result<VideoFeed> =
        call(endpoint, browseId = browseId, params = params, parse = VideoParser::feed)

    // ------------------------------------------------------------------ search

    /** [params] picks what is looked for (see [VideoSearchFilter]); null is everything. */
    override suspend fun search(query: String, params: String?): Result<VideoFeed> =
        call("search", query = query, params = params, parse = VideoParser::feed)

    /** The words YouTube suggests as [input] is typed. */
    override suspend fun suggestions(input: String): Result<List<String>> =
        runCatching {
            val text = http.get("https://suggestqueries.google.com/complete/search") {
                parameter("client", "firefox")
                parameter("ds", "yt")
                parameter("hl", locale.hl)
                parameter("q", input)
                userAgent(YouTubeClient.USER_AGENT_WEB)
            }.bodyAsText()
            Json.parseToJsonElement(text).jsonArray[1].jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }

    // ------------------------------------------------------------------ the watch page

    override suspend fun watch(videoId: String): Result<VideoDetails> =
        call("next", videoId = videoId, parse = { root ->
            VideoParser.details(root, videoId) ?: error("This video is not available")
        })

    override suspend fun comments(token: String): Result<VideoComments> =
        call("next", continuation = token, parse = VideoParser::comments)

    /** Every way to play [videoId] (empty when it cannot be played, e.g. it is private or age-restricted). */
    override suspend fun qualities(videoId: String): Result<List<VideoQuality>> =
        runCatching { extractor?.videoQualities(videoId).orEmpty() }

    // ------------------------------------------------------------------ channels

    /** A channel's page; [params] opens one of its tabs (see [VideoChannelTab]), none opens its videos. */
    override suspend fun channel(channelId: String, params: String?): Result<VideoChannelPage> =
        call("browse", browseId = channelId, params = params ?: VIDEOS_TAB, parse = { root ->
            VideoParser.channel(root, channelId) ?: error("This channel is not available")
        })

    // ------------------------------------------------------------------ what the account does

    override suspend fun setLike(videoId: String, like: Boolean): Result<Unit> =
        signedIn { action("like/${if (like) "like" else "removelike"}", buildJsonObject { put("target", buildJsonObject { put("videoId", videoId) }) }) }

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        signedIn {
            action(
                "subscription/${if (subscribed) "subscribe" else "unsubscribe"}",
                buildJsonObject { put("channelIds", buildJsonArray { add(JsonPrimitive(channelId)) }) },
            )
        }

    override suspend fun setWatchLater(videoId: String, saved: Boolean): Result<Unit> =
        signedIn {
            action(
                "browse/edit_playlist",
                buildJsonObject {
                    put("playlistId", "WL")
                    put(
                        "actions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    if (saved) {
                                        put("action", "ACTION_ADD_VIDEO")
                                        put("addedVideoId", videoId)
                                    } else {
                                        put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                                        put("removedVideoId", videoId)
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

    private suspend fun signedIn(block: suspend () -> Unit): Result<Unit> =
        if (!isSignedIn) Result.failure(VideoSignInRequired()) else runCatching { block() }

    private suspend fun action(path: String, body: JsonObject) {
        val response = post(path, body)
        check(response.status.value in 200..299) { "YouTube answered ${response.status.value}" }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Asks YouTube and reads the answer with [parse]. The answer is a big JSON document (up to a megabyte), so reading
     * it happens off the main thread: done on the screen's thread it froze the picture for a moment at every list.
     */
    private suspend fun <T> call(
        endpoint: String,
        browseId: String? = null,
        params: String? = null,
        query: String? = null,
        videoId: String? = null,
        continuation: String? = null,
        parse: (JsonObject) -> T,
    ): Result<T> =
        runCatching {
            ensureVisitorData()
            val body = buildJsonObject {
                put("context", context())
                browseId?.let { put("browseId", it) }
                params?.let { put("params", it) }
                query?.let { put("query", it) }
                videoId?.let { put("videoId", it) }
                continuation?.let { put("continuation", it) }
            }
            val response = post(endpoint, body)
            val text = response.bodyAsText()
            check(response.status.value in 200..299) {
                if (response.status.value == 401 || response.status.value == 403) "Sign in to YouTube" else "YouTube answered ${response.status.value}"
            }
            withContext(Dispatchers.Default) { parse(Json.parseToJsonElement(text).jsonObject) }
        }

    /** Gets the anonymous visitor id ready, so the first list does not wait for it. */
    override suspend fun warmUp() {
        withContext(Dispatchers.Default) { ensureVisitorData() }
    }

    private suspend fun post(path: String, body: JsonObject): HttpResponse =
        http.post("$BASE/$path") {
            parameter("prettyPrint", false)
            userAgent(YouTubeClient.USER_AGENT_WEB)
            header("X-YouTube-Client-Name", "1")
            header("X-YouTube-Client-Version", CLIENT_VERSION)
            header("Origin", ORIGIN)
            header("X-Origin", ORIGIN)
            header("Referer", "$ORIGIN/")
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
            val account = auth?.takeIf { it.isLoggedIn }
            if (account != null) {
                account.rawCookies?.let { header("Cookie", it) }
                account.getSapisidHash(ORIGIN)?.let {
                    header("Authorization", "SAPISIDHASH $it")
                    header("X-Goog-AuthUser", "0")
                }
            }
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

    private fun context(): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", "WEB")
                put("clientVersion", CLIENT_VERSION)
                put("hl", locale.hl)
                put("gl", locale.gl)
                visitorData?.let { put("visitorData", it) }
            },
        )
    }

    /** An anonymous visitor id once, like the music client does: without one YouTube treats the traffic as a bot's. */
    private suspend fun ensureVisitorData() {
        if (visitorData != null) return
        visitorData = runCatching {
            val response = http.post("$BASE/visitor_id") {
                parameter("prettyPrint", false)
                userAgent(YouTubeClient.USER_AGENT_WEB)
                setBody(TextContent(buildJsonObject { put("context", context()) }.toString(), ContentType.Application.Json))
            }.bodyAsText()
            Json.parseToJsonElement(response).jsonObject["responseContext"]?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val BASE = "$ORIGIN/youtubei/v1"
        const val CLIENT_VERSION = "2.20250925.01.00"

        /** The "Videos" tab of a channel. */
        const val VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
    }
}
