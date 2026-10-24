package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.QMusicIcons

/** A playlist the user made on this device: play, shuffle, rename, delete and remove songs. */
@Composable
fun LocalPlaylistScreen(
    name: String,
    songs: List<SongItem>,
    downloadedIds: Set<String>,
    onBack: () -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onRemoveSong: (SongItem) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
    onReorder: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var renaming by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    // The order shown while arranging; saved after every move so leaving the page keeps it.
    var ordered by remember(songs) { mutableStateOf(songs) }
    fun move(from: Int, to: Int) {
        if (to !in ordered.indices) return
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        onReorder(ordered.map { it.id })
    }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(QMusicIcons.Back, contentDescription = s.back, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (songs.size > 1) {
                IconButton(onClick = { reordering = !reordering }) {
                    Icon(
                        if (reordering) QMusicIcons.Check else QMusicIcons.DragHandle,
                        contentDescription = s.reorderPlaylist,
                        tint = if (reordering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { renaming = true }) {
                Icon(QMusicIcons.Edit, contentDescription = s.renamePlaylist, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(QMusicIcons.Trash, contentDescription = s.deletePlaylist, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = s.localPlaylistEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onPlayAll(ordered) }, shape = RoundedCornerShape(12.dp)) {
                            Icon(QMusicIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.play)
                        }
                        OutlinedButton(onClick = { onShuffle(ordered) }, shape = RoundedCornerShape(12.dp)) {
                            Icon(QMusicIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.shuffle)
                        }
                        Text(
                            text = s.songsCount(songs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                        SongListItem(
                            song = song,
                            isFavorite = isFavoritePredicate(song.id),
                            onClick = { onSongClick(song, ordered) },
                            onToggleFavorite = { onToggleFavorite(song) },
                            onMore = { onSongMore(song) },
                            isDownloaded = song.id in downloadedIds,
                            flat = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (reordering) {
                            IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                                Icon(QMusicIcons.ChevronDown, contentDescription = s.moveUp, modifier = Modifier.size(20.dp).rotate(180f))
                            }
                            IconButton(onClick = { move(index, index + 1) }, enabled = index < ordered.lastIndex) {
                                Icon(QMusicIcons.ChevronDown, contentDescription = s.moveDown, modifier = Modifier.size(20.dp))
                            }
                        } else {
                            IconButton(onClick = { onRemoveSong(song) }) {
                                Icon(
                                    QMusicIcons.Close,
                                    contentDescription = s.removeFromPlaylist,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = BgSurface,
            title = { Text(s.renamePlaylist) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(text)
                        renaming = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(s.cancel) } },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = BgSurface,
            title = { Text(s.deletePlaylistTitle(name)) },
            text = { Text(s.deletePlaylistMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) { Text(s.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(s.cancel) } },
        )
    }
}
