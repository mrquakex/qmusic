package com.qmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary

/** Asks the image host for a bigger rendition: thumbnail URLs carry their size (`=w60-h60`). */
fun String.upscaleThumbnail(px: Int): String =
    replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px").replace(Regex("=s\\d+"), "=s$px")

/**
 * A cover drawn sharp ([px] wide) without ever going blank: the address the small cover already used (in Coil's
 * cache, so it shows offline too) is drawn first, the large rendition over it once it loads. Offline, a song whose
 * large cover was never fetched still shows its small one instead of nothing.
 */
@Composable
fun SharpCover(
    url: String?,
    px: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (url == null) return
    Box(modifier = modifier) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        val sharp = url.upscaleThumbnail(px)
        if (sharp != url) {
            AsyncImage(model = sharp, contentDescription = contentDescription, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Square card used by every horizontal shelf (songs, albums, playlists). */
@Composable
fun ShelfCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    showPlayBadge: Boolean = false,
    isCurrent: Boolean = false,
) {
    val coverShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .width(cardWidth)
            .clip(coverShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(coverShape)
                .background(DarkSurfaceVariant)
                .border(
                    width = if (isCurrent) 1.5.dp else 0.5.dp,
                    color = if (isCurrent) Ink else BorderSubtle,
                    shape = coverShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (isCurrent || showPlayBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.66f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isCurrent) QMusicIcons.Waveform else QMusicIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = TextPrimary,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextSecondary,
            )
        }
    }
}

/** Round avatar for artists, as on YouTube Music. */
@Composable
fun ArtistCircleCard(
    name: String,
    subtitle: String?,
    thumbnail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(124.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(106.dp)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
                .border(0.5.dp, BorderSubtle, CircleShape),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = TextPrimary,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
