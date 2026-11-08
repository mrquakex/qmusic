package com.qmusic.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.qmusic.ui.components.VideoSurface
import com.qmusic.ui.theme.QMusicHeaderLogo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.components.upscaleThumbnail
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * "For you", like YouTube Music's samples: one clip per screen, full screen, swiped up and down; each starts playing
 * part-way in as it settles. Tap the picture to pause, and like, save, share or play the whole song from the buttons on
 * the right. The mini player stays out of this page (the preview is not what the player keeps).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ForYouScreen(
    samples: List<SongItem>,
    isFavorite: (String) -> Boolean,
    onShown: () -> Unit,
    onSettle: (SongItem) -> Unit,
    onNearEnd: () -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onShare: (SongItem) -> Unit,
    onPlayFull: (SongItem) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isPlaying: Boolean = true,
    onTogglePlayback: () -> Unit = {},
) {
    val s = LocalStrings.current
    LaunchedEffect(Unit) { onShown() }

    if (samples.isEmpty()) {
        Box(modifier.fillMaxSize().background(DarkBackground).padding(32.dp), contentAlignment = Alignment.Center) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            } else {
                Text(s.forYouEmpty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val pager = rememberPagerState { samples.size }
    LaunchedEffect(pager.settledPage, samples.size) {
        samples.getOrNull(pager.settledPage)?.let(onSettle)
        if (pager.settledPage >= samples.size - NEAR_END) onNearEnd()
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { samples[it].id }) { page ->
            val song = samples[page]
            SamplePage(
                song = song,
                // One player draws the picture: only the page that has settled shows it, the others show their cover.
                showVideo = page == pager.settledPage && song.hasVideo,
                playing = isPlaying,
                liked = isFavorite(song.id),
                onTogglePlayback = onTogglePlayback,
                onToggleFavorite = { onToggleFavorite(song) },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onShare = { onShare(song) },
                onPlayFull = { onPlayFull(song) },
            )
        }
        QMusicHeaderLogo(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SamplePage(
    song: SongItem,
    showVideo: Boolean,
    playing: Boolean,
    liked: Boolean,
    onTogglePlayback: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onPlayFull: () -> Unit,
) {
    val s = LocalStrings.current
    val art = (song.thumbnail ?: song.thumbnails.lastOrNull()?.url)?.upscaleThumbnail(900)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTogglePlayback,
            ),
    ) {
        // The cover, blurred, fills the screen: it is what shows until the clip's first frame, and for a song without one.
        AsyncImage(
            model = art,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(48.dp),
            contentScale = ContentScale.Crop,
        )
        if (!song.hasVideo) {
            AsyncImage(
                model = art,
                contentDescription = song.title,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceVariant),
                contentScale = ContentScale.Crop,
            )
        }
        if (showVideo) VideoSurface(trackKey = song.id, modifier = Modifier.fillMaxSize(), fill = true)

        // Darker towards the bottom and the top, where the writing sits.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.22f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )

        if (!playing && showVideo) {
            Icon(
                imageVector = QMusicIcons.Play,
                contentDescription = s.play,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SideButton(if (liked) QMusicIcons.HeartFilled else QMusicIcons.Heart, if (liked) s.liked else s.like, onToggleFavorite)
            SideButton(QMusicIcons.PlaylistAdd, s.save, onAddToPlaylist)
            SideButton(QMusicIcons.Share, s.share, onShare)
            SideButton(QMusicIcons.Play, s.play, onPlayFull)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 96.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceVariant),
            ) {
                AsyncImage(
                    model = song.thumbnail ?: song.thumbnails.lastOrNull()?.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fetch more songs when this few are left. */
private const val NEAR_END = 3
