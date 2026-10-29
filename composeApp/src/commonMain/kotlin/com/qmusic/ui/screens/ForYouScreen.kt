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
 * "For you", like YouTube Music's samples: one song per screen, swiped up and down; each starts playing part-way in
 * as it settles. Like, save, share, or play the whole song from the buttons on the right.
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
) {
    val s = LocalStrings.current
    LaunchedEffect(Unit) { onShown() }

    if (samples.isEmpty()) {
        Box(modifier.fillMaxSize().background(DarkBackground).padding(32.dp), contentAlignment = Alignment.Center) {
            Text(s.forYouEmpty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val pager = rememberPagerState { samples.size }
    LaunchedEffect(pager.settledPage, samples.size) {
        samples.getOrNull(pager.settledPage)?.let(onSettle)
        if (pager.settledPage >= samples.size - NEAR_END) onNearEnd()
    }

    Box(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { samples[it].id }) { page ->
            SamplePage(
                song = samples[page],
                liked = isFavorite(samples[page].id),
                onToggleFavorite = { onToggleFavorite(samples[page]) },
                onAddToPlaylist = { onAddToPlaylist(samples[page]) },
                onShare = { onShare(samples[page]) },
                onPlayFull = { onPlayFull(samples[page]) },
            )
        }
        Text(
            text = s.tabForYou,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SamplePage(
    song: SongItem,
    liked: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onPlayFull: () -> Unit,
) {
    val s = LocalStrings.current
    val art = (song.thumbnail ?: song.thumbnails.lastOrNull()?.url)?.upscaleThumbnail(900)
    Box(modifier = Modifier.fillMaxSize()) {
        // The cover, blurred and darkened, fills the screen behind everything
        AsyncImage(
            model = art,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(48.dp),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.85f)))),
        )

        AsyncImage(
            model = art,
            contentDescription = song.title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceVariant),
            contentScale = ContentScale.Crop,
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.preview,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SideButton(if (liked) QMusicIcons.HeartFilled else QMusicIcons.Heart, if (liked) s.liked else s.like, onToggleFavorite)
                SideButton(QMusicIcons.PlaylistAdd, s.save, onAddToPlaylist)
                SideButton(QMusicIcons.Share, s.share, onShare)
                SideButton(QMusicIcons.Play, s.playFullSong, onPlayFull)
            }
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Fetch more songs when this few are left. */
private const val NEAR_END = 3
