package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestion(
    val text: String,
    /** True when the suggestion comes from the signed-in account's own search history. */
    val isHistory: Boolean = false,
    /** Set for the songs, albums, playlists and artists YouTube Music suggests among the words. */
    val song: SongItem? = null,
    val collection: CollectionItem? = null,
    val artist: ArtistItem? = null,
    /** "Song • Narkoz Ex • 55M plays" under a song/album/artist suggestion. */
    val subtitle: String? = null,
) {
    val isRich: Boolean get() = song != null || collection != null || artist != null
}

@Serializable
enum class CollectionKind { PLAYLIST, ALBUM, ARTIST, PODCAST }

/** A tappable shelf entry that opens a track list (playlist or album) instead of playing directly. */
@Serializable
data class CollectionItem(
    val browseId: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val kind: CollectionKind = CollectionKind.PLAYLIST,
)

/** How a home shelf is drawn: square cards, wide video cards, or rows of songs in columns of four. */
enum class SectionStyle { CARDS, VIDEOS, ROWS }

/** One shelf of the YouTube Music home page ("Quick picks", "Listen again", ...). */
@Serializable
data class HomeSection(
    val title: String,
    val songs: List<SongItem> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
    /** The small line above the title ("ORVIX", "BECAUSE YOU LISTENED TO"). */
    val strapline: String? = null,
    /** The round picture next to the title (the account, or the artist the shelf is about). */
    val avatar: String? = null,
    val style: SectionStyle = SectionStyle.CARDS,
    /** The shelf offers "Play all". */
    val canPlayAll: Boolean = false,
    /** The full list behind the shelf ("See all"), when YouTube Music has one. */
    val moreBrowseId: String? = null,
    val moreParams: String? = null,
)

/** A tile of "Moods & genres" ("Chill", "Rock", ...), opening its own page. */
@Serializable
data class MoodButton(
    val text: String,
    val browseId: String,
    val params: String?,
    /** The colour of its left edge, as 0xAARRGGBB. */
    val color: Long? = null,
)

@Serializable
data class MoodGroup(
    val title: String,
    val buttons: List<MoodButton>,
)

/** A page of Explore: new releases, charts, moods & genres, or one mood / genre. */
@Serializable
data class ExplorePage(
    val title: String,
    val sections: List<HomeSection> = emptyList(),
    val moods: List<MoodGroup> = emptyList(),
)

/** A mood filter at the top of the home page ("Energize", "Relax", ...). */
@Serializable
data class HomeChip(
    val text: String,
    val params: String,
)

/** One page of the home feed: the filter chips come with the first page only. */
data class HomePage(
    val chips: List<HomeChip>,
    val sections: List<HomeSection>,
)

@Serializable
data class CollectionPage(
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val songs: List<SongItem> = emptyList(),
    /** "Album • 2005" / "Playlist • 2025": what it is and when. */
    val kindLine: String? = null,
    /** "12 songs • 48 minutes". */
    val countLine: String? = null,
    val description: String? = null,
    /** The account owns this playlist: songs can be removed and moved, the playlist deleted. */
    val editable: Boolean = false,
    /** Each song's place in the playlist (videoId -> setVideoId), needed to remove or move it. */
    val setVideoIds: Map<String, String> = emptyMap(),
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
    /** More results of a filtered search, fetched as the list is scrolled to its end. */
    val continuation: String? = null,
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
    /** "11.2M monthly audience", as YouTube Music words it. */
    val audience: String? = null,
    /** The channel to subscribe to. */
    val channelId: String? = null,
    val subscribed: Boolean = false,
    /** "232K", shown on the subscribe button. */
    val subscriberCount: String? = null,
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
