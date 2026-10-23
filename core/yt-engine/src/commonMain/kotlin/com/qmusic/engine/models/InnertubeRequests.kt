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

/** One change to a playlist; actions differ in their fields, so each is written out as plain JSON. */
@Serializable
data class EditPlaylistBody(
    val context: InnertubeContext,
    val playlistId: String,
    val actions: List<kotlinx.serialization.json.JsonObject>,
)

@Serializable
data class CreatePlaylistBody(
    val context: InnertubeContext,
    val title: String,
    val privacyStatus: String = "PRIVATE",
    val videoIds: List<String> = emptyList(),
)

@Serializable
data class DeletePlaylistBody(
    val context: InnertubeContext,
    val playlistId: String,
)

@Serializable
data class SubscribeBody(
    val context: InnertubeContext,
    val channelIds: List<String>,
)

@Serializable
data class LikeBody(
    val context: InnertubeContext,
    val target: Target,
) {
    @Serializable
    data class Target(val videoId: String)
}
