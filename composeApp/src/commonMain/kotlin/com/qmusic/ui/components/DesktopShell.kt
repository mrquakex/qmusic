package com.qmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.HomeSection
import com.qmusic.engine.models.SongItem
import com.qmusic.player.RepeatMode
import com.qmusic.player.SleepTimerState
import com.qmusic.ui.CastButton
import com.qmusic.ui.rememberDominantColor
import com.qmusic.ui.screens.HomeShelf
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicHeaderLogo
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicLogo
import com.qmusic.ui.viewmodel.LyricsState
import com.qmusic.ui.viewmodel.ScreenTab

val DESKTOP_MIN_WIDTH = 700.dp
val DESKTOP_TOP_BAR_HEIGHT = 64.dp
val DESKTOP_PLAYER_BAR_HEIGHT = 72.dp
private val SIDEBAR_EXPANDED_WIDTH = 240.dp
private val SIDEBAR_COLLAPSED_WIDTH = 72.dp

/**
 * YouTube Music Desktop top navigation bar (`ytmusic-nav-bar`):
 * Left hamburger toggle + QMusic logo, centered search box, right quick action icons.
 */
@Composable
fun DesktopTopNavBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onToggleSidebar: () -> Unit,
    onHomeClick: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    isLoggedIn: Boolean,
    onAccountClick: () -> Unit,
    dominantTint: Color?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var searchFocused by remember { mutableStateOf(false) }
    val barTint by animateColorAsState(
        targetValue = dominantTint?.let { lerp(DarkBackground, it.copy(alpha = 1f), 0.18f) } ?: DarkBackground,
        animationSpec = tween(600),
        label = "topBarTint",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DESKTOP_TOP_BAR_HEIGHT)
            .background(barTint)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left section matching sidebar width
        Row(
            modifier = Modifier.width(224.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggleSidebar,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = QMusicIcons.Menu,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            QMusicHeaderLogo(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onHomeClick,
                ),
            )
        }

        // Centered Search Box (`ytmusic-search-box`)
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            val boxBg = if (searchFocused) DarkSurface else Color.White.copy(alpha = 0.11f)
            val boxBorder = if (searchFocused) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f)
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(boxBg)
                    .border(1.dp, boxBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = QMusicIcons.Search,
                    contentDescription = s.tabSearch,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = s.searchFieldPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.52f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state -> searchFocused = state.isFocused },
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = QMusicIcons.Close,
                            contentDescription = s.clearSearch,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // Right Action Icons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CastButton(modifier = Modifier.size(40.dp))
            IconButton(onClick = onOpenStats, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = QMusicIcons.Stats,
                    contentDescription = s.statsTitle,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenEqualizer, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = QMusicIcons.Tune,
                    contentDescription = s.equalizerTitle,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = QMusicIcons.Settings,
                    contentDescription = s.tabSettings,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isLoggedIn) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onAccountClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = QMusicIcons.Account,
                    contentDescription = s.sectionAccount,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * YouTube Music Desktop Left Sidebar (`ytmusic-guide-renderer`):
 * 240.dp expanded (with Home, Explore, Library, Divider, `+ New playlist` button, and scrollable playlists)
 * or 72.dp collapsed mini-guide.
 */
@Composable
fun DesktopLeftSidebar(
    expanded: Boolean,
    currentTab: ScreenTab,
    onSelectTab: (ScreenTab) -> Unit,
    favoritesCount: Int,
    downloadsCount: Int,
    playlists: List<PlaylistEntity>,
    ytPlaylists: List<CollectionItem>,
    onOpenFavorites: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLocalPlaylist: (PlaylistEntity) -> Unit,
    onOpenCollection: (CollectionItem) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    dominantTint: Color?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val activePillColor = if (dominantTint != null) {
        lerp(Color.White.copy(alpha = 0.14f), dominantTint.copy(alpha = 0.36f), 0.55f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }

    val primaryItems = listOf(
        Triple(ScreenTab.HOME, s.tabHome, QMusicIcons.Home),
        Triple(ScreenTab.FOR_YOU, s.tabForYou, QMusicIcons.Samples),
        Triple(ScreenTab.LIBRARY, s.tabLibrary, QMusicIcons.Library),
    )

    if (!expanded) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(SIDEBAR_COLLAPSED_WIDTH)
                .background(DarkBackground)
                .padding(top = 8.dp, start = 4.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            primaryItems.forEach { (tab, label, icon) ->
                val selected = currentTab == tab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) activePillColor else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected) Color.White else Color.White.copy(alpha = 0.68f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(SIDEBAR_EXPANDED_WIDTH)
                .background(DarkBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            primaryItems.forEach { (tab, label, icon) ->
                val selected = currentTab == tab
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) activePillColor else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                thickness = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
            )

            // "+ Yeni oynatma listesi" (`+ New playlist`) pill button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { showNewPlaylistDialog = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = QMusicIcons.Add,
                    contentDescription = s.newPlaylist,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s.newPlaylist,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Scrollable Playlists list
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    SidebarPlaylistRow(
                        title = s.tabFavorites,
                        subtitle = s.songsCount(favoritesCount),
                        leadingIcon = QMusicIcons.HeartFilled,
                        onClick = onOpenFavorites,
                    )
                }
                if (downloadsCount > 0) {
                    item {
                        SidebarPlaylistRow(
                            title = s.tabDownloads,
                            subtitle = s.songsCount(downloadsCount),
                            leadingIcon = QMusicIcons.DownloadDone,
                            onClick = onOpenDownloads,
                        )
                    }
                }
                items(playlists, key = { "local_${it.id}" }) { playlist ->
                    SidebarPlaylistRow(
                        title = playlist.name,
                        subtitle = s.tabPlaylists,
                        onClick = { onOpenLocalPlaylist(playlist) },
                    )
                }
                items(ytPlaylists, key = { "yt_${it.browseId}" }) { col ->
                    SidebarPlaylistRow(
                        title = col.title,
                        subtitle = col.subtitle ?: s.tabPlaylists,
                        onClick = { onOpenCollection(col) },
                    )
                }
            }
        }
    }

    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false },
            containerColor = DarkSurface,
            title = { Text(s.newPlaylist, color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(s.playlistNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreatePlaylist(trimmed)
                            newPlaylistName = ""
                            showNewPlaylistDialog = false
                        }
                    },
                ) {
                    Text(s.create)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false }) {
                    Text(s.cancel)
                }
            },
        )
    }
}

@Composable
private fun SidebarPlaylistRow(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * YouTube Music Desktop Bottom Player Bar (`ytmusic-player-bar`):
 * Fixed 72.dp full-width bar with top hairline interactive seek slider,
 * left transport controls + time, center track info + like/more, right volume + repeat/shuffle/expand.
 */
@Composable
fun DesktopBottomPlayerBar(
    currentTrack: SongItem,
    isPlaying: Boolean,
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    durationMs: Long,
    isFavorite: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    volume: Float,
    isExpanded: Boolean,
    dominantTint: Color?,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMore: () -> Unit,
    onArtistClick: (() -> Unit)?,
    onVolumeChange: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val pos = positionMs()
    val buf = bufferedMs()
    val effectiveDuration = if (durationMs > 0L) durationMs else ((currentTrack.duration ?: 0) * 1000L)
    var draggingFraction by remember { mutableStateOf<Float?>(null) }
    var barWidthPx by remember { mutableFloatStateOf(1f) }
    var volumeBeforeMute by remember { mutableFloatStateOf(0.8f) }

    val progress = draggingFraction ?: if (effectiveDuration > 0L) {
        (pos.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedProgress = if (effectiveDuration > 0L) {
        (buf.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val accentColor = dominantTint?.let { lerp(MaterialTheme.colorScheme.primary, it.copy(alpha = 1f), 0.35f) }
        ?: MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DESKTOP_PLAYER_BAR_HEIGHT)
            .background(BgSurface),
    ) {
        // Main 3-column row (`1fr 2fr 1fr`)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Left Transport Controls (`Prev`, `Play/Pause`, `Next`, `1:24 / 3:45`)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = QMusicIcons.Previous,
                        contentDescription = s.previous,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                        contentDescription = if (isPlaying) s.pause else s.play,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = QMusicIcons.Next,
                        contentDescription = s.next,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${formatDurationMs(pos)} / ${formatDurationMs(effectiveDuration)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }

            // 2. Center Track Info (`44.dp` cover + Title/Artist + Like / Add / More)
            Row(
                modifier = Modifier
                    .weight(1.8f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleExpand,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val thumb = currentTrack.thumbnail ?: currentTrack.thumbnails.firstOrNull()?.url
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = currentTrack.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        QMusicLogo(size = 24.dp, tint = Color.White.copy(alpha = 0.85f))
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        text = currentTrack.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val artistText = currentTrack.artists.joinToString { it.name }.ifEmpty { s.unknownArtist }
                    val subtitleText = currentTrack.album?.name?.takeIf { it.isNotBlank() }?.let { "$artistText • $it" } ?: artistText
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onArtistClick != null) {
                            Modifier.clickable { onArtistClick() }
                        } else {
                            Modifier
                        },
                    )
                }

                Spacer(Modifier.width(12.dp))

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFavorite) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                        contentDescription = if (isFavorite) s.favorited else s.favorite,
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = QMusicIcons.PlaylistAdd,
                        contentDescription = s.addToPlaylist,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = QMusicIcons.More,
                        contentDescription = s.moreOptions,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 3. Right Controls (Volume slider + Repeat + Shuffle + Equalizer + Expand Chevron)
            Row(
                modifier = Modifier.weight(1.2f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        if (volume > 0.01f) {
                            volumeBeforeMute = volume
                            onVolumeChange(0f)
                        } else {
                            onVolumeChange(volumeBeforeMute.coerceAtLeast(0.2f))
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (volume <= 0.01f) QMusicIcons.VolumeMute else QMusicIcons.Volume,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.width(88.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                    ),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) QMusicIcons.RepeatOne else QMusicIcons.Repeat,
                        contentDescription = when (repeatMode) {
                            RepeatMode.OFF -> s.repeatOff
                            RepeatMode.ALL -> s.repeatAll
                            RepeatMode.ONE -> s.repeatOne
                        },
                        tint = if (repeatMode != RepeatMode.OFF) Color.White else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = QMusicIcons.Shuffle,
                        contentDescription = s.shuffle,
                        tint = if (shuffle) Color.White else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onOpenEqualizer, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = QMusicIcons.Tune,
                        contentDescription = s.equalizerTitle,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (isExpanded) QMusicIcons.ChevronDown else QMusicIcons.ChevronUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Top Edge Interactive Progress Slider (`tp-yt-paper-slider#progress-bar`)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(14.dp)
                .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(effectiveDuration) {
                    detectTapGestures { offset ->
                        if (effectiveDuration > 0L) {
                            val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            onSeekTo((frac * effectiveDuration).toLong())
                        }
                    }
                }
                .pointerInput(effectiveDuration) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            draggingFraction?.let { frac ->
                                if (effectiveDuration > 0L) {
                                    onSeekTo((frac * effectiveDuration).toLong())
                                }
                            }
                            draggingFraction = null
                        },
                        onDragCancel = { draggingFraction = null },
                    ) { change, _ ->
                        change.consume()
                        val frac = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                        draggingFraction = frac
                    }
                },
            contentAlignment = Alignment.TopStart,
        ) {
            // Unplayed background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.16f)),
            )
            // Buffered track
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedProgress)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.32f)),
            )
            // Active played track
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(accentColor),
            )
        }
    }
}

/**
 * YouTube Music Desktop Expanded Watch Page (`ytmusic-player-page`):
 * Split two-column layout between the Top Navigation Bar and Bottom Player Bar.
 * Left column (~54%): Song/Video toggle + Large square cover + quick action chips.
 * Right column (~46%): 3-tab panel (`SIRADAKİ` / `ŞARKI SÖZLERİ` / `BENZER`).
 */
@Composable
fun DesktopWatchPage(
    track: SongItem,
    isPlaying: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    sleepTimer: SleepTimerState,
    queue: List<SongItem>,
    queueIndex: Int,
    lyrics: LyricsState,
    videoMode: Boolean,
    videoPreferred: Boolean,
    onSetVideoPreferred: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleDownload: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onSleepAtEndOfTrack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    onSaveQueue: (String) -> Unit,
    onClearQueue: () -> Unit,
    onLoadLyrics: () -> Unit,
    related: List<HomeSection>?,
    onLoadRelated: () -> Unit,
    onRelatedSongClick: (SongItem, List<SongItem>) -> Unit,
    onRelatedCollectionClick: (CollectionItem) -> Unit,
    onRelatedSongMore: (SongItem) -> Unit,
    playbackSpeed: Float,
    onCycleSpeed: () -> Unit,
    lyricsTranslation: List<String>?,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showSaveQueueDialog by remember { mutableStateOf(false) }
    var queuePlaylistName by remember { mutableStateOf("") }

    val artwork = track.thumbnail ?: track.thumbnails.lastOrNull()?.url
    val dominant = rememberDominantColor(artwork)
    val animatedTint by animateColorAsState(
        targetValue = dominant ?: Color(0xFF221C30),
        animationSpec = tween(700),
        label = "watchPageTint",
    )

    LaunchedEffect(selectedTab, track.id) {
        if (selectedTab == 1) onLoadLyrics()
        if (selectedTab == 2) onLoadRelated()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgRoot),
    ) {
        // Blurred album cover + our dominant color gradient backdrop
        if (artwork != null) {
            SharpCover(
                url = artwork,
                px = 256,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(64.dp)
                    .alpha(0.38f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to animatedTint.copy(alpha = 0.62f),
                        0.6f to BgRoot.copy(alpha = 0.88f),
                        1f to BgRoot,
                    ),
                ),
        )

        // Two-Column Split Layout (`div#main-panel` + `div#side-panel`)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Column (~54% width): Song/Video switch + Large Album Cover + Quick Action Chips
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (track.hasVideo) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(3.dp),
                    ) {
                        listOf(false to s.song, true to s.video).forEach { (isVideo, label) ->
                            val active = videoPreferred == isVideo
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (active) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable { onSetVideoPreferred(isVideo) }
                                    .padding(horizontal = 18.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 420.dp)
                        .aspectRatio(if (track.hasVideo && videoMode) 16f / 9f else 1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track.hasVideo && videoMode) {
                        VideoSurface(trackKey = track.id, modifier = Modifier.fillMaxSize())
                    } else if (artwork != null) {
                        SharpCover(
                            url = artwork,
                            px = 800,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        QMusicLogo(size = 72.dp, tint = Color.White.copy(alpha = 0.85f))
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Quick action pills below the cover
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WatchQuickChip(
                        icon = if (isDownloaded) QMusicIcons.DownloadDone else QMusicIcons.Download,
                        label = if (isDownloaded) s.downloaded else if (downloadProgress != null) "${(downloadProgress * 100).toInt()}%" else s.download,
                        active = isDownloaded,
                        onClick = onToggleDownload,
                    )
                    WatchQuickChip(
                        icon = QMusicIcons.Moon,
                        label = s.sleepTimer,
                        active = sleepTimer.active,
                        onClick = { showSleepDialog = true },
                    )
                    WatchQuickChip(
                        icon = QMusicIcons.Tune,
                        label = s.equalizerTitle,
                        active = false,
                        onClick = onOpenEqualizer,
                    )
                    WatchQuickChip(
                        icon = null,
                        label = "${playbackSpeed}x",
                        active = playbackSpeed != 1f,
                        onClick = onCycleSpeed,
                    )
                }
            }

            // Right Column (~46% width): 3-Tab Panel (`SIRADAKİ` / `ŞARKI SÖZLERİ` / `BENZER`)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val tabs = listOf(
                    s.upNext(null).uppercase(),
                    s.lyrics.uppercase(),
                    s.related.uppercase(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val active = selectedTab == idx
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { selectedTab = idx },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    letterSpacing = 0.6.sp,
                                ),
                                color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(if (active) Color.White else Color.White.copy(alpha = 0.12f)),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> DesktopQueueTab(
                        queue = queue,
                        queueIndex = queueIndex,
                        isPlaying = isPlaying,
                        onItemClick = onQueueItemClick,
                        onItemRemove = onQueueItemRemove,
                        onSaveQueue = { showSaveQueueDialog = true },
                        onClearQueue = onClearQueue,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    1 -> DesktopLyricsTab(
                        state = lyrics,
                        positionMs = positionMs,
                        onSeekTo = onSeekTo,
                        translation = lyricsTranslation,
                        onToggleTranslation = onToggleTranslation,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    else -> DesktopRelatedTab(
                        related = related,
                        onSongClick = onRelatedSongClick,
                        onCollectionClick = onRelatedCollectionClick,
                        onSongMore = onRelatedSongMore,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            containerColor = DarkSurface,
            title = { Text(s.sleepTimer, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        TextButton(
                            onClick = {
                                onSetSleepTimer(mins)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("$mins dk", color = Color.White)
                        }
                    }
                    TextButton(
                        onClick = {
                            onSleepAtEndOfTrack()
                            showSleepDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s.endOfThisSong, color = Color.White)
                    }
                    if (sleepTimer.active) {
                        TextButton(
                            onClick = {
                                onSetSleepTimer(0)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(s.turnOffTimer, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text(s.close) }
            },
        )
    }

    if (showSaveQueueDialog) {
        AlertDialog(
            onDismissRequest = { showSaveQueueDialog = false },
            containerColor = DarkSurface,
            title = { Text(s.saveQueue, color = Color.White) },
            text = {
                OutlinedTextField(
                    value = queuePlaylistName,
                    onValueChange = { queuePlaylistName = it },
                    label = { Text(s.playlistNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = queuePlaylistName.trim()
                        if (name.isNotEmpty()) {
                            onSaveQueue(name)
                            queuePlaylistName = ""
                            showSaveQueueDialog = false
                        }
                    },
                ) {
                    Text(s.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveQueueDialog = false }) {
                    Text(s.cancel)
                }
            },
        )
    }
}

@Composable
private fun WatchQuickChip(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopQueueTab(
    queue: List<SongItem>,
    queueIndex: Int,
    isPlaying: Boolean,
    onItemClick: (Int) -> Unit,
    onItemRemove: (Int) -> Unit,
    onSaveQueue: () -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()

    LaunchedEffect(queueIndex) {
        if (queueIndex in queue.indices) {
            listState.animateScrollToItem(queueIndex)
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = s.songsCount(queue.size),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSaveQueue,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(QMusicIcons.PlaylistAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.save, style = MaterialTheme.typography.labelMedium)
                }
                if (queue.size > 1) {
                    OutlinedButton(
                        onClick = onClearQueue,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(s.clearQueue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(queue, key = { index, song -> "${index}_${song.id}" }) { index, song ->
                val isCurrent = index == queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCurrent) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onItemClick(index) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp))
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
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) QMusicIcons.Volume else QMusicIcons.Play,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    song.duration?.takeIf { it > 0 }?.let { sec ->
                        Text(
                            text = formatDurationMs(sec * 1000L),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }

                    if (!isCurrent) {
                        IconButton(
                            onClick = { onItemRemove(index) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = QMusicIcons.Close,
                                contentDescription = s.removeFromQueue,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopLyricsTab(
    state: LyricsState,
    positionMs: () -> Long,
    onSeekTo: (Long) -> Unit,
    translation: List<String>?,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val pos = positionMs()
    val listState = rememberLazyListState()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            is LyricsState.Idle, is LyricsState.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }
            is LyricsState.Unavailable -> {
                Text(
                    text = s.lyricsUnavailable,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            is LyricsState.Available -> {
                val lyrics = state.lyrics
                if (lyrics.lines.isNotEmpty()) {
                    val activeIdx = lyrics.lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
                    LaunchedEffect(activeIdx) {
                        listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onToggleTranslation) {
                                Text(
                                    text = if (translation != null) s.showOriginalLyrics else s.translateLyrics,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 24.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { idx, line ->
                                val active = idx == activeIdx
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSeekTo(line.timeMs) }
                                        .padding(vertical = 4.dp, horizontal = 8.dp),
                                ) {
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontSize = if (active) 22.sp else 18.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = if (active) Color.White else Color.White.copy(alpha = 0.36f),
                                    )
                                    val tr = translation?.getOrNull(idx)
                                    if (!tr.isNullOrBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = tr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (active) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.28f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                    ) {
                        item {
                            Text(
                                text = lyrics.text,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopRelatedTab(
    related: List<HomeSection>?,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onCollectionClick: (CollectionItem) -> Unit,
    onSongMore: (SongItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (related == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(related) { section ->
            HomeShelf(
                section = section,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                onPlayAll = { songs -> songs.firstOrNull()?.let { onSongClick(it, songs) } },
                onCollectionClick = onCollectionClick,
            )
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "$min:${sec.toString().padStart(2, '0')}"
}
