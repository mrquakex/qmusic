package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    onBack: () -> Unit,
    recentSongs: List<SongItem>,
    suggestedSearches: List<String>,
    onOpenExplore: (browseId: String, title: String) -> Unit,
    onVoiceSearch: (() -> Unit)?,
    onRecognizeSong: (() -> Unit)?,
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
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            shape = RoundedCornerShape(50),
            placeholder = { Text(s.searchFieldPlaceholder) },
            leadingIcon = {
                if (query.isNotEmpty() || showResults) {
                    IconButton(onClick = {
                        keyboardController?.hide()
                        onBack()
                    }) {
                        Icon(imageVector = QMusicIcons.Back, contentDescription = s.back, tint = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Icon(
                        imageVector = QMusicIcons.Search,
                        contentDescription = s.search,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                } else if (onVoiceSearch != null) {
                    IconButton(onClick = onVoiceSearch) {
                        Icon(
                            imageVector = QMusicIcons.Mic,
                            contentDescription = s.voiceSearch,
                            tint = MaterialTheme.colorScheme.onSurface,
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
            textStyle = MaterialTheme.typography.titleMedium,
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
        if (onRecognizeSong != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onRecognizeSong,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
            ) {
                Icon(imageVector = QMusicIcons.Waveform, contentDescription = s.recognizeSong, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        }

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
                recentSongs = recentSongs,
                suggestedSearches = suggestedSearches,
                onOpenExplore = onOpenExplore,
                onSongClick = onSongClick,
                onFill = onQueryChange,
                onHistoryClick = onHistoryClick,
                onHistoryDelete = onHistoryDelete,
                onClearHistory = onClearHistory,
            )
        } else {
            SuggestionList(
                query = query,
                suggestions = suggestions,
                onSuggestionClick = onSuggestionClick,
                onFill = { onQueryChange(it.text) },
                onSongMore = onSongMore,
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

/**
 * The empty box, as on YouTube Music: what was played lately as wide cards, then the past searches. Tapping a
 * search runs it, the arrow puts it in the box to edit, and a long press removes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistory(
    history: List<String>,
    recentSongs: List<SongItem>,
    suggestedSearches: List<String>,
    onOpenExplore: (browseId: String, title: String) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onFill: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val s = LocalStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
    ) {
        if (recentSongs.isNotEmpty()) {
            item {
                Text(
                    text = s.recentlyPlayed,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentSongs.take(12), key = { it.id }) { song ->
                        Column(
                            modifier = Modifier
                                .width(170.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSongClick(song, listOf(song)) },
                        ) {
                            AsyncImage(
                                model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                                contentDescription = song.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurfaceVariant),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.recentSearches,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearHistory) { Text(s.clearAll) }
                }
            }
            items(history, key = { it }) { entry ->
                SuggestionRow(
                    text = entry,
                    query = "",
                    leading = QMusicIcons.History,
                    onClick = { onHistoryClick(entry) },
                    onLongClick = { onHistoryDelete(entry) },
                    onFill = { onFill(entry) },
                )
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            ExploreTiles(onOpenExplore)
            Spacer(Modifier.height(12.dp))
        }
        if (suggestedSearches.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = s.moreYouMayLike,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(suggestedSearches, key = { "more:$it" }) { entry ->
                SuggestionRow(
                    text = entry,
                    query = "",
                    leading = QMusicIcons.Search,
                    onClick = { onHistoryClick(entry) },
                    onFill = { onFill(entry) },
                )
            }
        }
    }
}

/**
 * While typing: the first word suggestions, then the songs, albums and artists YouTube Music suggests, then the
 * rest of the words, the way YouTube Music orders them.
 */
@Composable
private fun SuggestionList(
    query: String,
    suggestions: List<SearchSuggestion>,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onFill: (SearchSuggestion) -> Unit,
    onSongMore: (SongItem) -> Unit,
) {
    val words = suggestions.filterNot { it.isRich }
    val rich = suggestions.filter { it.isRich }
    val ordered = words.take(2) + rich + words.drop(2)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
    ) {
        items(ordered, key = { "${it.isHistory}:${it.isRich}:${it.text}:${it.song?.id ?: it.artist?.browseId ?: it.collection?.browseId}" }) { suggestion ->
            if (suggestion.isRich) {
                RichSuggestionRow(suggestion, onClick = { onSuggestionClick(suggestion) }, onSongMore = onSongMore)
            } else {
                SuggestionRow(
                    text = suggestion.text,
                    query = query,
                    leading = if (suggestion.isHistory) QMusicIcons.History else QMusicIcons.Search,
                    onClick = { onSuggestionClick(suggestion) },
                    onFill = { onFill(suggestion) },
                )
            }
        }
    }
}

/** A word: icon, the text with what was typed in bold, and the arrow that puts it in the box. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(
    text: String,
    query: String,
    leading: ImageVector,
    onClick: () -> Unit,
    onFill: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, end = 24.dp).size(24.dp),
        )
        val typed = query.trim()
        val label = if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(0, typed.length)) }
                append(text.substring(typed.length))
            }
        } else {
            AnnotatedString(text)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onFill) {
            Icon(
                imageVector = QMusicIcons.FillQuery,
                contentDescription = s.useThisSuggestion,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A suggested song, album, playlist or artist: picture (round for artists), title, what it is, and "more". */
@Composable
private fun RichSuggestionRow(suggestion: SearchSuggestion, onClick: () -> Unit, onSongMore: (SongItem) -> Unit) {
    val picture = suggestion.song?.let { it.thumbnail ?: it.thumbnails.firstOrNull()?.url }
        ?: suggestion.artist?.thumbnail
        ?: suggestion.collection?.thumbnail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = picture,
            contentDescription = suggestion.text,
            modifier = Modifier
                .size(56.dp)
                .clip(if (suggestion.artist != null) CircleShape else RoundedCornerShape(6.dp))
                .background(DarkSurfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            suggestion.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        suggestion.song?.let { song ->
            IconButton(onClick = { onSongMore(song) }) {
                Icon(imageVector = QMusicIcons.More, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}
