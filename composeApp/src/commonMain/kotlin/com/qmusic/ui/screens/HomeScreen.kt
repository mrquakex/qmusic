package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import com.qmusic.ui.rememberDominantColor
import com.qmusic.ui.theme.QMusicHeaderLogo
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.lazy.LazyListState
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
    isOnline: Boolean,
    downloads: List<SongItem>,
    onOpenDownloads: () -> Unit,
    speedDial: List<SongItem>,
    onRandomSong: () -> Unit,
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
    onOpenExplore: (browseId: String, title: String) -> Unit,
    onSearchClick: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val ordered = remember(sections) { sections.sortedBy(::shelfRank) }
    LaunchedEffect(scrollToTop) {
        scrollToTop.collect { listState.animateScrollToItem(0) }
    }

    val tint = rememberDominantColor(speedDial.firstOrNull()?.let { it.thumbnail ?: it.thumbnails.firstOrNull()?.url })
    // Heights of the rows seen so far: the logo row is short, so "how far the page has moved" has to add up the
    // rows above the first visible one, not only that row's own offset (which made the colour vanish at once).
    val itemSizes = remember { HashMap<Int, Int>() }
    val animatedTint by animateColorAsState(tint ?: Color.Transparent, animationSpec = tween(700), label = "homeTint")

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TINT_HEIGHT)
                .graphicsLayer {
                    // The colour stays where it is and fades out as the page scrolls down, a little slower than the
                    // content moves (it drifts up at a third of the speed), so it melts away instead of sliding off.
                    // Only read while drawing: scrolling never recomposes the page.
                    val scrolled = scrolledPx(listState, itemSizes)
                    val progress = (scrolled / (size.height * TINT_FADE_PORTION)).coerceIn(0f, 1f)
                    alpha = 1f - FastOutSlowInEasing.transform(progress)
                    translationY = -scrolled / 3f
                }
                .background(
                    Brush.verticalGradient(
                        0f to animatedTint.copy(alpha = animatedTint.alpha * 0.95f),
                        0.55f to animatedTint.copy(alpha = animatedTint.alpha * 0.45f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        ) {
            // The logo, as YouTube Music has it: on the tinted top, scrolling away with the page
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QMusicHeaderLogo(modifier = Modifier.weight(1f))
                }
            }

            // No connection: say so, and lead to what still plays
            if (!isOnline) {
                item {
                    OfflineBanner(downloads.size, onOpenDownloads)
                }
                if (downloads.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = s.tabDownloads,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(downloads) { song ->
                                    ShelfCard(
                                        title = song.title,
                                        subtitle = song.artists.joinToString { it.name }.ifEmpty { "YouTube Music" },
                                        thumbnail = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                                        onClick = { onSongClick(song, downloads) },
                                    )
                                }
                            }
                        }
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

            if (isOnline && chips.isNotEmpty()) {
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

            if (speedDial.isNotEmpty()) {
                item {
                    // The account's picture and name ride on "Listen again"; they head the speed dial instead.
                    val identity = sections.firstOrNull { it.avatar != null && it.strapline != null }
                    Column {
                        SectionHeader(
                            HomeSection(title = s.speedDial, strapline = identity?.strapline, avatar = identity?.avatar),
                            onPlayAll = {},
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SpeedDial(speedDial, onSongClick, onRandomSong)
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

            if (isOnline && error != null && sections.isEmpty() && !isLoading) {
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

            items(ordered) { section ->
                HomeShelf(section, onSongClick, onSongMore, onPlayAll, onCollectionClick)
            }

            if (isOnline && !isLoadingMore && sections.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = s.exploreTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ExploreTiles(onOpenExplore, modifier = Modifier.padding(horizontal = 16.dp))
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

/**
 * YouTube Music's own order for the shelves it hands to everyone: quick picks first, then new discoveries, clips,
 * covers and remixes, and the feed's long tail. Anything not listed keeps the order the feed gave it, after these.
 */
private fun shelfRank(section: HomeSection): Int {
    val title = section.title.lowercase()
    val order = listOf(
        listOf("hızlı seçimler", "quick picks"),
        listOf("yeni keşifler", "new discoveries"),
        listOf("müzik videoları", "music videos"),
        listOf("cover", "remix", "remiks"),
        listOf("yeniden dinleyin", "listen again"),
    )
    order.forEachIndexed { rank, keys -> if (keys.any { it in title }) return rank }
    val tail = listOf(
        listOf("uzun parçalar", "long listens"),
        listOf("dinlemeye devam", "keep listening"),
        listOf("unutulan favoriler", "forgotten favorites"),
        listOf("derlenenler", "mixed for you"),
        listOf("yeni çıkanlar", "new releases"),
        listOf("topluluktan", "from the community"),
        listOf("günlük keşif", "daily discover"),
    )
    tail.forEachIndexed { rank, keys -> if (keys.any { it in title }) return 100 + rank }
    return 50
}

/**
 * "Speed dial": nine tiles to a page in three rows, the ninth being the dice that plays something at random.
 * The first tile is the song heard last and wears a white frame.
 */
@Composable
private fun SpeedDial(
    songs: List<SongItem>,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onRandomSong: () -> Unit,
) {
    val s = LocalStrings.current
    // Eight songs, then the dice as the ninth tile of the first page; further songs fill the next pages.
    val tiles: List<SongItem?> = songs.take(8) + listOf(null) + songs.drop(8)
    val pages = tiles.chunked(9)
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(pages.size) { index ->
            Column(
                modifier = Modifier
                    .fillParentMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pages[index].chunked(3).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { column, song ->
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                                if (song == null) {
                                    DiceTile(s.randomSong, onRandomSong)
                                } else {
                                    SpeedDialTile(song, framed = index == 0 && rowIndex == 0 && column == 0) {
                                        onSongClick(song, songs)
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    if (pages.size > 1) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { dot ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (dot == listState.firstVisibleItemIndex) 0.95f else 0.3f)),
                )
            }
        }
    }
}

@Composable
private fun SpeedDialTile(song: SongItem, framed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .then(if (framed) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
            contentDescription = song.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiceTile(description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2140), Color(0xFF14101F))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = QMusicIcons.Dice,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(44.dp),
        )
    }
}

/** Shown while there is no internet: what happened, and the way to the songs saved on the device. */
@Composable
private fun OfflineBanner(downloadCount: Int, onOpenDownloads: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = QMusicIcons.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.offlineTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (downloadCount > 0) s.offlineWithDownloads(downloadCount) else s.offlineWithoutDownloads,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenDownloads,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(s.goToDownloads, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** How far down the colour of the first speed-dial picture reaches. */
private val TINT_HEIGHT = 460.dp

/** How far the list has scrolled from its very top, in pixels; rows above the screen count with their measured heights. */
private fun scrolledPx(state: LazyListState, sizes: MutableMap<Int, Int>): Float {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    visible.forEach { sizes[it.index] = it.size }
    val first = visible.first()
    var above = 0
    for (index in 0 until first.index) {
        above += (sizes[index] ?: return Float.MAX_VALUE) + info.mainAxisItemSpacing
    }
    return (above - first.offset).toFloat()
}

/** The colour is gone once the page has scrolled this share of its height. */
private const val TINT_FADE_PORTION = 0.75f

/** One shelf: its header, then covers, wide clips or song columns, as its style says. */
@Composable
fun HomeShelf(
    section: HomeSection,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
) {
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

/** YouTube Music's three Explore tiles: new releases, charts, moods & genres. */
@Composable
fun ExploreTiles(onOpenExplore: (browseId: String, title: String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tiles = listOf(
        Triple(QMusicIcons.NewReleases, s.exploreNewReleases, "FEmusic_new_releases"),
        Triple(QMusicIcons.Trending, s.exploreCharts, "FEmusic_charts"),
        Triple(QMusicIcons.Mood, s.exploreMoods, "FEmusic_moods_and_genres"),
    )
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { (icon, label, browseId) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.05f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceVariant)
                    .clickable { onOpenExplore(browseId, label) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
