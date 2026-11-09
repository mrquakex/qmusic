package com.qmusic.engine.video

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.YouTubeLocale

/** What search can be narrowed to. */
object VideoSearchFilter {
    const val VIDEOS = "EgIQAQ%3D%3D"
    const val CHANNELS = "EgIQAg%3D%3D"
    const val PLAYLISTS = "EgIQAw%3D%3D"
    const val LIVE = "EgJAAQ%3D%3D"
    /** Shorts only. */
    const val SHORTS = "EgIQCQ%3D%3D"
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
}
