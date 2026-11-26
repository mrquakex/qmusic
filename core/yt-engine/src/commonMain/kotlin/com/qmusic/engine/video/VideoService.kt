package com.qmusic.engine.video

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.YouTubeLocale

/** What search can be narrowed or sorted to (Innertube base64 protobuf `sp` values). */
object VideoSearchFilter {
    const val VIDEOS = "EgIQAQ%3D%3D"
    const val CHANNELS = "EgIQAg%3D%3D"
    const val PLAYLISTS = "EgIQAw%3D%3D"
    const val LIVE = "EgJAAQ%3D%3D"
    /** Shorts only. */
    const val SHORTS = "EgIQCQ%3D%3D"
    /** Sort by upload date (newest first). */
    const val SORT_UPLOAD_DATE = "CAI%3D"
    /** Sort by view count (most viewed first). */
    const val SORT_VIEW_COUNT = "CAM%3D"
    /** Uploaded today. */
    const val DATE_TODAY = "EgIIAg%3D%3D"
    /** Uploaded this week. */
    const val DATE_WEEK = "EgIIAw%3D%3D"
    /** Uploaded this month. */
    const val DATE_MONTH = "EgIIBA%3D%3D"
    /** Under 4 minutes. */
    const val DURATION_SHORT = "EgIYAQ%3D%3D"
    /** 4 to 20 minutes. */
    const val DURATION_MEDIUM = "EgIYAw%3D%3D"
    /** Over 20 minutes. */
    const val DURATION_LONG = "EgIYAg%3D%3D"
}

/**
 * YouTube itself (not YouTube Music) as the app uses it. [YouTubeVideos] is the real one; tests stand in their own.
 */
interface VideoService {
    /** The account whose feed this is; without one, only what needs no account works (search, channels, watch pages). */
    var auth: YouTubeAuth?
    var locale: YouTubeLocale
    val isSignedIn: Boolean

    /** Gets what the first request needs ready (a no-op for stand-ins). */
    suspend fun warmUp() {}

    /** The home feed: what YouTube suggests to the signed-in account (nothing without one). */
    suspend fun home(): Result<VideoFeed>

    /** A chip's feed of the home page, or the next page of any feed. */
    suspend fun more(continuation: String): Result<VideoFeed>

    suspend fun subscriptions(): Result<VideoFeed>
    suspend fun history(): Result<VideoFeed>
    suspend fun likedVideos(): Result<VideoFeed>
    suspend fun watchLater(): Result<VideoFeed>

    /** The playlists of the account (and the ones it saved). */
    suspend fun playlists(): Result<VideoFeed>

    /** The channels the account is subscribed to. */
    suspend fun subscribedChannels(): Result<VideoFeed>

    /** The videos of a playlist. */
    suspend fun playlist(playlistId: String): Result<VideoFeed>

    /** [params] picks what is looked for (see [VideoSearchFilter]); null is everything. */
    suspend fun search(query: String, params: String? = null): Result<VideoFeed>

    /** The words YouTube suggests as [input] is typed. */
    suspend fun suggestions(input: String): Result<List<String>>

    suspend fun watch(videoId: String): Result<VideoDetails>
    suspend fun comments(token: String): Result<VideoComments>

    /** Every way to play [videoId], best first (empty when it cannot be played). */
    suspend fun qualities(videoId: String): Result<List<VideoQuality>>

    /** The small pictures shown over the seek bar while seeking (null when YouTube has none for the video). */
    suspend fun previewFrames(videoId: String): Result<com.qmusic.engine.models.PreviewFrames?> = Result.success(null)

    /** A channel's page; [params] opens one of its tabs, none opens its videos. */
    suspend fun channel(channelId: String, params: String? = null): Result<VideoChannelPage>

    suspend fun setLike(videoId: String, like: Boolean): Result<Unit>
    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit>
    suspend fun setWatchLater(videoId: String, saved: Boolean): Result<Unit>

    /** Puts a video in (or takes it out of) one of the account's playlists. */
    suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean): Result<Unit>

    /** The subtitle tracks of [videoId]. */
    suspend fun captions(videoId: String): Result<List<VideoCaption>>

    /** Posts a comment; [createToken] comes with the comments' page. */
    suspend fun postComment(createToken: String, text: String): Result<Unit>

    /** Tells YouTube the account watched [videoId], so it shows in the history and the suggestions learn from it. */
    suspend fun reportWatch(videoId: String)

    /**
     * How much of [videoId] was watched, as the website tells it while a video plays: the stretch from [fromSec] to
     * [toSec] of a [lengthSec] long video ([ended]: the last one, when the video is left or over). This is what the
     * suggestions learn from most; [reportWatch] must have been called first.
     */
    /** The next part of a list, or a chip's list, asked from [endpoint] ("browse" or "search"). */
    suspend fun more(continuation: String, endpoint: String): Result<VideoFeed> = more(continuation)

    suspend fun reportProgress(videoId: String, fromSec: Double, toSec: Double, lengthSec: Double, ended: Boolean) {}

    /**
     * The Shorts YouTube's own Shorts tab plays after [seedId] (for the account, when one is signed in), or the next
     * ones with [continuation] from the previous answer.
     */
    suspend fun shortsSequence(seedId: String?, continuation: String? = null): Result<ShortsSequence> =
        Result.failure(UnsupportedOperationException("no Shorts sequence"))

    /** Distinct audio language/dub tracks for [videoId] (empty or 1 item when single-language). */
    suspend fun audioTracks(videoId: String): Result<List<VideoAudioTrack>> = Result.success(emptyList())

    /** Polls the next batch of live chat messages for [continuation]. */
    suspend fun liveChat(continuation: String): Result<VideoLiveChatPage> = Result.success(VideoLiveChatPage())

    /** Searches inside a single channel [channelId] for [query]. */
    suspend fun channelSearch(channelId: String, query: String): Result<VideoFeed> = search(query, null)
}
