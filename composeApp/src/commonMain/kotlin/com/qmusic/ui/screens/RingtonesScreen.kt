package com.qmusic.ui.screens

import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.ringtone.MadeRingtone
import com.qmusic.ringtone.RingtoneStudio
import com.qmusic.ringtone.RingtoneTarget
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons

/**
 * The ringtones made in the app: play one, see which sounds it is set as, set it as another (or give it to a contact),
 * or delete it.
 */
@Composable
fun RingtonesScreen(
    studio: RingtoneStudio,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val saved by studio.saved.collectAsState()
    val inUse by studio.inUse.collectAsState()
    val playing by studio.playingSaved.collectAsState()
    val qeyfRing by com.qmusic.ui.chat.CallTones.ring.collectAsState()
    val callerTone by com.qmusic.ui.chat.CallTones.callerTone.collectAsState()
    var actions by remember { mutableStateOf<MadeRingtone?>(null) }
    var confirmDelete by remember { mutableStateOf<MadeRingtone?>(null) }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(s.myRingtones, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(s.myRingtonesSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (saved.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = s.ringtonesEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(saved, key = { it.uri }) { made ->
                    RingtoneRow(
                        made = made,
                        targets = inUse[made.uri].orEmpty(),
                        qeyf = listOfNotNull(
                            com.qmusic.ui.chat.CallTones.words().inUseQeyf.takeIf { qeyfRing == com.qmusic.ui.chat.CallTones.songTone(made.uri) },
                            com.qmusic.ui.chat.CallTones.words().inUseCallers.takeIf { callerTone?.uri == made.uri },
                        ),
                        playing = playing == made.uri,
                        onPlay = { studio.togglePlay(made) },
                        onMore = { actions = made },
                    )
                }
            }
        }
    }

    actions?.let { made ->
        AlertDialog(
            onDismissRequest = { actions = null },
            containerColor = DarkSurface,
            title = { Text(made.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionLine(QMusicIcons.Bell, s.ringtoneSetPhone) {
                        actions = null
                        studio.applySaved(made, RingtoneTarget.PHONE)
                    }
                    ActionLine(QMusicIcons.Queue, s.ringtoneSetNotification) {
                        actions = null
                        studio.applySaved(made, RingtoneTarget.NOTIFICATION)
                    }
                    ActionLine(QMusicIcons.Moon, s.ringtoneSetAlarm) {
                        actions = null
                        studio.applySaved(made, RingtoneTarget.ALARM)
                    }
                    ActionLine(QMusicIcons.Account, s.ringtoneSetContact) {
                        actions = null
                        studio.applySavedToContact(made)
                    }
                    val w = com.qmusic.ui.chat.CallTones.words()
                    ActionLine(QMusicIcons.Phone, w.qeyfCalls) {
                        actions = null
                        studio.applySavedToQeyf(made)
                    }
                    ActionLine(QMusicIcons.Chat, w.callers) {
                        actions = null
                        studio.applySavedToCallers(made)
                    }
                    ActionLine(QMusicIcons.Trash, s.ringtoneDelete) {
                        actions = null
                        confirmDelete = made
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actions = null }) { Text(s.close) } },
        )
    }

    confirmDelete?.let { made ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = DarkSurface,
            title = { Text(s.ringtoneDeleteTitle) },
            text = { Text(s.ringtoneDeleteMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    studio.delete(made)
                }) { Text(s.ringtoneDelete) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(s.cancel) } },
        )
    }
}

@Composable
private fun RingtoneRow(made: MadeRingtone, targets: Set<RingtoneTarget>, qeyf: List<String>, playing: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            made.thumbnail?.let { AsyncImage(it, made.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(
                modifier = Modifier.fillMaxSize().background(InkInverse.copy(alpha = if (playing) 0.55f else 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playing) QMusicIcons.Pause else QMusicIcons.Play,
                    contentDescription = if (playing) s.ringtoneStopPreview else s.ringtonePreview,
                    tint = Ink,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = made.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(s.ringtoneLength(((made.durationMs + 500) / 1000).toInt()), made.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (targets.isNotEmpty() || qeyf.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val labels = RingtoneTarget.entries.filter { it in targets }.map { target ->
                        when (target) {
                            RingtoneTarget.PHONE -> s.ringtoneInUsePhone
                            RingtoneTarget.NOTIFICATION -> s.ringtoneInUseNotification
                            RingtoneTarget.ALARM -> s.ringtoneInUseAlarm
                        }
                    } + qeyf
                    labels.forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkInverse,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Ink)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        IconButton(onClick = onMore) {
            Icon(QMusicIcons.More, contentDescription = s.moreOptions, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ActionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
