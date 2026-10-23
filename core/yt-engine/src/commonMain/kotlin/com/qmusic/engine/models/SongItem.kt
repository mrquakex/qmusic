package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val id: String? = null,
)

@Serializable
data class Album(
    val name: String,
    val id: String,
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SongItem(
    val id: String,
    val title: String,
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val duration: Int? = null,
    val thumbnail: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val explicit: Boolean = false,
    val shareLink: String = "https://music.youtube.com/watch?v=$id",
    /** YouTube Music's `musicVideoType` (ATV = album track, OMV = official music video, UGC = user video). */
    val videoType: String? = null,
) {
    /** True for official clips and uploads, which have a picture worth showing; album tracks are audio only. */
    val hasVideo: Boolean
        get() = videoType == "MUSIC_VIDEO_TYPE_OMV" ||
            videoType == "MUSIC_VIDEO_TYPE_UGC" ||
            videoType == "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"
}
