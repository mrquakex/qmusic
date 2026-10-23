package com.qmusic.spotify.model

import kotlinx.serialization.Serializable

/**
 * Represents a playlist imported from Spotify.
 */
@Serializable
data class SpotifyPlaylist(
    val id: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Int = tracks.size,
    val tracks: List<SpotifyTrack> = emptyList(),
)
