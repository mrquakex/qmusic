package com.qmusic.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.HomeChip
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SectionStyle
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.asBackdrop
import com.qmusic.ui.components.ArtistCircleCard
import com.qmusic.ui.components.SharpCover
import com.qmusic.ui.components.ShelfCard
import com.qmusic.ui.components.warmOnPress
import com.qmusic.ui.rememberDominantColor
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicHeaderLogo
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Responsive horizontal inset for the Music home feed across phone, portrait tablet, and landscape tablet. */
internal fun musicHomeHorizontalPaddingDp(widthDp: Float): Int = when {
    widthDp >= 900f -> 36
    widthDp >= 600f -> 24
    else -> 16
}

/** Wide two-column Bento hero activates at 720dp+ (tablets and desktop). */
internal fun isMusicBentoWide(widthDp: Float): Boolean = widthDp >= 720f

/** First card-based shelf receives larger editorial featured cards with a subtle corner play badge. */
internal fun featuredCardsShelfIndex(sections: List<HomeSection>): Int =
    sections.indexOfFirst { it.style == SectionStyle.CARDS }

/** Place the Explore discovery strip after the second shelf so it is reachable without scrolling to the bottom. */
internal fun exploreStripAfterShelfIndex(sectionCount: Int): Int =
    if (sectionCount <= 0) -1 else minOf(1, sectionCount - 1)

/**
 * Nuvelo Music editorial monochrome home feed:
 * ambient cover wash on scroll, full-pill mood chips, responsive Bento Hero + Quick-Resume grid,
 * framed Quick Picks with active-track highlights, featured cover shelves, and pulsing monochrome skeletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sections: List<HomeSection>,
    isOnline: Boolean,
    downloads: List<SongItem>,
    onOpenDownloads: () -> Unit,
    speedDial: List<SongItem>,
    speedDialPins: Set<String> = emptySet(),
    onTogglePin: (SongItem) -> Unit = {},
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
    /** YouTube Music's Explore tiles (new releases, charts, moods); not with a Spotify account. */
    showExplore: Boolean = true,
    currentTrack: SongItem? = null,
    isPlaying: Boolean = false,
    desktop: Boolean = false,
    onSeeAll: (HomeSection) -> Unit = {},
    onSearchClick: () -> Unit,
    /** Opens the messages; null where there are none. */
    onChatClick: (() -> Unit)? = null,
    chatUnread: Int = 0,
    /** The settings (no longer a tab of the bottom bar: the gear at the top of the home page). */
    onSettingsClick: (() -> Unit)? = null,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val ordered = remember(sections) { sections.sortedBy(::shelfRank) }
    val featuredIndex = remember(ordered) { featuredCardsShelfIndex(ordered) }
    val exploreAfterIndex = remember(ordered.size) { exploreStripAfterShelfIndex(ordered.size) }

    LaunchedEffect(scrollToTop) {
        scrollToTop.collect { listState.animateScrollToItem(0) }
    }

    val tintSong = currentTrack ?: speedDial.firstOrNull() ?: ordered.firstOrNull()?.songs?.firstOrNull()
    val found = rememberDominantColor(tintSong?.let { it.thumbnail ?: it.thumbnails.firstOrNull()?.url })
    if (found != null) lastHomeColour = found
    val tint = (found ?: lastHomeColour)?.asBackdrop()
    val itemSizes = remember { HashMap<Int, Int>() }
    val animatedTint by animateColorAsState(tint ?: Color.Transparent, animationSpec = tween(700), label = "homeTint")

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val horizontalPad = musicHomeHorizontalPaddingDp(widthDp).dp
        val wideBento = isMusicBentoWide(widthDp)

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Subtle cover-derived atmospheric wash at the top, fading gently as the user scrolls.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TINT_HEIGHT)
                    .graphicsLayer {
                        val scrolled = scrolledPx(listState, itemSizes)
                        val progress = (scrolled / (size.height * TINT_FADE_PORTION)).coerceIn(0f, 1f)
                        alpha = 1f - FastOutSlowInEasing.transform(progress)
                        translationY = -scrolled / 3f
                    }
                    .background(
                        Brush.verticalGradient(
                            0f to animatedTint.copy(alpha = animatedTint.alpha * 0.90f),
                            0.55f to animatedTint.copy(alpha = animatedTint.alpha * 0.40f),
                            1f to Color.Transparent,
                        ),
                    ),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (desktop) 30.dp else 22.dp),
                contentPadding = PaddingValues(top = if (desktop) 18.dp else 12.dp, bottom = 120.dp),
            ) {
                // Mobile top bar: Nuvelo logo + Cast, Settings/Messages, Search
                if (!desktop) {
                    item(key = "mobile_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = horizontalPad, end = 8.dp, top = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QMusicHeaderLogo(modifier = Modifier.weight(1f))
                            com.qmusic.ui.tv.TvButton(tint = Ink, modifier = Modifier.size(46.dp))
                            if (onSettingsClick != null) {
                                IconButton(onClick = onSettingsClick) {
                                    Icon(QMusicIcons.Settings, contentDescription = s.tabSettings, tint = Ink)
                                }
                            } else if (onChatClick != null) {
                                IconButton(onClick = onChatClick) {
                                    Box {
                                        Icon(QMusicIcons.Chat, contentDescription = null, tint = Ink)
                                        if (chatUnread > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 6.dp, y = (-6).dp)
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .background(Ink),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    if (chatUnread > 9) "9+" else chatUnread.toString(),
                                                    color = InkInverse,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = onSearchClick) {
                                Icon(QMusicIcons.Search, contentDescription = s.tabSearch, tint = Ink)
                            }
                        }
                    }
                }

                // Offline banner & locally saved downloads
                if (!isOnline) {
                    item(key = "offline_banner") {
                        OfflineBanner(
                            downloadCount = downloads.size,
                            onOpenDownloads = onOpenDownloads,
                            horizontalPad = horizontalPad,
                        )
                    }
                    if (downloads.isNotEmpty()) {
                        item(key = "offline_downloads") {
                            Column {
                                SectionHeader(
                                    section = HomeSection(title = s.tabDownloads),
                                    onPlayAll = onPlayAll,
                                    horizontalPad = horizontalPad,
                                    desktop = desktop,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = horizontalPad),
                                    horizontalArrangement = Arrangement.spacedBy(if (desktop) 18.dp else 14.dp),
                                ) {
                                    items(downloads, key = { it.id }) { song ->
                                        ShelfCard(
                                            title = song.title,
                                            subtitle = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                                            thumbnail = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                                            onClick = { onSongClick(song, downloads) },
                                            cardWidth = if (desktop) 172.dp else 148.dp,
                                            isCurrent = currentTrack?.id == song.id,
                                            modifier = Modifier.warmOnPress(song),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Sign-in prompt (aligned cleanly with horizontalPad + Nuvelo hairline border)
                if (!isLoggedIn) {
                    item(key = "signin_prompt") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPad)
                                .clip(RoundedCornerShape(16.dp))
                                .background(DarkSurface)
                                .border(0.5.dp, BorderSubtle, RoundedCornerShape(16.dp))
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.signInYouTubeMusic,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = s.signInPromptSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Ink)
                                    .clickable(onClick = onSignIn)
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = s.signIn,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = InkInverse,
                                )
                            }
                        }
                    }
                }

                // Full-pill monochrome mood/filter chips (matching Video chip language)
                if (isOnline && chips.isNotEmpty()) {
                    item(key = "mood_chips") {
                        val hasTint = animatedTint.alpha > 0.01f && !com.qmusic.ui.theme.lightTheme
                        val chipContainer = if (hasTint) {
                            androidx.compose.ui.graphics.lerp(Ink, animatedTint.copy(alpha = 1f), 0.72f)
                                .copy(alpha = 0.20f)
                        } else {
                            DarkSurfaceVariant.copy(alpha = 0.9f)
                        }
                        val chipBorder = if (hasTint) {
                            androidx.compose.ui.graphics.lerp(Ink, animatedTint.copy(alpha = 1f), 0.5f)
                                .copy(alpha = 0.22f)
                        } else {
                            BorderSubtle
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(chips) { chip ->
                                val selected = chip.params == selectedChip
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) Ink else chipContainer)
                                        .border(
                                            width = if (selected) 1.dp else 0.5.dp,
                                            color = if (selected) Ink else chipBorder,
                                            shape = RoundedCornerShape(50),
                                        )
                                        .clickable { onChipClick(chip) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = chip.text,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) InkInverse else Ink,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // Editorial Bento Hero (wide >= 720dp) or Compact 2x3 Quick-Resume Grid (phone / narrow)
                if (speedDial.isNotEmpty() || currentTrack != null) {
                    item(key = "quick_resume_bento") {
                        val identity = sections.firstOrNull { it.avatar != null && it.strapline != null }
                        QuickResumeSection(
                            title = s.speedDial,
                            strapline = identity?.strapline,
                            avatar = identity?.avatar,
                            songs = speedDial,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            wideBento = wideBento,
                            horizontalPad = horizontalPad,
                            pins = speedDialPins,
                            onSongClick = onSongClick,
                            onRandomSong = onRandomSong,
                            onTogglePin = onTogglePin,
                        )
                    }
                }

                // Monochrome pulsing skeleton while initial feed loads
                if (isLoading && sections.isEmpty()) {
                    item(key = "home_skeleton") {
                        HomeLoadingSkeleton(
                            horizontalPad = horizontalPad,
                            wideBento = wideBento,
                            showTopBlock = speedDial.isEmpty() && currentTrack == null,
                        )
                    }
                }

                // Error state with monochrome retry pill
                if (isOnline && error != null && sections.isEmpty() && !isLoading) {
                    item(key = "home_error") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPad, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Ink)
                                    .clickable(onClick = onRefresh)
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Text(s.tryAgain, style = MaterialTheme.typography.labelLarge, color = InkInverse)
                            }
                        }
                    }
                }

                // Main editorial shelves + mid-feed Explore strip
                itemsIndexed(ordered) { index, section ->
                    Column(verticalArrangement = Arrangement.spacedBy(if (desktop) 30.dp else 22.dp)) {
                        HomeShelf(
                            section = section,
                            onSongClick = onSongClick,
                            onSongMore = onSongMore,
                            onPlayAll = onPlayAll,
                            onCollectionClick = onCollectionClick,
                            onSeeAll = onSeeAll,
                            desktop = desktop,
                            horizontalPad = horizontalPad,
                            currentTrackId = currentTrack?.id,
                            featured = index == featuredIndex,
                        )
                        if (isOnline && showExplore && index == exploreAfterIndex) {
                            ExploreDiscoveryStrip(
                                onOpenExplore = onOpenExplore,
                                desktop = desktop,
                                horizontalPad = horizontalPad,
                            )
                        }
                    }
                }

                if (isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Ink.copy(alpha = 0.65f),
                            )
                        }
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
 * Top quick-resume section:
 * - Wide (>= 720dp): 2-column Editorial Bento (left Featured Hero Card + right 2x3 compact quick-resume tiles)
 * - Compact (< 720dp): Horizontal snap-paging 2x3 compact quick-resume grid + monochrome Random pill in header
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickResumeSection(
    title: String,
    strapline: String?,
    avatar: String?,
    songs: List<SongItem>,
    currentTrack: SongItem?,
    isPlaying: Boolean,
    wideBento: Boolean,
    horizontalPad: Dp,
    pins: Set<String>,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onRandomSong: () -> Unit,
    onTogglePin: (SongItem) -> Unit,
) {
    val s = LocalStrings.current
    val heroSong = currentTrack ?: songs.firstOrNull() ?: return
    val playContext = remember(songs, heroSong) {
        if (songs.any { it.id == heroSong.id }) songs else listOf(heroSong) + songs
    }

    Column {
        // Editorial header with monochrome Random pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .border(0.5.dp, BorderSubtle, CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val sub = strapline?.takeIf { it.isNotBlank() } ?: s.recentlyPlayed
                Text(
                    text = sub.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    ),
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = (if (wideBento) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                        .copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            // Monochrome Random pill (replaces old hardcoded purple DiceTile)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DarkSurface)
                    .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                    .clickable(onClick = onRandomSong)
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = QMusicIcons.Dice,
                    contentDescription = s.randomSong,
                    tint = Ink,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = s.shuffle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Ink,
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (wideBento) {
            val gridSongs = remember(songs, heroSong) {
                val others = songs.filter { it.id != heroSong.id }
                if (others.size >= 4) others.take(6) else songs.take(6)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Left Featured Hero Card (matches exact 224.dp height of the 3-row grid on the right)
                FeaturedHeroCard(
                    song = heroSong,
                    isCurrent = currentTrack?.id == heroSong.id,
                    isPlaying = isPlaying && currentTrack?.id == heroSong.id,
                    pinned = heroSong.id in pins,
                    onPlay = { onSongClick(heroSong, playContext) },
                    onRandom = onRandomSong,
                    onLongClick = { onTogglePin(heroSong) },
                    modifier = Modifier
                        .weight(0.43f)
                        .height(224.dp),
                )

                // Right 2x3 Compact Quick-Resume Grid (3 rows x 68dp + 2 x 10dp spacing = 224dp)
                if (gridSongs.isNotEmpty()) {
                    Column(
                        modifier = Modifier.weight(0.57f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val rows = gridSongs.chunked(2)
                        rows.forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                pair.forEach { song ->
                                    QuickResumeTile(
                                        song = song,
                                        isCurrent = currentTrack?.id == song.id,
                                        pinned = song.id in pins,
                                        height = 68.dp,
                                        onClick = { onSongClick(song, playContext) },
                                        onLongClick = { onTogglePin(song) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Compact 2x3 snap-paging horizontal quick-resume grid (keeps first shelf visible above the fold on phones)
            val allTiles = remember(songs, currentTrack) {
                if (currentTrack != null && songs.none { it.id == currentTrack.id }) {
                    listOf(currentTrack) + songs
                } else {
                    songs
                }
            }
            val pages = remember(allTiles) { allTiles.chunked(6) }
            val pagerState = rememberLazyListState()
            LazyRow(
                state = pagerState,
                flingBehavior = rememberSnapFlingBehavior(pagerState),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(pages.size) { pageIndex ->
                    Column(
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .padding(horizontal = horizontalPad),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pages[pageIndex].chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pair.forEach { song ->
                                    QuickResumeTile(
                                        song = song,
                                        isCurrent = currentTrack?.id == song.id,
                                        pinned = song.id in pins,
                                        height = 60.dp,
                                        onClick = { onSongClick(song, playContext) },
                                        onLongClick = { onTogglePin(song) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
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
                        val active = dot == pagerState.firstVisibleItemIndex
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(5.dp)
                                .width(if (active) 16.dp else 5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Ink.copy(alpha = if (active) 0.92f else 0.25f)),
                        )
                    }
                }
            }
        }
    }
}

/** Large editorial featured hero card for wide tablets/desktop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeaturedHeroCard(
    song: SongItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    pinned: Boolean,
    onPlay: () -> Unit,
    onRandom: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(16.dp)
    val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url

    Box(
        modifier = modifier
            .clip(shape)
            .background(DarkSurfaceVariant)
            .border(0.5.dp, BorderHover, shape)
            .warmOnPress(song)
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick),
    ) {
        if (thumb != null) {
            SharpCover(
                url = thumb,
                px = 544,
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // Always-dark gradient scrim so white editorial text & controls stay crisp in both themes.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.42f to Color.Black.copy(alpha = 0.44f),
                        1f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
        )

        // Top status pill
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.56f))
                .border(0.5.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = when {
                    isCurrent -> QMusicIcons.Waveform
                    pinned -> QMusicIcons.Pin
                    else -> QMusicIcons.History
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = if (isCurrent) s.playingNow else s.recentlyPlayed,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
            )
        }

        // Bottom track details + primary Play / secondary Random actions
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = song.artists.joinToString(", ") { it.name }.ifEmpty { s.unknownArtist }
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = QMusicIcons.Play,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = s.play,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.Black,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .clickable(onClick = onRandom),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QMusicIcons.Dice,
                        contentDescription = s.randomSong,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/** Compact horizontal monochrome tile for the 2x3 Quick-Resume grid (phone & tablet Bento). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickResumeTile(
    song: SongItem,
    isCurrent: Boolean,
    pinned: Boolean,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(12.dp)
    val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url

    val compact = height <= 60.dp
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (isCurrent) DarkSurfaceVariant else DarkSurface)
            .border(
                width = if (isCurrent) 1.dp else 0.5.dp,
                color = if (isCurrent) Ink.copy(alpha = 0.55f) else BorderSubtle,
                shape = shape,
            )
            .warmOnPress(song)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (compact) 6.dp else 7.dp, vertical = if (compact) 6.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
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
            if (isCurrent && compact) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QMusicIcons.Waveform,
                        contentDescription = s.playingNow,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (pinned && compact) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QMusicIcons.Pin,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(if (compact) 8.dp else 10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = (if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
                    .copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = song.artists.joinToString(", ") { it.name }.ifEmpty { s.unknownArtist }
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent && !compact) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = QMusicIcons.Waveform,
                contentDescription = s.playingNow,
                tint = Ink,
                modifier = Modifier.size(16.dp),
            )
        } else if (pinned && !compact) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = QMusicIcons.Pin,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** Pulsing monochrome skeleton shown while the initial home feed is loading. */
@Composable
private fun HomeLoadingSkeleton(
    horizontalPad: Dp,
    wideBento: Boolean,
    showTopBlock: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "homeSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val fill = DarkSurfaceVariant.copy(alpha = pulse)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Chip placeholders
        Row(
            modifier = Modifier.padding(horizontal = horizontalPad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(76.dp, 92.dp, 68.dp, 84.dp).forEach { w ->
                Box(
                    modifier = Modifier
                        .width(w)
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(fill),
                )
            }
        }

        if (showTopBlock) {
            if (wideBento) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPad),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.43f)
                            .height(224.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(fill),
                    )
                    Column(
                        modifier = Modifier.weight(0.57f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                repeat(2) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(68.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(fill),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = horizontalPad),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(2) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(fill),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Two shelf skeletons
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = horizontalPad)
                        .width(160.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fill),
                )
                Row(
                    modifier = Modifier.padding(horizontal = horizontalPad),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val count = if (wideBento) 4 else 2
                    val cardSize = if (wideBento) 172.dp else 148.dp
                    repeat(count) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(cardSize)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(fill),
                            )
                            Box(
                                modifier = Modifier
                                    .width(cardSize * 0.72f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(fill),
                            )
                            Box(
                                modifier = Modifier
                                    .width(cardSize * 0.48f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(fill),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shown while there is no internet: what happened, and the way to the songs saved on the device. */
@Composable
private fun OfflineBanner(
    downloadCount: Int,
    onOpenDownloads: () -> Unit,
    horizontalPad: Dp = 16.dp,
) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad)
            .clip(shape)
            .background(DarkSurface)
            .border(0.5.dp, BorderSubtle, shape)
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
                tint = TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.offlineTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
            Text(
                text = if (downloadCount > 0) s.offlineWithDownloads(downloadCount) else s.offlineWithoutDownloads,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (downloadCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Ink)
                        .clickable(onClick = onOpenDownloads)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(s.goToDownloads, style = MaterialTheme.typography.labelLarge, color = InkInverse)
                }
            }
        }
    }
}

/** The small "E" YouTube Music shows before the artist of an explicit song. */
@Composable
fun ExplicitBadge() {
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TextSecondary)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "E",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = InkInverse,
        )
    }
}

/** How far down the colour of the first speed-dial picture reaches. */
private val TINT_HEIGHT = 440.dp

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

/** One shelf: its editorial header, then framed song columns, featured/standard covers, or wide music video cards. */
@Composable
fun HomeShelf(
    section: HomeSection,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onSeeAll: ((HomeSection) -> Unit)? = null,
    desktop: Boolean = false,
    horizontalPad: Dp = if (desktop) 36.dp else 16.dp,
    currentTrackId: String? = null,
    featured: Boolean = false,
) {
    val s = LocalStrings.current
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val step = if (section.style == SectionStyle.ROWS) 2 else 4
    val cardWidth = when {
        featured && desktop -> 208.dp
        featured -> 170.dp
        desktop -> 172.dp
        else -> 148.dp
    }

    Column {
        SectionHeader(
            section = section,
            onPlayAll = onPlayAll,
            onSeeAll = onSeeAll,
            horizontalPad = horizontalPad,
            desktop = desktop,
            canScrollBackward = rowState.canScrollBackward,
            canScrollForward = rowState.canScrollForward,
            onScrollBackward = {
                scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex - step).coerceAtLeast(0)) }
            },
            onScrollForward = {
                scope.launch { rowState.animateScrollToItem(rowState.firstVisibleItemIndex + step) }
            },
        )
        Spacer(modifier = Modifier.height(if (desktop) 14.dp else 12.dp))
        when (section.style) {
            SectionStyle.ROWS -> SongColumns(
                section = section,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                desktop = desktop,
                horizontalPad = horizontalPad,
                currentTrackId = currentTrackId,
                listState = rowState,
            )

            SectionStyle.VIDEOS -> LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = horizontalPad),
                horizontalArrangement = Arrangement.spacedBy(if (desktop) 18.dp else 14.dp),
            ) {
                items(section.songs) { song ->
                    VideoCard(
                        song = song,
                        width = if (desktop) 304.dp else 264.dp,
                        isCurrent = currentTrackId == song.id,
                        onClick = { onSongClick(song, section.songs) },
                    )
                }
            }

            SectionStyle.CARDS -> LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = horizontalPad),
                horizontalArrangement = Arrangement.spacedBy(if (desktop) 18.dp else 14.dp),
            ) {
                items(section.songs) { song ->
                    ShelfCard(
                        title = song.title,
                        subtitle = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                        thumbnail = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                        onClick = { onSongClick(song, section.songs) },
                        cardWidth = cardWidth,
                        showPlayBadge = featured,
                        isCurrent = currentTrackId == song.id,
                        modifier = Modifier.warmOnPress(song),
                    )
                }
                items(section.collections) { collection ->
                    if (collection.kind == CollectionKind.ARTIST) {
                        ArtistCircleCard(
                            name = collection.title,
                            subtitle = collection.subtitle,
                            thumbnail = collection.thumbnail,
                            onClick = { onCollectionClick(collection) },
                            modifier = if (desktop) Modifier.width(156.dp) else Modifier,
                        )
                    } else {
                        ShelfCard(
                            title = collection.title,
                            subtitle = collection.subtitle ?: "",
                            thumbnail = collection.thumbnail,
                            onClick = { onCollectionClick(collection) },
                            cardWidth = cardWidth,
                            showPlayBadge = featured,
                        )
                    }
                }
            }
        }
    }
}

/** Mid-feed editorial discovery strip for New Releases, Charts, and Moods. */
@Composable
private fun ExploreDiscoveryStrip(
    onOpenExplore: (browseId: String, title: String) -> Unit,
    desktop: Boolean,
    horizontalPad: Dp,
) {
    val s = LocalStrings.current
    Column {
        SectionHeader(
            section = HomeSection(title = s.exploreTitle),
            onPlayAll = {},
            horizontalPad = horizontalPad,
            desktop = desktop,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ExploreTiles(
            onOpenExplore = onOpenExplore,
            desktop = desktop,
            modifier = Modifier.padding(horizontal = horizontalPad),
        )
    }
}

/** Nuvelo monochrome Explore tiles: new releases, charts, moods & genres. */
@Composable
fun ExploreTiles(
    onOpenExplore: (browseId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    desktop: Boolean = false,
) {
    val s = LocalStrings.current
    val tiles = listOf(
        Triple(QMusicIcons.NewReleases, s.exploreNewReleases, "FEmusic_new_releases"),
        Triple(QMusicIcons.Trending, s.exploreCharts, "FEmusic_charts"),
        Triple(QMusicIcons.Mood, s.exploreMoods, "FEmusic_moods_and_genres"),
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 14.dp else 10.dp),
    ) {
        tiles.forEach { (icon, label, browseId) ->
            if (desktop) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(shape)
                        .background(DarkSurface)
                        .border(0.5.dp, BorderSubtle, shape)
                        .clickable { onOpenExplore(browseId, label) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp,
                        ),
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(92.dp)
                        .clip(shape)
                        .background(DarkSurface)
                        .border(0.5.dp, BorderSubtle, shape)
                        .clickable { onOpenExplore(browseId, label) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Ink,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Editorial shelf header with tracked uppercase strapline, bold title, monochrome action pill, and desktop `<` `>` arrows. */
@Composable
private fun SectionHeader(
    section: HomeSection,
    onPlayAll: (List<SongItem>) -> Unit,
    onSeeAll: ((HomeSection) -> Unit)? = null,
    horizontalPad: Dp = 16.dp,
    desktop: Boolean = false,
    canScrollBackward: Boolean = false,
    canScrollForward: Boolean = false,
    onScrollBackward: () -> Unit = {},
    onScrollForward: () -> Unit = {},
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (section.avatar != null) {
            AsyncImage(
                model = section.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant)
                    .border(0.5.dp, BorderSubtle, CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            section.strapline?.takeIf { it.isNotBlank() }?.let { strapline ->
                Text(
                    text = strapline.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    ),
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = section.title,
                style = (if (desktop) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                    .copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (section.canPlayAll && section.songs.isNotEmpty()) {
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DarkSurface)
                    .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                    .clickable { onPlayAll(section.songs) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = s.playAllShelf,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Ink,
                    maxLines = 1,
                )
            }
        } else if (onSeeAll != null && section.moreBrowseId != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DarkSurface)
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(50))
                    .clickable { onSeeAll(section) }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = s.seeAll,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Ink,
                )
                Icon(
                    imageVector = QMusicIcons.ChevronRight,
                    contentDescription = s.seeAll,
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        if (desktop && (canScrollBackward || canScrollForward)) {
            Spacer(modifier = Modifier.width(10.dp))
            CarouselArrowButton(
                icon = QMusicIcons.Back,
                enabled = canScrollBackward,
                onClick = onScrollBackward,
            )
            Spacer(modifier = Modifier.width(6.dp))
            CarouselArrowButton(
                icon = QMusicIcons.ChevronRight,
                enabled = canScrollForward,
                onClick = onScrollForward,
            )
        }
    }
}

@Composable
private fun CarouselArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) DarkSurface else Color.Transparent)
            .border(0.5.dp, if (enabled) BorderHover else BorderSubtle, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Ink.copy(alpha = if (enabled) 0.9f else 0.25f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** "Quick picks": framed 4-song monochrome columns with active-track highlight and hairline dividers. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongColumns(
    section: HomeSection,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    desktop: Boolean = false,
    horizontalPad: Dp = if (desktop) 36.dp else 16.dp,
    currentTrackId: String? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val s = LocalStrings.current
    val groupShape = RoundedCornerShape(16.dp)
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(horizontal = horizontalPad),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 16.dp else 12.dp),
    ) {
        items(section.songs.chunked(4)) { column ->
            Column(
                modifier = (if (desktop) Modifier.width(344.dp) else Modifier.fillParentMaxWidth(0.88f))
                    .clip(groupShape)
                    .background(DarkSurface)
                    .border(0.5.dp, BorderSubtle, groupShape)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                column.forEach { song ->
                    val isCurrent = currentTrackId == song.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) DarkSurfaceVariant else Color.Transparent)
                            .warmOnPress(song)
                            .clickable { onSongClick(song, section.songs) }
                            .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .border(
                                    width = if (isCurrent) 1.dp else 0.5.dp,
                                    color = if (isCurrent) Ink.copy(alpha = 0.65f) else BorderSubtle,
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.48f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = QMusicIcons.Waveform,
                                        contentDescription = s.playingNow,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                ),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (song.explicit) ExplicitBadge()
                                Text(
                                    text = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { onSongMore(song) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = QMusicIcons.More,
                                contentDescription = com.qmusic.ui.A11y.more,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A music video card: wide 16:9 rounded frame with subtle play badge and consistent 12dp corners. */
@Composable
private fun VideoCard(
    song: SongItem,
    width: Dp = 290.dp,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(width)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(DarkSurfaceVariant)
                .border(
                    width = if (isCurrent) 1.5.dp else 0.5.dp,
                    color = if (isCurrent) Ink else BorderSubtle,
                    shape = shape,
                ),
        ) {
            AsyncImage(
                model = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.64f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isCurrent) QMusicIcons.Waveform else QMusicIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
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
            .warmOnPress(song)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (flat) Color.Transparent else DarkSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (flat) PaddingValues(vertical = 6.dp) else PaddingValues(12.dp)),
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
                        if (song.explicit) ExplicitBadge()
                        Text(
                            text = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
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

/** The home page's last colour, for this run of the app: coming back to the page starts with it. */
private var lastHomeColour: Color? = null
