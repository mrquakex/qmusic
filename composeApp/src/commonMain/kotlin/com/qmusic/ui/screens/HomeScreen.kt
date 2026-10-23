package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.qmusic.engine.models.HomeChip
import com.qmusic.engine.models.SectionStyle
import kotlinx.coroutines.flow.Flow
import com.qmusic.ui.theme.QMusicIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.ui.components.ArtistCircleCard
import com.qmusic.ui.components.ShelfCard
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant

/**
 * The home feed, laid out like YouTube Music's: mood chips, then shelves of square covers, wide clips, or rows of
 * songs in columns of four. Pull down to refresh; the Home tab scrolls it back to the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sections: List<HomeSection>,
    chips: List<HomeChip>,
    selectedChip: String?,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    isLoggedIn: Boolean,
    scrollToTop: Flow<Unit>,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onChipClick: (HomeChip) -> Unit,
    onSearchClick: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTop) {
        scrollToTop.collect { listState.animateScrollToItem(0) }
    }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        ) {
            // Header: fades out and drifts up as the page scrolls away from it
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .graphicsLayer {
                            val scrolled = if (listState.firstVisibleItemIndex == 0) {
                                listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                HEADER_FADE_PX
                            }
                            alpha = (1f - scrolled / HEADER_FADE_PX).coerceIn(0f, 1f)
                            translationY = -scrolled * 0.35f
                        },
                ) {
                    Text(
                        text = "QMusic",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = s.homeSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            // Search bar trigger
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clickable(onClick = onSearchClick),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = QMusicIcons.Search,
                            contentDescription = s.search,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = s.searchPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Sign-in prompt: the personalised home only exists for a signed-in account
            if (!isLoggedIn) {
                item {
                    OutlinedCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = DarkSurface),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.signInYouTubeMusic,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = s.signInPromptSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = onSignIn,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(s.signIn)
                            }
                        }
                    }
                }
            }

            if (chips.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(chips) { chip ->
                            val selected = chip.params == selectedChip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) Color.White else DarkSurfaceVariant)
                                    .clickable { onChipClick(chip) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = chip.text,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading && sections.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            if (error != null && sections.isEmpty() && !isLoading) {
                item {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) { Text(s.tryAgain) }
                    }
                }
            }

            items(sections) { section ->
                Column {
                    SectionHeader(section, onPlayAll)
                    Spacer(modifier = Modifier.height(12.dp))
                    when (section.style) {
                        SectionStyle.ROWS -> SongColumns(section, onSongClick, onSongMore)
                        SectionStyle.VIDEOS -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(section.songs) { song ->
                                VideoCard(song) { onSongClick(song, section.songs) }
                            }
                        }

                        SectionStyle.CARDS -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(section.songs) { song ->
                                ShelfCard(
                                    title = song.title,
                                    subtitle = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
                                    thumbnail = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                                    onClick = { onSongClick(song, section.songs) },
                                )
                            }
                            items(section.collections) { collection ->
                                if (collection.kind == CollectionKind.ARTIST) {
                                    ArtistCircleCard(
                                        name = collection.title,
                                        subtitle = collection.subtitle,
                                        thumbnail = collection.thumbnail,
                                        onClick = { onCollectionClick(collection) },
                                    )
                                } else {
                                    ShelfCard(
                                        title = collection.title,
                                        subtitle = collection.subtitle ?: "",
                                        thumbnail = collection.thumbnail,
                                        onClick = { onCollectionClick(collection) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoadingMore) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

/** The shelf title with the small line above it, the round picture beside it and "Play all" at the end. */
@Composable
private fun SectionHeader(section: HomeSection, onPlayAll: (List<SongItem>) -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (section.avatar != null) {
            AsyncImage(
                model = section.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            section.strapline?.let { strapline ->
                Text(
                    text = strapline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (section.canPlayAll && section.songs.isNotEmpty()) {
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onPlayAll(section.songs) },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(s.playAllShelf, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** "Quick picks": songs as rows, four to a column, with the columns paging sideways. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongColumns(
    section: HomeSection,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(section.songs.chunked(4)) { column ->
            Column(modifier = Modifier.fillParentMaxWidth(0.9f)) {
                column.forEach { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSongClick(song, section.songs) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                            contentDescription = song.title,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkSurfaceVariant),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onSongMore(song) }) {
                            Icon(
                                imageVector = QMusicIcons.More,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A music video: wide picture with its title and artist underneath. */
@Composable
private fun VideoCard(song: SongItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(290.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
            contentDescription = song.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SongListItem(
    song: SongItem,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    flat: Boolean = false,
) {
    val s = LocalStrings.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (flat) androidx.compose.ui.graphics.Color.Transparent else DarkSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (flat) androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp) else androidx.compose.foundation.layout.PaddingValues(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDownloaded) {
                            Icon(
                                imageVector = QMusicIcons.DownloadDone,
                                contentDescription = s.downloaded,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else if (downloadProgress != null) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                        contentDescription = if (isFavorite) s.favorited else s.favorite,
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onMore != null) {
                    IconButton(onClick = onMore) {
                        Icon(
                            imageVector = QMusicIcons.More,
                            contentDescription = s.moreOptions,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val HEADER_FADE_PX = 220f
