package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestion(
    val text: String,
    /** True when the suggestion comes from the signed-in account's own search history. */
    val isHistory: Boolean = false,
)

@Serializable
enum class CollectionKind { PLAYLIST, ALBUM, ARTIST }

/** A tappable shelf entry that opens a track list (playlist or album) instead of playing directly. */
@Serializable
data class CollectionItem(
    val browseId: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val kind: CollectionKind = CollectionKind.PLAYLIST,
)

/** One shelf of the YouTube Music home page ("Quick picks", "Listen again", ...). */
@Serializable
data class HomeSection(
    val title: String,
    val songs: List<SongItem> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
)

@Serializable
data class CollectionPage(
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val songs: List<SongItem> = emptyList(),
)

@Serializable
data class ArtistItem(
    val browseId: String,
    val name: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
)

/** Search results grouped by kind. [top] is YouTube Music's single best match, when it shows one. */
@Serializable
data class SearchResultsPage(
    val top: ArtistItem? = null,
    val songs: List<SongItem> = emptyList(),
    val albums: List<CollectionItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val playlists: List<CollectionItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = top == null && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

@Serializable
data class ArtistPage(
    val name: String,
    val thumbnail: String? = null,
    val description: String? = null,
    val topSongs: List<SongItem> = emptyList(),
    /** Albums, singles, playlists, "Fans might also like" ... */
    val sections: List<HomeSection> = emptyList(),
)

@Serializable
data class Lyrics(
    val text: String,
    val source: String? = null,
    /** Lines with the moment each one starts; empty when the lyrics are plain text only. */
    val lines: List<LyricLine> = emptyList(),
) {
    val isSynced: Boolean get() = lines.isNotEmpty()
}

@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
)
