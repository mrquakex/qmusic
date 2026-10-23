package com.qmusic.spotify.model

import kotlinx.serialization.Serializable

/**
 * Represents a track imported from Spotify.
 */
@Serializable
data class SpotifyTrack(
    val id: String = "",
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
) {
    /**
     * Primary or comma-separated artist name string.
     */
    val artist: String
        get() = artists.joinToString(", ")
}
