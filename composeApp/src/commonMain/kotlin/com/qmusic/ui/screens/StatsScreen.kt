package com.qmusic.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.viewmodel.ListeningStats
import com.qmusic.ui.viewmodel.StatsPeriod

/** "Your listening": minutes, plays and the most played songs and artists of a period, with a way to share it. */
@Composable
fun StatsScreen(
    stats: ListeningStats?,
    period: StatsPeriod,
    onPeriodChange: (StatsPeriod) -> Unit,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onSongClick: (SongItem, List<SongItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val periodLabel = when (period) {
        StatsPeriod.MONTH -> s.statsLast30
        StatsPeriod.YEAR -> s.statsLastYear
        StatsPeriod.ALL -> s.statsAllTime
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(QMusicIcons.Back, contentDescription = s.back, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = s.statsTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (stats != null && stats.plays > 0) {
                IconButton(onClick = {
                    onShare(
                        s.statsShare(
                            periodLabel,
                            stats.minutes,
                            stats.topSongs.firstOrNull()?.first?.let { song -> "${song.title} - ${song.artists.joinToString { it.name }}" },
                            stats.topArtists.firstOrNull()?.first,
                        ),
                    )
                }) {
                    Icon(QMusicIcons.Share, contentDescription = s.share, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StatsPeriod.MONTH to s.statsLast30, StatsPeriod.YEAR to s.statsLastYear, StatsPeriod.ALL to s.statsAllTime)
                        .forEach { (value, label) ->
                            val selected = value == period
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) Color.White else DarkSurfaceVariant)
                                    .clickable { onPeriodChange(value) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                }
            }

            if (stats == null || stats.plays == 0) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(s.statsEmpty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // The big number, on a card coloured like a year-end recap
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF5B2A86), Color(0xFF1E1240))))
                            .padding(22.dp),
                    ) {
                        Text(periodLabel, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stats.minutes.toString(),
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black, fontSize = 56.sp),
                            color = Color.White,
                        )
                        Text(s.statsMinutes, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Figure(stats.plays.toString(), s.statsPlays)
                            Figure(stats.songs.toString(), s.statsSongs)
                        }
                    }
                }

                if (stats.topArtists.isNotEmpty()) {
                    item { Heading(s.statsTopArtists) }
                    itemsIndexed(stats.topArtists) { index, (name, plays) ->
                        RankRow(index + 1, picture = null, title = name, subtitle = s.statsTimes(plays), onClick = null)
                    }
                }

                item { Heading(s.statsTopSongs) }
                itemsIndexed(stats.topSongs) { index, (song, plays) ->
                    RankRow(
                        rank = index + 1,
                        picture = song.thumbnail ?: song.thumbnails.firstOrNull()?.url,
                        title = song.title,
                        subtitle = "${song.artists.joinToString { it.name }} · ${s.statsTimes(plays)}",
                        onClick = { onSongClick(song, stats.topSongs.map { it.first }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun RankRow(rank: Int, picture: String?, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        if (picture != null) {
            AsyncImage(
                model = picture,
                contentDescription = title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
