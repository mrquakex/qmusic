package com.qmusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.QMusicIcons

/** Picks a playlist for [song], or creates one on the spot. */
@Composable
fun AddToPlaylistDialog(
    song: SongItem,
    playlists: List<PlaylistEntity>,
    onPick: (PlaylistEntity) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    ytPlaylists: List<CollectionItem> = emptyList(),
    onPickYouTube: (CollectionItem) -> Unit = {},
    canSaveToYouTube: Boolean = false,
    onCreateOnYouTube: (String) -> Unit = {},
) {
    var onYouTube by remember { mutableStateOf(canSaveToYouTube) }
    var creating by remember { mutableStateOf(playlists.isEmpty() && ytPlaylists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    val s = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text(s.addToPlaylist) },
        text = {
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.padding(top = 8.dp))
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(s.playlistNameLabel) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (canSaveToYouTube) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onYouTube = !onYouTube }
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = onYouTube, onCheckedChange = { onYouTube = it })
                            Text(s.saveToYouTube, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { creating = true }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(QMusicIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(14.dp))
                                Text(s.newPlaylist, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        // The account's own playlists ("Liked music" is not one you add to by hand).
                        val editable = ytPlaylists.filter { it.browseId != "VLLM" }
                        if (editable.isNotEmpty()) {
                            item {
                                Text(
                                    text = s.youtubeMusicPlaylists,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(editable, key = { "yt:${it.browseId}" }) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPickYouTube(playlist) }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(QMusicIcons.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(14.dp))
                                    Text(playlist.title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                }
                            }
                            if (playlists.isNotEmpty()) {
                                item {
                                    Text(
                                        text = s.devicePlaylists,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                            }
                        }
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(playlist) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(QMusicIcons.Queue, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(14.dp))
                                Text(playlist.name, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { if (canSaveToYouTube && onYouTube) onCreateOnYouTube(name) else onCreate(name) },
                    enabled = name.isNotBlank(),
                ) { Text(s.create) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        },
    )
}
