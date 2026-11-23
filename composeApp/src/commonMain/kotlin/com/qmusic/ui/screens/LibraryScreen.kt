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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.engine.models.CollectionItem
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons

/**
 * The library, laid out as YouTube Music's: "Library" with history, search and stats; filter chips (Downloads,
 * Playlists, Songs, Albums, Artists; the chosen one with a cross before it to clear it); the order ("Recent activity")
 * and list / grid; then everything in one list, "Liked music" pinned first; and "+ New" for a playlist.
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
    startTab: Int = 0,
    ytPlaylists: List<CollectionItem> = emptyList(),
    ytAlbums: List<CollectionItem> = emptyList(),
    ytArtists: List<CollectionItem> = emptyList(),
    onOpenCollection: (CollectionItem) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenLiked: () -> Unit = {},
) {
    val s = LocalStrings.current
    val w = remember(s.english) { LibraryWords(s.english) }
    // The app's "open the downloads" lands here with the Downloads chip on.
    var filter by remember(startTab) { mutableStateOf(if (startTab == 2) LibraryFilter.DOWNLOADS else null) }
    var showHistory by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(LibraryOrder.RECENT) }
    var orderMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    val words = query.trim().lowercase()
    fun matches(vararg texts: String?) = words.isEmpty() || texts.any { it?.lowercase()?.contains(words) == true }
    fun refine(songs: List<SongItem>): List<SongItem> = songs
        .filter { song -> matches(song.title, song.album?.name, *song.artists.map { it.name }.toTypedArray()) }
        .let { list ->
            when (order) {
                LibraryOrder.RECENT -> list
                LibraryOrder.AZ -> list.sortedBy { it.title.lowercase() }
                LibraryOrder.ZA -> list.sortedByDescending { it.title.lowercase() }
            }
        }

    // Everything as entries of one list.
    val liked = LibraryEntry.Liked(favorites.size)
    val entries: List<LibraryEntry> = buildList {
        val f = filter
        if (f == null || f == LibraryFilter.PLAYLISTS) {
            addAll(playlists.map { LibraryEntry.Local(it) })
            addAll(ytPlaylists.map { LibraryEntry.Remote(it, LibraryKind.PLAYLIST) })
        }
        if (f == null || f == LibraryFilter.ALBUMS) addAll(ytAlbums.map { LibraryEntry.Remote(it, LibraryKind.ALBUM) })
        if (f == null || f == LibraryFilter.ARTISTS) addAll(ytArtists.map { LibraryEntry.Remote(it, LibraryKind.ARTIST) })
    }.filter { matches(it.title, it.subtitle(w)) }.let { list ->
        when (order) {
            LibraryOrder.RECENT -> list
            LibraryOrder.AZ -> list.sortedBy { it.title.lowercase() }
            LibraryOrder.ZA -> list.sortedByDescending { it.title.lowercase() }
        }
    }.let { list ->
        // "Liked music" stays on top, pinned, where playlists are shown.
        if ((filter == null || filter == LibraryFilter.PLAYLISTS) && matches(w.liked)) listOf(liked) + list else list
    }

    fun open(entry: LibraryEntry) = when (entry) {
        is LibraryEntry.Liked -> onOpenLiked()
        is LibraryEntry.Local -> onOpenPlaylist(entry.playlist)
        is LibraryEntry.Remote -> onOpenCollection(entry.item)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // "Library", and what is up the corner.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showHistory) s.tabHistory else w.library,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showHistory = !showHistory }) {
                    Icon(
                        if (showHistory) QMusicIcons.Close else QMusicIcons.History,
                        contentDescription = s.tabHistory,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) {
                    Icon(if (searching) QMusicIcons.Close else QMusicIcons.Search, s.searchLibrary, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onOpenStats) {
                    Icon(QMusicIcons.Stats, s.statsTitle, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }

            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(s.searchLibrary) },
                    leadingIcon = { Icon(QMusicIcons.Search, contentDescription = null) },
                    shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (!showHistory) {
                // The filter chips: the chosen one comes first with a cross before it, the others hide.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chosen = filter
                    if (chosen != null) {
                        Box(
                            Modifier.size(42.dp).pressScale(0.9f).clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).clickable { filter = null },
                            contentAlignment = Alignment.Center,
                        ) { Icon(QMusicIcons.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
                        LibraryChip(w.label(chosen), selected = true) { filter = null }
                    } else {
                        LibraryFilter.entries.forEach { f -> LibraryChip(w.label(f), selected = false) { filter = f } }
                    }
                }
            }

            when {
                showHistory -> SongList(
                    songs = refine(history),
                    empty = if (words.isEmpty()) s.historyEmpty else s.libraryNoMatch,
                    isFavoritePredicate = isFavoritePredicate,
                    downloadedIds = downloadedIds,
                    downloadProgress = downloadProgress,
                    onSongClick = onSongClick,
                    onSongMore = onSongMore,
                    onToggleFavorite = onToggleFavorite,
                    header = { PlayShuffleRow(refine(history), onPlayAll, onShuffle) },
                )

                filter == LibraryFilter.SONGS -> SongList(
                    songs = refine(favorites),
                    empty = if (words.isEmpty()) s.favoritesEmpty else s.libraryNoMatch,
                    isFavoritePredicate = isFavoritePredicate,
                    downloadedIds = downloadedIds,
                    downloadProgress = downloadProgress,
                    onSongClick = onSongClick,
                    onSongMore = onSongMore,
                    onToggleFavorite = onToggleFavorite,
                    header = {
                        OrderRow(w, order, orderMenu, onMenu = { orderMenu = it }, onOrder = { order = it }, grid = null, onGrid = {})
                        PlayShuffleRow(refine(favorites), onPlayAll, onShuffle)
                    },
                )

                filter == LibraryFilter.DOWNLOADS -> SongList(
                    songs = refine(downloads),
                    empty = if (words.isEmpty()) s.downloadsEmpty else s.libraryNoMatch,
                    isFavoritePredicate = isFavoritePredicate,
                    downloadedIds = downloadedIds,
                    downloadProgress = downloadProgress,
                    onSongClick = onSongClick,
                    onSongMore = onSongMore,
                    onToggleFavorite = onToggleFavorite,
                    header = {
                        OrderRow(w, order, orderMenu, onMenu = { orderMenu = it }, onOrder = { order = it }, grid = null, onGrid = {})
                        DownloadsSummary(
                            count = downloads.size,
                            bytes = downloadedBytes,
                            inProgress = downloadProgress.size,
                            onCancel = onCancelDownloads,
                            onRemoveAll = { confirmRemoveAll = true },
                        )
                        PlayShuffleRow(refine(downloads), onPlayAll, onShuffle)
                    },
                )

                else -> {
                    val orderRow: @Composable () -> Unit = {
                        OrderRow(w, order, orderMenu, onMenu = { orderMenu = it }, onOrder = { order = it }, grid = grid, onGrid = { grid = !grid })
                    }
                    if (entries.isEmpty()) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            orderRow()
                            EmptyLibraryPlaceholder(
                                if (words.isNotEmpty()) s.libraryNoMatch else when (filter) {
                                    LibraryFilter.ALBUMS -> s.albumsEmpty
                                    LibraryFilter.ARTISTS -> s.artistsEmpty
                                    else -> s.playlistsEmpty
                                },
                            )
                        }
                    } else if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) { orderRow() }
                            items(entries, key = { it.key }) { entry -> EntryCard(entry, w) { open(entry) } }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 180.dp),
                        ) {
                            item { Box(Modifier.padding(horizontal = 16.dp)) { orderRow() } }
                            items(entries, key = { it.key }) { entry -> EntryRow(entry, w) { open(entry) } }
                        }
                    }
                }
            }
        }

        // "+ New": a playlist, as on YouTube Music (over the list, above the player).
        if (!showHistory && (filter == null || filter == LibraryFilter.PLAYLISTS)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 104.dp)
                    .pressScale(0.95f)
                    .clip(RoundedCornerShape(50))
                    .background(Ink)
                    .clickable { showCreatePlaylistDialog = true }
                    .padding(start = 20.dp, end = 26.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QMusicIcons.Add, contentDescription = null, tint = InkInverse, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(w.new, color = InkInverse, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text(s.newPlaylistTitle) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(s.playlistNameLabel) },
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
                ) { Text(s.create) }
            },
            dismissButton = {
                Button(
                    onClick = { showCreatePlaylistDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(s.cancel) }
            },
            containerColor = DarkSurface,
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            containerColor = DarkSurface,
            title = { Text(s.removeAllDownloadsTitle) },
            text = { Text(s.removeAllDownloadsMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    onRemoveAllDownloads()
                }) { Text(s.removeAll) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text(s.cancel) } },
        )
    }

}

// ==================================================================== the words

internal class LibraryWords(val english: Boolean) {
    private fun t(tr: String, en: String) = if (english) en else tr
    val library = t("Kitaplık", "Library")
    val liked = t("Beğendiğim müzikler", "Liked music")
    val autoPlaylist = t("Otomatik oynatma listesi", "Auto playlist")
    val recent = t("Son etkinlik", "Recent activity")
    val az = t("A'dan Z'ye", "A to Z")
    val za = t("Z'den A'ya", "Z to A")
    val new = t("Yeni", "New")
    val playlist = t("Oynatma listesi", "Playlist")
    val album = t("Albüm", "Album")
    val artist = t("Sanatçı", "Artist")
    val onDevice = t("Bu cihazda", "On this device")
    fun songs(n: Int) = t("$n şarkı", if (n == 1) "1 song" else "$n songs")
    fun label(f: LibraryFilter) = when (f) {
        LibraryFilter.DOWNLOADS -> t("İndirilenler", "Downloads")
        LibraryFilter.PLAYLISTS -> t("Oynatma listeleri", "Playlists")
        LibraryFilter.SONGS -> t("Şarkılar", "Songs")
        LibraryFilter.ALBUMS -> t("Albümler", "Albums")
        LibraryFilter.ARTISTS -> t("Sanatçılar", "Artists")
    }
    fun order(o: LibraryOrder) = when (o) {
        LibraryOrder.RECENT -> recent
        LibraryOrder.AZ -> az
        LibraryOrder.ZA -> za
    }
}

internal enum class LibraryFilter { DOWNLOADS, PLAYLISTS, SONGS, ALBUMS, ARTISTS }

/** How the library is ordered. */
internal enum class LibraryOrder { RECENT, AZ, ZA }

private enum class LibraryKind { PLAYLIST, ALBUM, ARTIST }

/** One line of the library. */
private sealed interface LibraryEntry {
    val key: String
    val title: String
    fun subtitle(w: LibraryWords): String

    class Liked(val count: Int) : LibraryEntry {
        override val key = "liked"
        override val title = ""
        override fun subtitle(w: LibraryWords) = w.autoPlaylist
    }

    class Local(val playlist: PlaylistEntity) : LibraryEntry {
        override val key = "local:" + playlist.id
        override val title: String = playlist.name
        override fun subtitle(w: LibraryWords) = w.playlist + " • " + w.onDevice
    }

    class Remote(val item: CollectionItem, val kind: LibraryKind) : LibraryEntry {
        override val key = "yt:" + item.browseId
        override val title: String = item.title
        override fun subtitle(w: LibraryWords): String {
            val what = when (kind) {
                LibraryKind.PLAYLIST -> w.playlist
                LibraryKind.ALBUM -> w.album
                LibraryKind.ARTIST -> w.artist
            }
            val rest = item.subtitle?.takeIf { it.isNotBlank() }
            // YouTube Music's own line often starts with the kind already ("Album • ..."): it is not said twice.
            return if (rest == null) what else if (rest.startsWith(what, ignoreCase = true)) rest else "$what • $rest"
        }
    }
}

// ==================================================================== pieces

@Composable
private fun LibraryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = if (selected) InkInverse else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .pressScale(0.95f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ink else DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** "Recent activity ⌄" (the order, from a menu), and the list / grid switch at the other end. */
@Composable
private fun OrderRow(
    w: LibraryWords,
    order: LibraryOrder,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onOrder: (LibraryOrder) -> Unit,
    grid: Boolean?,
    onGrid: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onMenu(true) }.padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(w.order(order), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Icon(QMusicIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, containerColor = DarkSurfaceVariant) {
                LibraryOrder.entries.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(w.order(o), fontWeight = if (o == order) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            onOrder(o)
                            onMenu(false)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (grid != null) {
            IconButton(onClick = onGrid) {
                Icon(if (grid) QMusicIcons.ListView else QMusicIcons.Grid, contentDescription = if (grid) com.qmusic.ui.A11y.listView else com.qmusic.ui.A11y.gridView, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** "Liked music"'s picture: a thumb up on violet to pink. */
@Composable
internal fun LikedTile(size: Dp, corner: Dp = 4.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF8C5CF6), Color(0xFFF0569E)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(QMusicIcons.ThumbUpFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun EntryPicture(entry: LibraryEntry, size: Dp) {
    when (entry) {
        is LibraryEntry.Liked -> LikedTile(size)
        is LibraryEntry.Local -> Box(
            Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(QMusicIcons.Queue, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.4f)) }
        is LibraryEntry.Remote -> coil3.compose.AsyncImage(
            model = entry.item.thumbnail,
            contentDescription = entry.title,
            modifier = Modifier.size(size).clip(if (entry.kind == LibraryKind.ARTIST) CircleShape else RoundedCornerShape(4.dp)).background(DarkSurfaceVariant),
            contentScale = ContentScale.Crop,
        )
    }
}

/** A line of the list: the picture, the name, what it is under it. */
@Composable
private fun EntryRow(entry: LibraryEntry, w: LibraryWords, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryPicture(entry, 64.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (entry is LibraryEntry.Liked) w.liked else entry.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry is LibraryEntry.Liked) {
                    Icon(QMusicIcons.Pin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(45f))
                    Spacer(Modifier.width(6.dp))
                }
                Text(entry.subtitle(w), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A card of the grid: the picture filling the width, the name and what it is under it. */
@Composable
private fun EntryCard(entry: LibraryEntry, w: LibraryWords, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressScale(0.97f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) { EntryPicture(entry, maxWidth) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (entry is LibraryEntry.Liked) w.liked else entry.title,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(entry.subtitle(w), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SongList(
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { header() } }
        if (songs.isEmpty()) {
            item { EmptyLibraryPlaceholder(message = empty) }
        } else {
            items(songs, key = { it.id }) { song ->
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
    val s = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.pressScale(0.95f).clip(RoundedCornerShape(50)).background(Ink).clickable { onPlayAll(songs) }.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QMusicIcons.Play, contentDescription = null, tint = InkInverse, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.play, color = InkInverse, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.pressScale(0.95f).clip(RoundedCornerShape(50)).background(DarkSurfaceVariant).clickable { onShuffle(songs) }.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QMusicIcons.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.shuffle, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
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
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.downloadsSummaryCount(count, formatBytes(bytes)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(s.downloadsSummaryStatus(inProgress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (inProgress > 0) TextButton(onClick = onCancel) { Text(s.cancel) }
            if (count > 0) TextButton(onClick = onRemoveAll) { Text(s.removeAll) }
        }
    }
}

@Composable
internal fun EmptyLibraryPlaceholder(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

