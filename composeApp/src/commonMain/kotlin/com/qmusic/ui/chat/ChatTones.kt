package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Choosing a ringtone for Qeyf calls: Qeyf's own sounds, the ringtones made from songs, and making a new one from a
 * song. A pick plays it once. With [allowDefault] (one person's ringtone) the first choice is "the one for everyone".
 */
@Composable
internal fun RingTonePicker(s: ChatStrings, title: String, current: String?, allowDefault: Boolean, forUid: String? = null, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val w = CallTones.words()
    val made by CallTones.made.collectAsState()
    var pickingSong by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { CallTones.stopPreview?.invoke() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (allowDefault) {
                    ToneRow(w.default, w.defaultNote, null, current == null) { onPick(null) }
                }
                ToneHeader(w.qeyfSounds)
                ChatSoundCatalog.options.getValue(SoundKind.RING).forEach { option ->
                    ToneRow(option.name(CallTones.english), null, null, option.id == current) {
                        onPick(option.id)
                        CallTones.preview?.invoke(option.id)
                    }
                }
                ToneHeader(w.fromSongs)
                made.forEach { tone ->
                    val id = CallTones.songTone(tone.uri)
                    ToneRow(tone.title, tone.artist.ifBlank { null }, tone.thumbnail, id == current, song = true) {
                        onPick(id)
                        CallTones.preview?.invoke(id)
                    }
                }
                MakeRow(w) { pickingSong = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Ink) } },
    )
    if (pickingSong) SongForTone(s, forUid, onDismiss = { pickingSong = false }) { onDismiss() }
}

/** What people who call this account hear while it rings: Qeyf's beeps, or one of the ringtones made from songs. */
@Composable
internal fun CallerTonePicker(s: ChatStrings, onDismiss: () -> Unit) {
    val w = CallTones.words()
    val made by CallTones.made.collectAsState()
    val current by CallTones.callerTone.collectAsState()
    val publishing by CallTones.publishing.collectAsState()
    var pickingSong by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { CallTones.stopPreview?.invoke() } }
    fun choose(tone: MadeTone?) {
        if (publishing) return
        failed = false
        tone?.let { CallTones.preview?.invoke(CallTones.songTone(it.uri)) }
        scope.launch { failed = CallTones.setCallerTone?.invoke(tone) != true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(w.callerTitle, color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(w.callerNoteRow, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ToneRow(w.callerNone, null, null, current == null) { choose(null) }
                if (made.isNotEmpty()) ToneHeader(w.fromSongs)
                made.forEach { tone ->
                    ToneRow(tone.title, tone.artist.ifBlank { null }, tone.thumbnail, current?.uri == tone.uri, song = true) { choose(tone) }
                }
                MakeRow(w) { pickingSong = true }
                if (publishing) Text(w.publishing, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                if (failed) Text(w.callersFailed, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Ink) } },
    )
    if (pickingSong) SongForTone(s, null, onDismiss = { pickingSong = false }) { onDismiss() }
}

/** Finding the song to make a ringtone from; picking one opens the ringtone maker on it. */
@Composable
private fun SongForTone(s: ChatStrings, forUid: String?, onDismiss: () -> Unit, onOpened: () -> Unit) {
    StatusSongPicker(
        s = s,
        songs = emptyList(),
        search = { q -> CallTones.searchSongs?.invoke(q).orEmpty() },
        onPick = { song ->
            onDismiss()
            onOpened()
            CallTones.makingFor = forUid
            CallTones.makeFromSong?.invoke(song)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ToneHeader(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
private fun ToneRow(title: String, note: String?, picture: String?, on: Boolean, song: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) TickRead else DarkSurface), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Ink))
        }
        Spacer(Modifier.width(12.dp))
        if (song) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(DarkSurface), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(QMusicIcons.Bell, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(QMusicIcons.Play, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun MakeRow(w: CallToneWords, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(DarkSurface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(TickRead), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Bell, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.makeNew, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(w.makeHint, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/** The settings rows of song ringtones (in Sounds): what callers hear, and hearing what the called person chose. */
@Composable
internal fun CallerToneSettings(s: ChatStrings) {
    val w = CallTones.words()
    val caller by CallTones.callerTone.collectAsState()
    val hear by CallTones.hearOthers.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.callerTitle, color = TextPrimary, fontSize = 15.sp)
            Text(w.callerNoteRow, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            caller?.title ?: w.callerNone,
            color = TickRead,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
    SettingSwitch(w.hearOthers, w.hearOthersNote, hear) { CallTones.setHearOthers?.invoke(it) }
    if (picking) CallerTonePicker(s) { picking = false }
}

/** A person's own ringtone (on their profile): the one for everyone, or one of theirs. */
@Composable
internal fun PersonRingRow(s: ChatStrings, user: ChatUser) {
    val w = CallTones.words()
    val per by CallTones.perPerson.collectAsState()
    CallTones.made.collectAsState().value
    val current = per[user.uid]
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Bell, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(w.personRing, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            current?.let { CallTones.name(it) } ?: w.default,
            color = TickRead,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
    if (picking) {
        RingTonePicker(s, w.personRingTitle, current, allowDefault = true, forUid = user.uid, onPick = { id -> CallTones.setRingFor?.invoke(user.uid, id) }) { picking = false }
    }
}
