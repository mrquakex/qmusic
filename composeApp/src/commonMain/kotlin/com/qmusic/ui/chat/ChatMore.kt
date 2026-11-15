package com.qmusic.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// ------------------------------------------------------------------ polls

private const val MAX_POLL_OPTIONS = 12

/** Making a poll: the question, the choices (2-12), one or several answers. */
@Composable
internal fun PollDialog(s: ChatStrings, onSend: (ChatPoll) -> Unit, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multiple by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val ready = question.isNotBlank() && filled.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.poll, color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Field(question, { question = it.take(300) }, s.pollQuestion)
                Spacer(Modifier.height(10.dp))
                options.forEachIndexed { i, value ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.weight(1f)) { Field(value, { options[i] = it.take(100) }, s.pollOption + " ${i + 1}") }
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(i) }) { Icon(QMusicIcons.Close, null, tint = TextSecondary) }
                        }
                    }
                }
                if (options.size < MAX_POLL_OPTIONS) {
                    Text(
                        "+ " + s.pollAddOption,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { options.add("") }.padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { multiple = !multiple }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.pollMultiple, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = multiple,
                        onCheckedChange = { multiple = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.White, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = DarkSurface),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onDismiss(); onSend(ChatPoll(question.trim(), filled, multiple)) }) {
                Text(s.pollCreate, color = if (ready) Color.White else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } },
    )
}

/**
 * A poll in a message: each choice with how many picked it (a bar across), the person's own picks ticked. A tap picks
 * or takes back; the total opens who voted for what.
 */
@Composable
internal fun PollCard(
    poll: ChatPoll,
    votes: Map<String, List<Int>>,
    myUid: String,
    mine: Boolean,
    s: ChatStrings,
    onVote: (List<Int>) -> Unit,
    onShowVoters: () -> Unit,
) {
    val fg = if (mine) Color.Black else TextPrimary
    val soft = if (mine) Color.Black.copy(alpha = 0.55f) else TextSecondary
    val myPicks = votes[myUid].orEmpty()
    val voters = votes.size
    Column(Modifier.width(270.dp).padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
        Text("📊 " + poll.question, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(if (poll.multiple) s.pollPickMany else s.pollPickOne, color = soft, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        poll.options.forEachIndexed { i, option ->
            val count = votes.values.count { i in it }
            val share = if (voters > 0) count.toFloat() / voters else 0f
            val picked = i in myPicks
            Box(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (mine) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f))
                    .clickable {
                        val next = when {
                            poll.multiple -> if (picked) myPicks - i else myPicks + i
                            picked -> emptyList()
                            else -> listOf(i)
                        }
                        onVote(next)
                    },
            ) {
                // The share of votes, as a bar behind the choice.
                Box(Modifier.matchParentSize().padding(end = 0.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(TickRead.copy(alpha = if (mine) 0.25f else 0.3f)))
                }
                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).clip(if (poll.multiple) RoundedCornerShape(4.dp) else CircleShape)
                            .background(if (picked) TickRead else Color.Transparent)
                            .then(if (picked) Modifier else Modifier.background(soft.copy(alpha = 0.25f))),
                        contentAlignment = Alignment.Center,
                    ) { if (picked) Icon(QMusicIcons.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Text(option, color = fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(count.toString(), color = soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            "$voters ${s.pollVotes} · ${s.pollVoters}",
            color = soft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowVoters).padding(vertical = 4.dp),
        )
    }
}

/** Who voted for each choice. */
@Composable
internal fun VotersDialog(poll: ChatPoll, votes: Map<String, List<Int>>, nameOf: (String) -> String, s: ChatStrings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(poll.question, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                poll.options.forEachIndexed { i, option ->
                    val who = votes.filterValues { i in it }.keys.map(nameOf)
                    item(key = "o$i") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("$option · ${who.size}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (who.isEmpty()) "—" else who.joinToString(", "), color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Color.White) } },
    )
}

// ------------------------------------------------------------------ places

/**
 * A place in a message: a little map (OpenStreetMap data drawn by CARTO) with a pin in the middle; a tap opens it in
 * the phone's maps app.
 */
@Composable
internal fun LocationCard(location: ChatLocation, mine: Boolean, s: ChatStrings) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val zoom = 15
    val worldPx = 256.0 * (1 shl zoom)
    val px = (location.lng + 180.0) / 360.0 * worldPx
    val latRad = location.lat * PI / 180.0
    val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * worldPx
    val tileX = floor(px / 256.0).toInt()
    val tileY = floor(py / 256.0).toInt()
    val boxW = 260f
    val boxH = 150f
    // A 256-pixel tile is drawn 128 dp wide (the @2x tiles stay sharp).
    val scale = 0.5f
    Column(
        Modifier.padding(4.dp).width(boxW.dp).clip(RoundedCornerShape(12.dp))
            .clickable { runCatching { uriHandler.openUri("https://www.google.com/maps/search/?api=1&query=${location.lat},${location.lng}") } },
    ) {
        Box(Modifier.width(boxW.dp).height(boxH.dp).background(Color(0xFFE5E3DF))) {
            for (dx in -1..1) for (dy in -1..1) {
                val tx = tileX + dx
                val ty = tileY + dy
                val left = boxW / 2 + ((tx * 256.0 - px) * scale).toFloat()
                val top = boxH / 2 + ((ty * 256.0 - py) * scale).toFloat()
                val n = 1 shl zoom
                AsyncImage(
                    model = "https://a.basemaps.cartocdn.com/rastertiles/voyager/$zoom/${((tx % n) + n) % n}/$ty@2x.png",
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset(left.dp, top.dp).size((256 * scale).dp),
                )
            }
            // The pin: its point on the place.
            Canvas(Modifier.size(28.dp, 36.dp).align(Alignment.Center).offset(y = (-14).dp)) {
                val r = size.width / 2.4f
                val c = Offset(size.width / 2, r + 2f)
                drawCircle(Color(0xFFE53935), radius = r, center = c)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - r * 0.7f, c.y + r * 0.6f)
                    lineTo(size.width / 2, size.height)
                    lineTo(c.x + r * 0.7f, c.y + r * 0.6f)
                    close()
                }
                drawPath(path, Color(0xFFE53935))
                drawCircle(Color.White, radius = r * 0.4f, center = c)
            }
            Text(
                "© OpenStreetMap © CARTO",
                color = Color.Black.copy(alpha = 0.6f),
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 3.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().background(if (mine) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📍 " + location.label.ifBlank { s.location }, color = if (mine) Color.Black else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(s.openMap, color = if (mine) Color.Black.copy(alpha = 0.6f) else TextSecondary, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ contacts

/** A contact in a message: name and number, with Call and Save. */
@Composable
internal fun ContactCard(contact: ChatContact, mine: Boolean, s: ChatStrings, onSave: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val fg = if (mine) Color.Black else TextPrimary
    val soft = if (mine) Color.Black.copy(alpha = 0.55f) else TextSecondary
    Column(Modifier.padding(4.dp).width(250.dp).clip(RoundedCornerShape(12.dp)).background(if (mine) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(contact.name.ifBlank { "?" }, null, 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.name.ifBlank { contact.phone }, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.phone, color = soft, fontSize = 13.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(soft.copy(alpha = 0.2f)))
        Row(Modifier.fillMaxWidth()) {
            Text(
                s.call,
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clickable { runCatching { uriHandler.openUri("tel:" + contact.phone.filter { it.isDigit() || it == '+' }) } }.padding(vertical = 10.dp),
            )
            Box(Modifier.width(1.dp).height(40.dp).background(soft.copy(alpha = 0.2f)))
            Text(
                s.saveContact,
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clickable(onClick = onSave).padding(vertical = 10.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ reactions

/** Who put which reaction on a message; the person's own can be taken away from here. */
@Composable
internal fun ReactionsDialog(
    reactions: Map<String, String>,
    myUid: String,
    nameOf: (String) -> String,
    userOf: (String) -> ChatUser,
    s: ChatStrings,
    onRemoveMine: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.reactionsTitle + " · " + reactions.size, color = TextPrimary) },
        text = {
            Column {
                // A row of each emoji with how many, then each person.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    reactions.values.groupingBy { it }.eachCount().forEach { (emoji, count) ->
                        Text("$emoji $count", color = TextPrimary, fontSize = 15.sp)
                    }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(reactions.entries.sortedBy { if (it.key == myUid) "" else nameOf(it.key) }, key = { it.key }) { (uid, emoji) ->
                        val me = uid == myUid
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = me) { onDismiss(); onRemoveMine() }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChatAvatar(userOf(uid), 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (me) s.you else nameOf(uid), color = TextPrimary, fontSize = 15.sp)
                                if (me) Text(s.tapToRemove, color = TextMuted, fontSize = 12.sp)
                            }
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Color.White) } },
    )
}

// ------------------------------------------------------------------ the lock

/** The messages behind the phone's own lock; the prompt opens by itself, and again with the button. */
@Composable
internal fun LockedPage(lock: AppLock, s: ChatStrings, onExit: () -> Unit) {
    val ask = {
        lock.authenticate(s.lockTitle, s.lockBody) { ok -> if (ok) ChatLock.unlocked.value = true }
    }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar("", onExit)
        Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                Text("🔒", fontSize = 40.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(s.lockTitle, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(s.lockBody, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Box(Modifier.width(220.dp)) { WhiteButton(s.unlock) { ask() } }
        }
    }
}
