package com.qmusic.ui.chat

import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary

// ------------------------------------------------------------------ picking people

/** People to tick: picture, name, a round check on the right. */
@Composable
private fun PickList(people: List<ChatUser>, picked: Set<String>, onToggle: (ChatUser) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(people, key = { it.uid }) { user ->
            val on = user.uid in picked
            PersonRow(user, onClick = { onToggle(user) }) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (on) Ink else DarkSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(QMusicIcons.Check, contentDescription = null, tint = InkInverse, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

/** A new group: its name, and who is in it (friends and the people of the conversations). */
@Composable
internal fun NewGroupPage(controller: ChatController, s: ChatStrings) {
    val people by controller.groupCandidates.collectAsState()
    val busy by controller.busy.collectAsState()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(s.newGroup, { controller.pop() })
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.Group, contentDescription = null, tint = Ink)
            }
            Spacer(Modifier.width(12.dp))
            Field(name, { name = it.take(50) }, s.groupName)
        }
        // The people picked, as a row of small faces.
        if (picked.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                people.filter { it.uid in picked }.forEach { user ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp).clickable { picked = picked - user.uid }) {
                        ChatAvatar(user, 44.dp)
                        Text(user.name.substringBefore(' '), color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        SectionTitle(s.groupPick + if (picked.isNotEmpty()) " · ${picked.size}" else "")
        if (people.isEmpty()) {
            Text(s.groupNoPeople, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
            Spacer(Modifier.weight(1f))
        } else {
            PickList(people, picked, { u -> picked = if (u.uid in picked) picked - u.uid else picked + u.uid }, Modifier.weight(1f))
        }
        Box(Modifier.navigationBarsPadding().padding(16.dp)) {
            WhiteButton(s.groupCreate, enabled = name.isNotBlank() && picked.isNotEmpty(), busy = busy) {
                controller.createGroup(name, people.filter { it.uid in picked })
            }
        }
    }
}

/** Adding people to a group: the candidates not in it yet. */
@Composable
internal fun AddMembersPage(controller: ChatController, s: ChatStrings, group: ChatUser) {
    val people by controller.groupCandidates.collectAsState()
    val info by remember(group.uid) { controller.group(group) }.collectAsState(initial = null)
    val inGroup = info?.members.orEmpty().map { it.user.uid }.toSet()
    val available = people.filter { it.uid !in inGroup }
    var picked by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize()) {
        TopBar(s.addMembers, { controller.pop() })
        if (available.isEmpty()) {
            Text(s.groupNoPeople, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
            Spacer(Modifier.weight(1f))
        } else {
            PickList(available, picked, { u -> picked = if (u.uid in picked) picked - u.uid else picked + u.uid }, Modifier.weight(1f))
        }
        Box(Modifier.navigationBarsPadding().padding(16.dp)) {
            WhiteButton(s.add + if (picked.isNotEmpty()) " (${picked.size})" else "", enabled = picked.isNotEmpty()) {
                controller.addMembers(group, available.filter { it.uid in picked })
            }
        }
    }
}

/** A group's page: its name (members can change it), its people, adding people, leaving. */
@Composable
internal fun GroupInfoPage(controller: ChatController, s: ChatStrings, me: ChatUser, group: ChatUser) {
    val info by remember(group.uid) { controller.group(group) }.collectAsState(initial = null)
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<ChatUser?>(null) }
    val g = info
    val iAmAdmin = g?.members?.any { it.user.uid == me.uid && it.admin } == true
    val pickPhoto = rememberImagePicker { photos -> photos.firstOrNull()?.let { controller.setGroupPhoto(group, it) } }
    var describing by remember { mutableStateOf(false) }
    val messages by remember(group.uid) { controller.messages(group) }.collectAsState(initial = null)
    var viewingPhotos by remember { mutableStateOf<Pair<List<ChatImage>, Int>?>(null) }
    var videoOpen by remember { mutableStateOf<String?>(null) }
    val openFile = rememberAttachmentOpener()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar("", { controller.pop() }) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(CircleShape).clickable { pickPhoto() }) {
                    ChatAvatar(ChatUser(group.uid, g?.name ?: group.name, "", g?.photo), 112.dp)
                }
                Text(s.changePhoto, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { pickPhoto() }.padding(8.dp))
                Text(g?.name ?: group.name, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${g?.members?.size ?: 0} ${s.membersCount}", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    g?.description?.ifBlank { null } ?: s.descriptionHint,
                    color = if (g?.description.isNullOrBlank()) TextMuted else TextPrimary,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(10.dp)).clickable { describing = true }.padding(8.dp),
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(s.addMembers, primary = true) { controller.openAddMembers(group) }
                    Pill(s.rename, primary = false) { renaming = true }
                }
            }
        }
        item {
            var inviteOpen by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { inviteOpen = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) { QrGlyph(InkInverse) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.inviteLink, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.inviteLinkNote, color = TextSecondary, fontSize = 12.sp)
                }
            }
            if (inviteOpen) InviteDialog(controller, s, group, iAmAdmin) { inviteOpen = false }
        }
        if (iAmAdmin && g != null) {
            item { SectionTitle(s.groupSettings) }
            item {
                SettingSwitch(s.sendAdminsOnly, s.sendAdminsOnlyNote, g.settings.sendAdmins) { controller.setGroupSettings(group, g.settings.copy(sendAdmins = it)) }
                SettingSwitch(s.editAdminsOnly, s.editAdminsOnlyNote, g.settings.editAdmins) { controller.setGroupSettings(group, g.settings.copy(editAdmins = it)) }
            }
        }
        item { SectionTitle(s.members) }
        items(g?.members.orEmpty(), key = { it.user.uid }) { m ->
            PersonRow(m.user, onClick = { if (m.user.uid != me.uid) memberMenu = m.user }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserBadge(m.user.uid, modifier = Modifier.padding(end = if (m.admin && hasBadge(m.user.uid)) 8.dp else 0.dp))
                    if (m.admin) Text(s.admin, color = ClaudeCoral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (!messages.isNullOrEmpty()) {
            item { SectionTitle(s.media) }
            item {
                MediaTabs(
                    messages.orEmpty(),
                    s,
                    loadImage = { i -> controller.imageBytes(group, i) },
                    onOpenPhotos = { list, index -> viewingPhotos = list to index },
                    onOpenVideo = { videoOpen = it },
                    onOpenFile = openFile,
                )
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            Text(
                s.leaveGroup,
                color = Color(0xFFFF6B6B),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clickable { confirmLeave = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
    memberMenu?.let { user ->
        AlertDialog(
            onDismissRequest = { memberMenu = null },
            containerColor = DarkSurfaceVariant,
            title = { Text(user.name, color = TextPrimary) },
            text = {
                Column {
                    Text(s.message, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { memberMenu = null; controller.openConversation(user) }.padding(vertical = 12.dp))
                    Text("@" + user.username, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { memberMenu = null; controller.openProfile(user) }.padding(vertical = 12.dp))
                    if (iAmAdmin) {
                        val isAdmin = g?.members?.any { it.user.uid == user.uid && it.admin } == true
                        Text(
                            if (isAdmin) s.removeAdmin else s.makeAdmin,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable { memberMenu = null; controller.setAdmin(group, user, !isAdmin) }.padding(vertical = 12.dp),
                        )
                        Text(s.removeFromGroup, color = Color(0xFFFF6B6B), fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { memberMenu = null; controller.removeMember(group, user) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { memberMenu = null }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(g?.name ?: group.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.rename, color = TextPrimary) },
            text = { Field(name, { name = it.take(50) }, s.groupName) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; controller.renameGroup(group, name) }) { Text(s.save, color = Ink) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
    if (describing) {
        var text by remember { mutableStateOf(g?.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { describing = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.groupDescription, color = TextPrimary) },
            text = { Field(text, { text = it.take(500) }, s.descriptionHint, capitalize = false) },
            confirmButton = { TextButton(onClick = { describing = false; controller.setGroupDescription(group, text) }) { Text(s.save, color = Ink) } },
            dismissButton = { TextButton(onClick = { describing = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
    viewingPhotos?.let { (list, index) -> PhotoViewer(list, index, load = { i -> controller.imageBytes(group, i) }) { viewingPhotos = null } }
    videoOpen?.let { url -> VideoViewer(url) { videoOpen = null } }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.leaveGroup, color = TextPrimary) },
            text = { Text(s.leaveGroupConfirm, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; controller.leaveGroup(group) }) { Text(s.leave, color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

/** The archived conversations; a long press takes one back to the list. */
@Composable
internal fun ArchivedPage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val list by controller.archived.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(s.archivedTitle, { controller.pop() })
        if (list.isEmpty()) {
            Centered { Text(s.noResults, color = TextSecondary, fontSize = 15.sp) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.id }) { t ->
                    ThreadRow(
                        t, me, s,
                        onAvatar = { if (t.isGroup) controller.openGroupInfo(t.other) else controller.openProfile(t.other) },
                        actions = listOf(s.unarchive to { controller.setArchived(t, false) }),
                    ) { controller.openConversation(t.other) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ messages with files

/** A colour for each member's name in a group (the same person always the same colour). */
internal fun memberColor(uid: String): Color {
    val colors = listOf(0xFFFFB86C, 0xFF8BE9FD, 0xFF50FA7B, 0xFFFF79C6, 0xFFBD93F9, 0xFFF1FA8C, 0xFFFF6E6E, 0xFF6EE7B7)
    return Color(colors[(uid.hashCode() and 0x7fffffff) % colors.size])
}

/** "1:07" from milliseconds. */
internal fun mmss(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** "2,4 MB". */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> ((bytes * 10 / (1024 * 1024)) / 10.0).toString().replace('.', ',') + " MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** A voice message: play/pause, its sound as bars (the part played lit), and how long it is. A tap on the bars seeks. */
@Composable
internal fun VoiceBubble(a: ChatAttachment, mine: Boolean, player: VoicePlayer?, progress: Float?) {
    val playingUrl by (player?.playing ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val played by (player?.progress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }).collectAsState()
    val isThis = playingUrl == a.url
    val fg = if (mine) InkInverse else Ink
    val dim = if (mine) InkInverse.copy(alpha = 0.28f) else Ink.copy(alpha = 0.3f)
    Row(Modifier.width(250.dp).padding(start = 6.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (mine) InkInverse else Ink).clickable(enabled = progress == null && player != null) { player?.toggle(a.url) },
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, color = if (mine) Ink else InkInverse, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                Icon(if (isThis) QMusicIcons.Pause else QMusicIcons.Play, contentDescription = null, tint = if (mine) Ink else InkInverse, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val wave = a.wave.ifEmpty { List(40) { 20 } }
            val shown = if (isThis) played else 0f
            Canvas(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(a.url) {
                    detectTapGestures { pos -> player?.seek(a.url, (pos.x / size.width).coerceIn(0f, 1f)) }
                },
            ) {
                val gap = 2.dp.toPx()
                val barW = ((size.width - gap * (wave.size - 1)) / wave.size).coerceAtLeast(1f)
                wave.forEachIndexed { i, v ->
                    val h = (size.height * (v / 100f)).coerceAtLeast(3.dp.toPx())
                    val x = i * (barW + gap)
                    val lit = (i + 0.5f) / wave.size <= shown
                    drawRoundRect(
                        color = if (lit) fg else dim,
                        topLeft = Offset(x, (size.height - h) / 2),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2, barW / 2),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isThis) mmss((a.durationMs * played).toLong()) else mmss(a.durationMs),
                    color = if (mine) InkInverse.copy(alpha = 0.55f) else TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                if (isThis && player != null) {
                    // 1× → 1.5× → 2×, like the apps people know.
                    val speed by player.speed.collectAsState()
                    Text(
                        (if (speed == 1f) "1" else if (speed == 1.5f) "1.5" else "2") + "×",
                        color = if (mine) Ink else InkInverse,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (mine) InkInverse else Ink).clickable { player.cycleSpeed() }.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** A video in a message: its first frame, a play button and its length; a tap opens it full screen. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
internal fun VideoBubble(a: ChatAttachment, progress: Float?, onOpen: () -> Unit) {
    val thumb = remember(a.thumb) { runCatching { kotlin.io.encoding.Base64.decode(a.thumb) }.getOrNull() }
    val ratio = if (a.width > 0 && a.height > 0) (a.width.toFloat() / a.height).coerceIn(0.56f, 1.8f) else 16f / 9f
    Box(
        Modifier.width(250.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(enabled = progress == null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            } else {
                Icon(QMusicIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (a.durationMs > 0) {
            Text(
                mmss(a.durationMs),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** A file in a message: its kind, name and size; a tap downloads it. */
@Composable
internal fun FileBubble(a: ChatAttachment, mine: Boolean, progress: Float?, s: ChatStrings, onOpen: () -> Unit) {
    Row(
        Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Color(0xFFEDEDED) else DarkSurface)
            .clickable(enabled = progress == null, onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (mine) InkInverse else DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                Icon(QMusicIcons.File, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val fg = if (mine) InkInverse else TextPrimary
            val soft = if (mine) InkInverse.copy(alpha = 0.55f) else TextSecondary
            Text(a.name.ifBlank { "file" }, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val ext = a.name.substringAfterLast('.', "").uppercase().take(5)
            Text(listOf(ext, sizeLabel(a.size)).filter { it.isNotBlank() }.joinToString(" · "), color = soft, fontSize = 12.sp)
        }
        if (progress == null) Icon(QMusicIcons.Download, contentDescription = s.download, tint = if (mine) InkInverse else Ink, modifier = Modifier.size(20.dp))
    }
}

/** A video message full screen; back or the close button leaves. */
@Composable
internal fun VideoViewer(url: String, onClose: () -> Unit) {
    PlatformBackHandler(enabled = true) { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoMessagePlayer(url, Modifier.fillMaxSize())
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(6.dp)) {
            Icon(QMusicIcons.Close, contentDescription = null, tint = Color.White)
        }
    }
}

/** "Ali added Ayşe" and the like, in the middle of the conversation. */
@Composable
internal fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Listening / watching together in this conversation: what plays, and leaving. */
@Composable
internal fun TogetherBanner(state: TogetherState, s: ChatStrings, onOpen: () -> Unit, onLeave: () -> Unit) {
    val song = state.item.kind == ShareKind.SONG
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(ClaudeCoral.copy(alpha = 0.16f)).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(InkInverse)) {
            if (state.item.thumbnail != null) AsyncImage(model = state.item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (song) s.listeningWith else s.watchingWith, color = ClaudeCoral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(state.item.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill(s.leave, primary = false, onClick = onLeave)
    }
}

/** Recording a voice message: a red dot, the time, the loudness, and how to cancel. */
@Composable
internal fun RecordingBar(elapsedMs: Long, level: Float, hint: String, cancelling: Boolean, modifier: Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition()
        .animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse))
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(DarkSurfaceVariant).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF4D4D).copy(alpha = pulse.value)))
        Spacer(Modifier.width(10.dp))
        Text(mmss(elapsedMs), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width((6 + level * 40).dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.copy(alpha = 0.7f)))
        Spacer(Modifier.weight(1f))
        Text(hint, color = if (cancelling) Color(0xFFFF6B6B) else TextMuted, fontSize = 13.sp, maxLines = 1)
    }
}
