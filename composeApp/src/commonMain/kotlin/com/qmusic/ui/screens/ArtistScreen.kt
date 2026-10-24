package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.ArtistPage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.components.ArtistCircleCard
import com.qmusic.ui.components.SectionTitle
import com.qmusic.ui.components.ShelfCard
import com.qmusic.ui.components.upscaleThumbnail
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * An artist, laid out like YouTube Music: a full-width photo that runs under the status bar and fades into the page,
 * the name and audience over it, then subscribe, radio and a large play button, top songs and the shelves.
 * Scrolling slides the photo away slower than the page and brings in a bar with the name.
 */
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
    onToggleSubscribe: () -> Unit = {},
    onStartRadio: (SongItem) -> Unit = {},
    onShare: (ArtistPage) -> Unit = {},
    onSeeAll: (com.qmusic.engine.models.HomeSection) -> Unit = {},
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        val heroHeight = maxWidth * HERO_RATIO
        val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
        // How far the photo has scrolled away (0 at rest, the photo's height once it is gone).
        val scrolled by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) heroPx else listState.firstVisibleItemScrollOffset.toFloat()
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            page != null -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item { Hero(page, heroHeight, scrolled = { scrolled }, heroPx = heroPx) }

                item {
                    ActionRow(
                        page = page,
                        onToggleSubscribe = onToggleSubscribe,
                        onRadio = { page.topSongs.firstOrNull()?.let(onStartRadio) },
                        onPlay = { onPlayAll(page.topSongs) },
                    )
                }

                if (page.topSongs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = s.sectionTopSongs,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { onPlayAll(page.topSongs) },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(s.playAllShelf, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
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
                    Box(modifier = Modifier.padding(top = 10.dp)) {
                        HomeShelf(
                            section = section,
                            onSongClick = onSongClick,
                            onSongMore = onSongMore,
                            onPlayAll = onPlayAll,
                            onCollectionClick = onCollectionClick,
                            onSeeAll = onSeeAll,
                        )
                    }
                }

                page.description?.let { about ->
                    item { About(about) }
                }
            }
        }

        // Top bar: plain icons over the photo; a solid bar with the name fades in once the photo has scrolled away.
        // Read only while drawing, so scrolling never recomposes the page.
        val barAlpha = { ((scrolled - heroPx * 0.55f) / (heroPx * 0.3f)).coerceIn(0f, 1f) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(BgRoot.copy(alpha = barAlpha())) }
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = QMusicIcons.Back, contentDescription = s.back, tint = Color.White)
            }
            Text(
                text = page?.name ?: name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = barAlpha() },
            )
            if (page != null) {
                IconButton(onClick = { onShare(page) }) {
                    Icon(imageVector = QMusicIcons.Share, contentDescription = s.share, tint = Color.White)
                }
            }
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

/** The photo of the artist with the name over its fading bottom edge. */
@Composable
private fun Hero(page: ArtistPage, height: Dp, scrolled: () -> Float, heroPx: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(DarkSurfaceVariant),
    ) {
        page.thumbnail?.let { thumb ->
            AsyncImage(
                model = thumb.upscaleThumbnail(1440),
                contentDescription = page.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Parallax: the photo moves at half the page's speed and dims as it leaves.
                        val offset = scrolled()
                        translationY = offset * 0.5f
                        alpha = 1f - (offset / heroPx).coerceIn(0f, 1f) * 0.6f
                    },
                contentScale = ContentScale.Crop,
            )
        }
        // A light shade at the top keeps the icons readable; the bottom melts into the page.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.22f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.82f to BgRoot.copy(alpha = 0.75f),
                        1f to BgRoot,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            Text(
                text = page.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            page.audience?.let { audience ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = audience,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/** Subscribe on the left; radio and a large white play button on the right. */
@Composable
private fun ActionRow(
    page: ArtistPage,
    onToggleSubscribe: () -> Unit,
    onRadio: () -> Unit,
    onPlay: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page.channelId != null) {
            val label = if (page.subscribed) s.subscribedLabel else s.subscribe
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (page.subscribed) Color.Transparent else Color.White)
                    .then(if (page.subscribed) Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50)) else Modifier)
                    .clickable(onClick = onToggleSubscribe)
                    .padding(horizontal = 20.dp, vertical = 11.dp),
            ) {
                Text(
                    text = listOfNotNull(label, page.subscriberCount).joinToString("  "),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (page.subscribed) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (page.topSongs.isNotEmpty()) {
            IconButton(onClick = onRadio, modifier = Modifier.size(52.dp)) {
                Icon(QMusicIcons.Radio, contentDescription = s.startRadio, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(QMusicIcons.Play, contentDescription = s.play, tint = Color.Black, modifier = Modifier.size(34.dp))
            }
        }
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
    )
}

/** The photo is a little taller than wide-screen, as on YouTube Music. */
private const val HERO_RATIO = 0.86f
