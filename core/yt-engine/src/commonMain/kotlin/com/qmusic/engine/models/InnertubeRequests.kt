package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchBody(
    val context: InnertubeContext,
    val query: String? = null,
    val params: String? = null,
)

@Serializable
data class PlayerBody(
    val context: InnertubeContext,
    val videoId: String,
    val playlistId: String? = null,
    val cpn: String? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
    val playbackContext: PlaybackContext? = null,
) {
    @Serializable
    data class PlaybackContext(
        val contentPlaybackContext: ContentPlaybackContext,
    ) {
        @Serializable
        data class ContentPlaybackContext(
            val signatureTimestamp: Int,
        )
    }
}

@Serializable
data class NextBody(
    val context: InnertubeContext,
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val index: Int? = null,
    val params: String? = null,
    val continuation: String? = null,
)

@Serializable
data class BrowseBody(
    val context: InnertubeContext,
    val browseId: String? = null,
    val params: String? = null,
    val continuation: String? = null,
)

@Serializable
data class SearchSuggestionsBody(
    val context: InnertubeContext,
    val input: String,
)

@Serializable
data class LikeBody(
    val context: InnertubeContext,
    val target: Target,
) {
    @Serializable
    data class Target(val videoId: String)
}
