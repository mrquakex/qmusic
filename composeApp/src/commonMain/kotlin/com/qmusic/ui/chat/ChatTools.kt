package com.qmusic.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.rememberShare
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Offers [text] as a .txt file named [fileName] to the share sheet. */
@Composable
expect fun rememberTextFileShare(): (fileName: String, text: String) -> Unit

// ------------------------------------------------------------------ locked chats

/** The locked conversations: shown only after the phone's lock says it is the person. */
@Composable
internal fun LockedFolderPage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val lock = rememberAppLock()
    val locked by controller.lockedThreads.collectAsState()
    var open by remember { mutableStateOf(false) }
    val now = rememberNow()
    val ask: () -> Unit = { if (lock != null && lock.available) lock.authenticate(s.lockedChats, s.lockBody) { ok -> if (ok) open = true } }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(s.lockedChats, { controller.pop() })
        when {
            lock == null || !lock.available -> Text(s.lockedNeedsLock, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            !open -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.lockedChatsNote, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.width(240.dp)) { WhiteButton(s.unlockToSee) { ask() } }
            }
            locked.isEmpty() -> Text(s.lockedEmpty, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Text(s.lockedChatsNote, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                items(locked, key = { it.id }) { t ->
                    ThreadRow(
                        t, me, s,
                        onAvatar = { if (t.isGroup) controller.openGroupInfo(t.other) else controller.openProfile(t.other) },
                        actions = listOf(s.unlockChat to { controller.setLocked(t, false) }),
                        now = now,
                    ) { controller.openConversation(t.other) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ group invites

/** What an invite link opens: the group's picture and name, and Join. */
@Composable
internal fun JoinGroupPage(controller: ChatController, s: ChatStrings, code: String) {
    var invite by remember(code) { mutableStateOf<GroupInvite?>(null) }
    var loaded by remember(code) { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        invite = controller.groupInvite(code)
        loaded = true
    }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(s.joinGroupTitle, { controller.pop() })
        val g = invite
        when {
            !loaded -> Centered { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            g == null -> Text(s.inviteInvalid, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            else -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChatAvatar(ChatUser(g.groupId, g.name, "", g.photo), 112.dp)
                Spacer(Modifier.height(16.dp))
                Text(g.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.width(240.dp)) {
                    WhiteButton(s.joinGroup, busy = joining) {
                        joining = true
                        controller.joinGroup(code)
                    }
                }
            }
        }
    }
}

/** A group's invite link: its code as a picture, share, and (for admins) a new link. */
@Composable
internal fun InviteDialog(controller: ChatController, s: ChatStrings, group: ChatUser, admin: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val share = rememberShare()
    var code by remember(group.uid) { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(group.uid) { code = controller.inviteCode(group) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.inviteLink, color = TextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val c = code
                if (c == null) {
                    CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.padding(24.dp))
                } else {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) { QrCode(qeyfGroupLink(c), Modifier.size(200.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text(qeyfGroupLink(c), color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text(s.inviteLinkNote, color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (admin && c != null) {
                    Text(
                        s.inviteReset,
                        color = Color(0xFFFF5A5F),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { confirmReset = true }.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { code?.let { share(s.inviteShareText + " \"" + group.name + "\": " + qeyfGroupLink(it)) } }, enabled = code != null) {
                Text(s.inviteShare, color = TextPrimary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.closeLabel, color = TextSecondary) } },
    )
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.inviteReset, color = TextPrimary) },
            text = { Text(s.inviteResetNote, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    code = null
                    scope.launch { code = controller.inviteCode(group, renew = true) }
                }) { Text(s.inviteReset, color = Color(0xFFFF5A5F)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

// ------------------------------------------------------------------ broadcast lists

@Composable
internal fun BroadcastsPage(controller: ChatController, s: ChatStrings) {
    val lists by controller.broadcasts.collectAsState()
    Box(Modifier.fillMaxSize().background(BgRoot)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { TopBar(s.broadcastsTitle, { controller.pop() }) }
            item { Text(s.broadcastsNote, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            items(lists, key = { it.id }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { controller.push(ChatRoute.BroadcastChat(b.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(50.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(QMusicIcons.Send, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name.ifBlank { b.members.joinToString { it.name.substringBefore(' ') } }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${b.members.size} ${s.broadcastRecipients}", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        HomeFab(QMusicIcons.Add, s.broadcastNew, Modifier.align(Alignment.BottomEnd)) { controller.push(ChatRoute.BroadcastEdit(null)) }
    }
}

/** A list's name and who is on it (friends). */
@Composable
internal fun BroadcastEditPage(controller: ChatController, s: ChatStrings, id: String?) {
    val lists by controller.broadcasts.collectAsState()
    val friends by controller.friends.collectAsState()
    val existing = lists.firstOrNull { it.id == id }
    var name by remember(id) { mutableStateOf(existing?.name.orEmpty()) }
    var picked by remember(id) { mutableStateOf(existing?.members?.map { it.uid }?.toSet().orEmpty()) }
    val people = remember(friends) { friends.filter { it.uid != CLAUDE_BOT_UID }.sortedBy { it.name.lowercase() } }
    Box(Modifier.fillMaxSize().background(BgRoot).imePadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item { TopBar(if (id == null) s.broadcastNew else s.broadcastEdit, { controller.pop() }) }
            item { Field(name, { name = it.take(50) }, s.broadcastName, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            item { SectionTitle(s.broadcastPick + if (picked.isNotEmpty()) " (${picked.size})" else "") }
            if (people.isEmpty()) item { Text(s.noFriends, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(24.dp)) }
            items(people, key = { it.uid }) { user ->
                val on = user.uid in picked
                Row(
                    Modifier.fillMaxWidth().clickable { picked = if (on) picked - user.uid else picked + user.uid }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatAvatar(user, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(user.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Ink else DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                        if (on) Icon(QMusicIcons.Check, contentDescription = null, tint = InkInverse, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            WhiteButton(s.save, enabled = picked.isNotEmpty()) {
                controller.saveBroadcast(id, name, people.filter { it.uid in picked })
            }
        }
    }
}

/** A list's own page: what was sent through it, and a box to send one more to everyone on it. */
@Composable
internal fun BroadcastChatPage(controller: ChatController, s: ChatStrings, id: String) {
    val lists by controller.broadcasts.collectAsState()
    val list = lists.firstOrNull { it.id == id }
    var text by remember(id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(list?.log?.size) { list?.log?.size?.takeIf { it > 0 }?.let { state.animateScrollToItem(it - 1) } }
    Column(Modifier.fillMaxSize().background(BgRoot).imePadding()) {
        TopBar(list?.name?.ifBlank { null } ?: s.broadcastsTitle, { controller.pop() }, actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                    DropdownMenuItem(text = { Text(s.broadcastEdit, color = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.BroadcastEdit(id)) })
                    DropdownMenuItem(text = { Text(s.broadcastDelete, color = TextPrimary) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        })
        if (list != null) {
            Text(
                "${list.members.size} ${s.broadcastRecipients}: " + list.members.joinToString { it.name.substringBefore(' ') },
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val log = list?.log.orEmpty()
            if (log.isEmpty()) item { Text(s.broadcastEmpty, color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
            items(log.size) { i ->
                val (body, at) = log[i]
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(Ink).padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(body, color = InkInverse, fontSize = 15.sp)
                        Text(listTime(at, s) + " " + clock(at), color = InkInverse.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(DarkSurfaceVariant).padding(horizontal = 18.dp, vertical = 13.dp)) {
                if (text.isEmpty()) Text(s.message, color = TextMuted, fontSize = 16.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(4000) },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(Ink),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (text.isNotBlank() && list != null) Ink else DarkSurfaceVariant)
                    .clickable(enabled = text.isNotBlank() && list != null) {
                        list?.let { controller.sendBroadcast(it, text) }
                        text = ""
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(QMusicIcons.Send, contentDescription = s.send, tint = if (text.isNotBlank()) InkInverse else TextMuted, modifier = Modifier.size(20.dp)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.broadcastDelete, color = TextPrimary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; controller.deleteBroadcast(id) }) { Text(s.delete, color = Color(0xFFFF5A5F)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

// ------------------------------------------------------------------ privacy choices

internal fun visibilityName(v: Visibility, s: ChatStrings): String = when (v) {
    Visibility.EVERYONE -> s.visEveryone
    Visibility.FRIENDS -> s.visFriends
    Visibility.NOBODY -> s.visNobody
}

/** One privacy setting: its name and its choice; a tap offers the choices. */
@Composable
internal fun VisibilityRow(title: String, note: String?, value: Visibility, choices: List<Visibility>, s: ChatStrings, onChange: (Visibility) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            if (note != null) Text(note, color = TextSecondary, fontSize = 12.sp)
        }
        Text(visibilityName(value, s), color = TextSecondary, fontSize = 14.sp)
    }
    if (open) ChoiceDialog(title, choices.map { v -> visibilityName(v, s) to { onChange(v) } }, s) { open = false }
}
