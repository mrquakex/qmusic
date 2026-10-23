package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * Favorites (device + YouTube Music likes), playlists, downloads and history, with what you can do to each as a
 * whole: play, shuffle, download everything, keep liked songs downloaded automatically.
 */
@Composable
fun LibraryScreen(
    favorites: List<SongItem>,
    history: List<SongItem>,
    playlists: List<PlaylistEntity>,
    downloads: List<SongItem>,
    downloadedIds: Set<String>,
    downloadProgress: Map<String, Float>,
    downloadedBytes: Long,
    autoDownload: Boolean,
    wifiOnly: Boolean,
    onAutoDownloadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onDownloadAll: (List<SongItem>) -> Unit,
    onCancelDownloads: () -> Unit,
    onRemoveAllDownloads: () -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onOpenPlaylist: (PlaylistEntity) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    isFavoritePredicate: (String) -> Boolean,
    onCreatePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    val tabs = listOf(
        "Favorites" to favorites.size,
        "Playlists" to playlists.size,
        "Downloads" to downloads.size,
        "History" to history.size,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Your Library",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (selectedTabIndex == 1) {
                IconButton(onClick = { showCreatePlaylistDialog = true }) {
                    Icon(
                        imageVector = QMusicIcons.Add,
                        contentDescription = "New Playlist",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tabs as pills, with how much is in each
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { index, (title, count) ->
                val selected = selectedTabIndex == index
                Text(
                    text = if (count > 0) "$title · $count" else title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) BgRoot else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else DarkSurfaceVariant)
                        .clickable { selectedTabIndex = index }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTabIndex) {
            0 -> SongsTab(
                songs = favorites,
                empty = "No favorite songs yet.\nTap the heart on any song to add it here.",
                isFavoritePredicate = isFavoritePredicate,
                downloadedIds = downloadedIds,
                downloadProgress = downloadProgress,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                onToggleFavorite = onToggleFavorite,
                header = {
                    PlayShuffleRow(favorites, onPlayAll, onShuffle)
                    OfflineCard(
                        songs = favorites,
                        downloadedIds = downloadedIds,
                        downloadProgress = downloadProgress,
                        autoDownload = autoDownload,
                        wifiOnly = wifiOnly,
                        onDownloadAll = { onDownloadAll(favorites) },
                        onCancel = onCancelDownloads,
                        onAutoDownloadChange = onAutoDownloadChange,
                        onWifiOnlyChange = onWifiOnlyChange,
                    )
                },
            )

            1 -> PlaylistsTab(playlists = playlists, onOpenPlaylist = onOpenPlaylist)

            2 -> SongsTab(
                songs = downloads,
                empty = "Nothing downloaded yet.\nDownload your liked songs from the Favorites tab, or use a song's menu.",
                isFavoritePredicate = isFavoritePredicate,
                downloadedIds = downloadedIds,
                downloadProgress = downloadProgress,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                onToggleFavorite = onToggleFavorite,
                header = {
                    DownloadsSummary(
                        count = downloads.size,
                        bytes = downloadedBytes,
                        inProgress = downloadProgress.size,
                        onCancel = onCancelDownloads,
                        onRemoveAll = { confirmRemoveAll = true },
                    )
                    PlayShuffleRow(downloads, onPlayAll, onShuffle)
                },
            )

            else -> SongsTab(
                songs = history,
                empty = "No playback history yet.\nSongs you listen to will appear here.",
                isFavoritePredicate = isFavoritePredicate,
                downloadedIds = downloadedIds,
                downloadProgress = downloadProgress,
                onSongClick = onSongClick,
                onSongMore = onSongMore,
                onToggleFavorite = onToggleFavorite,
                header = { PlayShuffleRow(history, onPlayAll, onShuffle) },
            )
        }
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotBlank()) {
                            onCreatePlaylist(name)
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Create") }
            },
            dismissButton = {
                Button(
                    onClick = { showCreatePlaylistDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkSurfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text("Cancel") }
            },
            containerColor = DarkSurface,
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            containerColor = DarkSurface,
            title = { Text("Remove all downloads?") },
            text = { Text("The songs stay in your library; only the copies on this device are deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveAll = false
                        onRemoveAllDownloads()
                    },
                ) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SongsTab(
    songs: List<SongItem>,
    empty: String,
    isFavoritePredicate: (String) -> Boolean,
    downloadedIds: Set<String>,
    downloadProgress: Map<String, Float>,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    onSongMore: (SongItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    header: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { header() } }
        if (songs.isEmpty()) {
            item { EmptyLibraryPlaceholder(message = empty) }
        } else {
            items(songs) { song ->
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

@Composable
private fun PlayShuffleRow(
    songs: List<SongItem>,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
) {
    if (songs.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { onPlayAll(songs) }, shape = RoundedCornerShape(12.dp)) {
            Icon(QMusicIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        OutlinedButton(onClick = { onShuffle(songs) }, shape = RoundedCornerShape(12.dp)) {
            Icon(QMusicIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

/** What is on the device out of the liked songs, the "Download all" button and the auto-download switches. */
@Composable
private fun OfflineCard(
    songs: List<SongItem>,
    downloadedIds: Set<String>,
    downloadProgress: Map<String, Float>,
    autoDownload: Boolean,
    wifiOnly: Boolean,
    onDownloadAll: () -> Unit,
    onCancel: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
) {
    val saved = songs.count { it.id in downloadedIds }
    val pending = songs.count { it.id in downloadProgress }
    val missing = songs.size - saved - pending

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (songs.isNotEmpty() && saved == songs.size) QMusicIcons.DownloadDone else QMusicIcons.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Listen offline",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            songs.isEmpty() -> "Like some songs to save them here."
                            pending > 0 -> "Downloading $pending ${if (pending == 1) "song" else "songs"} · $saved of ${songs.size} saved"
                            saved == songs.size -> "All ${songs.size} songs are on this device."
                            else -> "$saved of ${songs.size} songs are on this device."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (pending > 0) {
                LinearProgressIndicator(
                    progress = { if (songs.isEmpty()) 0f else saved.toFloat() / songs.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
                OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text("Cancel downloads") }
            } else if (missing > 0) {
                Button(onClick = onDownloadAll, shape = RoundedCornerShape(12.dp)) {
                    Icon(QMusicIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download all ($missing)")
                }
            }

            SwitchRow(
                title = "Auto-download liked songs",
                subtitle = "Every song you like is saved on this device.",
                checked = autoDownload,
                onChange = onAutoDownloadChange,
            )
            if (autoDownload) {
                SwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "Wait for Wi-Fi instead of using mobile data.",
                    checked = wifiOnly,
                    onChange = onWifiOnlyChange,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgRoot,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun DownloadsSummary(
    count: Int,
    bytes: Long,
    inProgress: Int,
    onCancel: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$count ${if (count == 1) "song" else "songs"} · ${formatBytes(bytes)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (inProgress > 0) "$inProgress still downloading" else "Available without a connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inProgress > 0) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (count > 0) {
                TextButton(onClick = onRemoveAll) { Text("Remove all") }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(playlists: List<PlaylistEntity>, onOpenPlaylist: (PlaylistEntity) -> Unit) {
    if (playlists.isEmpty()) {
        EmptyLibraryPlaceholder(message = "No custom playlists yet.\nCreate one with the + button.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        items(playlists) { playlist ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPlaylist(playlist) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < kb * kb -> "${(bytes / kb).toInt()} KB"
        bytes < kb * kb * kb -> "${((bytes / (kb * kb)) * 10).toInt() / 10.0} MB"
        else -> "${((bytes / (kb * kb * kb)) * 100).toInt() / 100.0} GB"
    }
}
