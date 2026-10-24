package com.qmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/** The "more" sheet of a song: queue, playlists, like, download and a shortcut to its artist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: SongItem,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDownload: () -> Unit,
    onGoToArtist: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onStartRadio: () -> Unit = {},
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgSurface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                ) {
                    val thumb = song.thumbnail ?: song.thumbnails.firstOrNull()?.url
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artists.joinToString { it.name }.ifEmpty { LocalStrings.current.unknownArtist },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val s = LocalStrings.current
            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

            ActionRow(QMusicIcons.Radio, s.startRadio, onStartRadio)
            ActionRow(QMusicIcons.Next, s.playNext, onPlayNext)
            ActionRow(QMusicIcons.Queue, s.addToQueue, onAddToQueue)
            ActionRow(QMusicIcons.PlaylistAdd, s.addToPlaylist, onAddToPlaylist)
            if (onRemoveFromPlaylist != null) ActionRow(QMusicIcons.Trash, s.removeFromThisPlaylist, onRemoveFromPlaylist)
            ActionRow(
                icon = if (isFavorite) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                label = if (isFavorite) s.removeFromFavorites else s.addToFavorites,
                onClick = onToggleFavorite,
            )
            ActionRow(
                icon = if (isDownloaded) QMusicIcons.Trash else QMusicIcons.Download,
                label = if (isDownloaded) s.removeDownload else s.download,
                onClick = onToggleDownload,
            )
            ActionRow(QMusicIcons.Share, s.share, onShare)
            if (onGoToArtist != null) {
                ActionRow(QMusicIcons.Account, s.goToArtist, onGoToArtist)
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
