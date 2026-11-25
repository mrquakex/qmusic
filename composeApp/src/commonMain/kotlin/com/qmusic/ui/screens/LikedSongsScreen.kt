package com.qmusic.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary

/**
 * "Liked music", as YouTube Music has it: a violet glow at the top behind the back arrow, "add to queue" and the menu;
 * the thumbs-up tile with the name and how many songs; a line about it; the download button on the left, shuffle and
 * a big play on the right; then the songs. The menu keeps the liked songs downloaded by themselves (and only on Wi-Fi).
 */
@Composable
fun LikedSongsScreen(
    songs: List<SongItem>,
    downloadedIds: Set<String>,
    downloadProgress: Map<String, Float>,
    autoDownload: Boolean,
    wifiOnly: Boolean,
    isFavoritePredicate: (String) -> Boolean,
    onBack: () -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onAddToQueue: (List<SongItem>) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    onDownloadAll: (List<SongItem>) -> Unit,
    onCancelDownloads: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val w = remember(s.english) { LibraryWords(s.english) }
    val english = s.english
    var menu by remember { mutableStateOf(false) }
    val saved = songs.count { it.id in downloadedIds }
    val pending = songs.count { it.id in downloadProgress }
    val missing = songs.size - saved - pending

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // How far the header has scrolled away, 0-1: its glow and its contents fade with it, and the pinned bar turns
    // solid with the name in it, as on YouTube Music.
    val scrolled by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / with(density) { 220.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    Box(modifier.fillMaxSize().background(BgRoot)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Box(
                    Modifier.fillMaxWidth().drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                0f to DarkSurfaceVariant,
                                0.55f to DarkSurface.copy(alpha = 0.65f),
                                1f to BgRoot,
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                listOf(Ink.copy(alpha = 0.08f), Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.08f),
                                radius = size.width * 0.75f,
                            ),
                        )
                    }.graphicsLayer { alpha = 1f - 0.6f * scrolled },
                ) {
                    Column(Modifier.statusBarsPadding().padding(bottom = 18.dp).graphicsLayer { alpha = 1f - scrolled }) {
                        // Room for the bar pinned over the top (back, queue, menu).
                        Spacer(Modifier.height(56.dp))
                        Spacer(Modifier.height(18.dp))
                        // The tile, the name, how many.
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(QMusicIcons.ThumbUpFilled, contentDescription = null, tint = Ink, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(w.liked, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                                Text(w.songs(songs.size), color = TextSecondary, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (english) "The songs you like show here. Tap the heart on any song to add it."
                            else "Beğendiğin şarkılar burada gösterilir. Bir şarkıdaki kalbe dokununca buraya eklenir.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        // Download on the left; shuffle and play on the right.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(56.dp).pressScale(0.92f).clip(CircleShape).background(Ink.copy(alpha = 0.12f))
                                    .clickable(enabled = songs.isNotEmpty()) {
                                        when {
                                            pending > 0 -> onCancelDownloads()
                                            missing > 0 -> onDownloadAll(songs)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (pending > 0) {
                                    CircularProgressIndicator(
                                        progress = { if (songs.isEmpty()) 0f else saved.toFloat() / songs.size },
                                        color = Ink,
                                        trackColor = Ink.copy(alpha = 0.2f),
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(34.dp),
                                    )
                                } else {
                                    Icon(
                                        if (songs.isNotEmpty() && missing == 0) QMusicIcons.DownloadDone else QMusicIcons.Download,
                                        contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { if (songs.isNotEmpty()) onShuffle(songs) }, modifier = Modifier.size(56.dp)) {
                                Icon(QMusicIcons.Shuffle, contentDescription = s.shuffle, tint = Ink, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Box(
                                Modifier.size(68.dp).pressScale(0.92f).clip(CircleShape).background(Ink).clickable(enabled = songs.isNotEmpty()) { onPlayAll(songs) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(QMusicIcons.Play, contentDescription = s.play, tint = InkInverse, modifier = Modifier.size(34.dp)) }
                        }
                    }
                }
            }
            if (songs.isEmpty()) {
                item { EmptyLibraryPlaceholder(s.favoritesEmpty) }
            } else {
                itemsIndexed(songs, key = { index, song -> "${index}_${song.id}" }) { _, song ->
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        SongListItem(
                            song = song,
                            isFavorite = isFavoritePredicate(song.id),
                            onClick = { onSongClick(song, songs) },
                            onToggleFavorite = { onToggleFavorite(song) },
                            onMore = { onSongMore(song) },
                            isDownloaded = song.id in downloadedIds,
                            flat = true,
                            downloadProgress = downloadProgress[song.id],
                        )
                    }
                }
            }
        }
        // The bar pinned over the top: see-through over the glow, solid once the header has gone, the name fading in.
        Box(
            Modifier.fillMaxWidth().background(BgRoot.copy(alpha = scrolled)).statusBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(QMusicIcons.Back, contentDescription = s.back, tint = Ink, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.weight(1f))
                if (songs.isNotEmpty()) {
                    IconButton(onClick = { onAddToQueue(songs) }) {
                        Icon(QMusicIcons.PlayNext, contentDescription = if (english) "Add to queue" else "Sıraya ekle", tint = Ink, modifier = Modifier.size(26.dp))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = s.moreOptions, tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        MenuSwitch(s.autoDownloadLikedTitle, autoDownload) { onAutoDownloadChange(it) }
                        if (autoDownload) MenuSwitch(s.wifiOnlyTitle, wifiOnly) { onWifiOnlyChange(it) }
                    }
                }
            }
            Text(
                w.liked,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 60.dp, end = 120.dp).graphicsLayer { alpha = ((scrolled - 0.6f) / 0.4f).coerceIn(0f, 1f) },
            )
        }
    }
}

@Composable
private fun MenuSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = InkInverse,
                    checkedTrackColor = Ink,
                    uncheckedThumbColor = Ink.copy(alpha = 0.7f),
                    uncheckedTrackColor = DarkSurfaceVariant,
                    uncheckedBorderColor = Ink.copy(alpha = 0.3f),
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}
