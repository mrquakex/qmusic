package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.CollectionKind
import com.qmusic.engine.models.CollectionPage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.components.SharpCover
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * An album, playlist or podcast, laid out like YouTube Music: the cover over a blurred copy of itself that fades
 * into the page, title, owner, year and length, then download / play / shuffle, the description and the songs.
 * Albums number their tracks; playlists the account owns can be reordered by dragging and deleted.
 */
@Composable
fun CollectionScreen(
    item: CollectionItem,
    page: CollectionPage?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    downloadedIds: Set<String>,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
    modifier: Modifier = Modifier,
    onDownloadAll: (List<SongItem>) -> Unit = {},
    onShare: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onDeletePlaylist: () -> Unit = {},
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf(false) }
    val songs = page?.songs.orEmpty()
    val isAlbum = item.kind == CollectionKind.ALBUM
    val editable = page?.editable == true

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        val headerPx = with(LocalDensity.current) { (maxWidth * 0.9f).toPx() }
        val barAlpha by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else ((listState.firstVisibleItemScrollOffset - headerPx * 0.5f) / (headerPx * 0.3f)).coerceIn(0f, 1f)
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                var draggingId by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                var dragFrom by remember { mutableStateOf(-1) }
                var dragTo by remember { mutableStateOf(-1) }
                val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
                // While dragging, the list shows the song at its new place; the move is sent once, on release.
                val shown = if (draggingId != null && dragFrom >= 0 && dragTo >= 0) {
                    songs.toMutableList().apply { add(dragTo, removeAt(dragFrom)) }
                } else {
                    songs
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    item { Header(item, page, onPlay = { onPlayAll(songs) }, onShuffle = { onShuffle(songs) }, onDownloadAll = { onDownloadAll(songs) }) }

                    itemsIndexed(shown, key = { _, song -> song.id }) { index, song ->
                        val dragged = draggingId == song.id
                        val latestIndex by rememberUpdatedState(index)
                        Row(
                            modifier = Modifier
                                .then(if (dragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier)
                                .padding(start = 16.dp, end = if (editable) 0.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isAlbum) {
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(28.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SongListItem(
                                    song = song,
                                    isFavorite = isFavoritePredicate(song.id),
                                    onClick = { onSongClick(song, songs) },
                                    onToggleFavorite = { onToggleFavorite(song) },
                                    onMore = { onSongMore(song) },
                                    isDownloaded = song.id in downloadedIds,
                                    flat = true,
                                )
                            }
                            if (editable) {
                                Icon(
                                    imageVector = QMusicIcons.DragHandle,
                                    contentDescription = s.reorderQueue,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .padding(10.dp)
                                        .pointerInput(song.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingId = song.id
                                                    dragFrom = latestIndex
                                                    dragTo = latestIndex
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) onMove(dragFrom, dragTo)
                                                    draggingId = null
                                                    dragFrom = -1
                                                    dragTo = -1
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    dragFrom = -1
                                                    dragTo = -1
                                                    dragOffset = 0f
                                                },
                                            ) { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                if (dragOffset > rowPx && dragTo < songs.lastIndex) {
                                                    dragTo += 1
                                                    dragOffset -= rowPx
                                                } else if (dragOffset < -rowPx && dragTo > 0) {
                                                    dragTo -= 1
                                                    dragOffset += rowPx
                                                }
                                            }
                                        },
                                )
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // Top bar: plain icons over the cover; a solid bar with the title fades in as the header scrolls away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(BgRoot.copy(alpha = barAlpha)) }
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(QMusicIcons.Back, contentDescription = s.back, tint = Color.White)
            }
            Text(
                text = page?.title?.ifBlank { item.title } ?: item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = barAlpha },
            )
            IconButton(onClick = onShare) {
                Icon(QMusicIcons.Share, contentDescription = s.share, tint = Color.White)
            }
            if (editable) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(QMusicIcons.Trash, contentDescription = s.delete, tint = Color.White)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(s.deleteYouTubePlaylistTitle) },
            text = { Text(s.deleteYouTubePlaylistMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeletePlaylist()
                }) { Text(s.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
private fun Header(
    item: CollectionItem,
    page: CollectionPage?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val s = LocalStrings.current
    val cover = page?.thumbnail ?: item.thumbnail
    val songs = page?.songs.orEmpty()
    Box(modifier = Modifier.fillMaxWidth()) {
        // The cover again, blurred, behind the header; it melts into the page below.
        SharpCover(
            url = cover,
            px = 256,
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .blur(60.dp)
                .graphicsLayer { alpha = 0.6f },
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.6f to BgRoot.copy(alpha = 0.6f), 1f to BgRoot)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 56.dp, start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SharpCover(
                url = cover,
                px = 800,
                contentDescription = item.title,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = page?.title?.ifBlank { item.title } ?: item.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (page?.subtitle ?: item.subtitle)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            listOfNotNull(page?.kindLine, page?.countLine).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundAction(QMusicIcons.Download, s.downloadAll, enabled = songs.isNotEmpty(), onClick = onDownloadAll)
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (songs.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
                        .clickable(enabled = songs.isNotEmpty(), onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QMusicIcons.Play, contentDescription = s.play, tint = Color.Black, modifier = Modifier.size(34.dp))
                }
                RoundAction(QMusicIcons.Shuffle, s.shuffle, enabled = songs.isNotEmpty(), onClick = onShuffle)
            }
            page?.description?.let { description ->
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(24.dp))
    }
}

private val ROW_HEIGHT = 64.dp
