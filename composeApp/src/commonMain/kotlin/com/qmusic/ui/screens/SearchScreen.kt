package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.ArtistItem
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.SearchFilter
import com.qmusic.engine.models.SearchResultsPage
import com.qmusic.engine.models.SearchSuggestion
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.components.ArtistCircleCard
import com.qmusic.ui.components.SectionTitle
import com.qmusic.ui.components.ShelfCard
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * Three states, like YouTube Music: an empty box lists past searches, typing lists autocomplete
 * suggestions, and submitting a search shows the results grouped into songs, albums, artists and playlists.
 */
@Composable
fun SearchScreen(
    query: String,
    filter: SearchFilter?,
    page: SearchResultsPage?,
    isSearching: Boolean,
    error: String?,
    showResults: Boolean,
    suggestions: List<SearchSuggestion>,
    history: List<String>,
    downloadedIds: Set<String>,
    onQueryChange: (String) -> Unit,
    onFilterChange: (SearchFilter?) -> Unit,
    onSearch: () -> Unit,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(50),
            placeholder = { Text(s.searchFieldPlaceholder) },
            leadingIcon = {
                Icon(
                    imageVector = QMusicIcons.Search,
                    contentDescription = s.search,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = QMusicIcons.Close,
                            contentDescription = s.clearSearch,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                onSearch()
            }),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = DarkSurfaceVariant,
                unfocusedContainerColor = DarkSurfaceVariant,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (showResults) {
            FilterRow(filter = filter, onFilterChange = onFilterChange, onSearch = onSearch)
            Spacer(modifier = Modifier.height(16.dp))
            SearchResults(
                filter = filter,
                page = page,
                isSearching = isSearching,
                error = error,
                downloadedIds = downloadedIds,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                onCollectionClick = onCollectionClick,
                onArtistClick = onArtistClick,
                onToggleFavorite = onToggleFavorite,
                isFavoritePredicate = isFavoritePredicate,
            )
        } else if (query.isBlank()) {
            SearchHistory(
                history = history,
                onHistoryClick = onHistoryClick,
                onHistoryDelete = onHistoryDelete,
                onClearHistory = onClearHistory,
            )
        } else {
            SuggestionList(
                suggestions = suggestions,
                onSuggestionClick = onSuggestionClick,
                onFill = { onQueryChange(it.text) },
            )
        }
    }
}

@Composable
private fun FilterRow(
    filter: SearchFilter?,
    onFilterChange: (SearchFilter?) -> Unit,
    onSearch: () -> Unit,
) {
    val s = LocalStrings.current
    val options = listOf<Pair<String, SearchFilter?>>(
        s.filterAll to null,
        s.filterSongs to SearchFilter.SONGS,
        s.filterAlbums to SearchFilter.ALBUMS,
        s.filterArtists to SearchFilter.ARTISTS,
        s.filterPlaylists to SearchFilter.COMMUNITY_PLAYLISTS,
        s.filterVideos to SearchFilter.VIDEOS,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = filter == value,
                onClick = {
                    onFilterChange(value)
                    onSearch()
                },
                label = { Text(label) },
                shape = RoundedCornerShape(10.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White,
                    selectedLabelColor = Color.Black,
                    containerColor = DarkSurfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
private fun SearchResults(
    filter: SearchFilter?,
    page: SearchResultsPage?,
    isSearching: Boolean,
    error: String?,
    downloadedIds: Set<String>,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
) {
    val s = LocalStrings.current
    when {
        isSearching -> CenteredBox {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }

        error != null -> CenteredBox {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        page == null || page.isEmpty -> CenteredBox {
            Text(
                text = s.noResultsFound,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            // With a filter chip active the one matching group fills the screen as a plain list; "All" mixes
            // the groups, with horizontal shelves for the visual ones.
            val single = filter != null
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                if (!single) {
                    page.top?.let { top ->
                        item { SectionTitle(s.sectionTopResult) }
                        item {
                            ResultRow(
                                title = top.name,
                                subtitle = top.subtitle,
                                thumbnail = top.thumbnail,
                                round = true,
                                onClick = { onArtistClick(top) },
                            )
                        }
                    }
                }

                if (page.songs.isNotEmpty()) {
                    if (!single) item { SectionTitle(s.sectionSongs) }
                    items(page.songs) { song ->
                        SongListItem(
                            song = song,
                            isFavorite = isFavoritePredicate(song.id),
                            onClick = { onSongClick(song, page.songs) },
                            onToggleFavorite = { onToggleFavorite(song) },
                            onMore = { onSongMore(song) },
                            isDownloaded = song.id in downloadedIds,
                            flat = true,
                        )
                    }
                }

                if (page.albums.isNotEmpty()) {
                    if (single) {
                        items(page.albums) { album ->
                            ResultRow(album.title, album.subtitle, album.thumbnail, round = false, onClick = { onCollectionClick(album) })
                        }
                    } else {
                        item { SectionTitle(s.sectionAlbums) }
                        item { CollectionShelf(page.albums, onCollectionClick) }
                    }
                }

                if (page.artists.isNotEmpty()) {
                    if (single) {
                        items(page.artists) { artist ->
                            ResultRow(artist.name, artist.subtitle, artist.thumbnail, round = true, onClick = { onArtistClick(artist) })
                        }
                    } else {
                        item { SectionTitle(s.sectionArtists) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(page.artists) { artist ->
                                    ArtistCircleCard(
                                        name = artist.name,
                                        subtitle = artist.subtitle,
                                        thumbnail = artist.thumbnail,
                                        onClick = { onArtistClick(artist) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (page.playlists.isNotEmpty()) {
                    if (single) {
                        items(page.playlists) { playlist ->
                            ResultRow(playlist.title, playlist.subtitle, playlist.thumbnail, round = false, onClick = { onCollectionClick(playlist) })
                        }
                    } else {
                        item { SectionTitle(s.sectionPlaylists) }
                        item { CollectionShelf(page.playlists, onCollectionClick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionShelf(items: List<CollectionItem>, onClick: (CollectionItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items) { collection ->
            ShelfCard(
                title = collection.title,
                subtitle = collection.subtitle.orEmpty(),
                thumbnail = collection.thumbnail,
                onClick = { onClick(collection) },
            )
        }
    }
}

/** A tappable row with artwork: albums, playlists and artists in a single-group result. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    thumbnail: String?,
    round: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(if (round) CircleShape else RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchHistory(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val s = LocalStrings.current
    if (history.isEmpty()) {
        CenteredBox {
            Text(
                text = s.searchHistoryEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = s.recentSearches,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onClearHistory) { Text(s.clearAll) }
            }
        }
        items(history, key = { it }) { entry ->
            SuggestionRow(
                text = entry,
                leading = QMusicIcons.History,
                onClick = { onHistoryClick(entry) },
                trailing = QMusicIcons.Close,
                trailingDescription = s.removeFromHistory,
                onTrailingClick = { onHistoryDelete(entry) },
            )
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: List<SearchSuggestion>,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onFill: (SearchSuggestion) -> Unit,
) {
    val s = LocalStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        items(suggestions, key = { "${it.isHistory}:${it.text}" }) { suggestion ->
            SuggestionRow(
                text = suggestion.text,
                leading = if (suggestion.isHistory) QMusicIcons.History else QMusicIcons.Search,
                onClick = { onSuggestionClick(suggestion) },
                trailing = QMusicIcons.FillQuery,
                trailingDescription = s.useThisSuggestion,
                onTrailingClick = { onFill(suggestion) },
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    leading: ImageVector,
    onClick: () -> Unit,
    trailing: ImageVector,
    trailingDescription: String,
    onTrailingClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onTrailingClick) {
            Icon(
                imageVector = trailing,
                contentDescription = trailingDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}
