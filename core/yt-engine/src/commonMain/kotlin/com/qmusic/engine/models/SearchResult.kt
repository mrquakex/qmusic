package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val items: List<SongItem>,
    val continuation: String? = null,
)

enum class SearchFilter(val value: String) {
    ALL(""),
    SONGS("EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS("EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    ARTISTS("EgWKAQIgAWoMEA4QChADEAQQCRAF"),
    COMMUNITY_PLAYLISTS("EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
    FEATURED_PLAYLISTS("EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"),
}
