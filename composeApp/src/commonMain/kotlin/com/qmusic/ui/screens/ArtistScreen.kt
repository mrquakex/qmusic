package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.ArtistPage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.components.ArtistCircleCard
import com.qmusic.ui.components.SectionTitle
import com.qmusic.ui.components.ShelfCard
import com.qmusic.ui.components.upscaleThumbnail
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/** An artist: hero image, top songs and the albums / singles / related artists shelves. */
@Composable
fun ArtistScreen(
    name: String,
    page: ArtistPage?,
    isLoading: Boolean,
    error: String?,
    downloadedIds: Set<String>,
    onBack: () -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            page != null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item { Hero(page = page) }

                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onPlayAll(page.topSongs) },
                            enabled = page.topSongs.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(QMusicIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                        OutlinedButton(
                            onClick = { onShuffle(page.topSongs) },
                            enabled = page.topSongs.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(QMusicIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                }

                page.description?.let { about ->
                    item { About(about) }
                }

                if (page.topSongs.isNotEmpty()) {
                    item { SectionTitle("Top songs", Modifier.padding(horizontal = 16.dp)) }
                    items(page.topSongs, key = { it.id }) { song ->
                        SongListItem(
                            song = song,
                            isFavorite = isFavoritePredicate(song.id),
                            onClick = { onSongClick(song, page.topSongs) },
                            onToggleFavorite = { onToggleFavorite(song) },
                            onMore = { onSongMore(song) },
                            isDownloaded = song.id in downloadedIds,
                            flat = true,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                items(page.sections) { section ->
                    Column {
                        SectionTitle(section.title, Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(section.songs) { song ->
                                ShelfCard(
                                    title = song.title,
                                    subtitle = song.artists.joinToString { it.name },
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
                                        subtitle = collection.subtitle.orEmpty(),
                                        thumbnail = collection.thumbnail,
                                        onClick = { onCollectionClick(collection) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Back button floats over the hero image
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(
                imageVector = QMusicIcons.Back,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        if (page == null && !isLoading && error == null) {
            Text(
                text = name,
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hero(page: ArtistPage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(DarkSurfaceVariant),
    ) {
        page.thumbnail?.let { thumb ->
            AsyncImage(
                model = thumb.upscaleThumbnail(900),
                contentDescription = page.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to BgRoot)),
        )
        Text(
            text = page.name,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun About(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
    )
}
