package com.qmusic.ui.chat

import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The conversation open on screen right now (no notification is shown for it). */
object ChatPresence {
    var openChatId: String? = null
}

/**
 * The messages section: full screen, in the app's own black and white. [shareables] are what can be sent with one tap
 * (the song playing, the video open); [onOpenShare] plays a song or opens a video someone sent.
 */
@Composable
fun ChatApp(
    controller: ChatController,
    s: ChatStrings,
    shareables: () -> List<SharedItem>,
    onOpenShare: (SharedItem) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val account by controller.account.collectAsState()
    val routes by controller.routes.collectAsState()
    val error by controller.error.collectAsState()
    PlatformBackHandler(enabled = true) { if (!controller.pop()) onExit() }
    val askPermissions = rememberCallPermissions()
    val calls = controller.calls
    val onCall: ((ChatUser, Boolean) -> Unit)? = calls?.let { service ->
        { other, video -> askPermissions(video) { granted -> if (granted) service.call(other, video) else controller.reportError(s.callPermission) } }
    }

    Box(modifier.fillMaxSize().background(BgRoot)) {
        when (val a = account) {
            ChatAccount.Loading -> Centered { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
            is ChatAccount.Unavailable -> MessagePage(s, onExit, if (a.reason == "setup") s.setupNeeded else s.unavailable)
            ChatAccount.SignedOut -> SignInPage(controller, s, onExit)
            is ChatAccount.NeedsProfile -> ProfilePage(controller, s, a, onExit)
            is ChatAccount.Ready -> when (val top = routes.lastOrNull()) {
                is ChatRoute.Conversation -> ConversationPage(controller, s, a.me, top.other, shareables, onOpenShare, onCall)
                ChatRoute.NewChat -> NewChatPage(controller, s)
                null -> ThreadListPage(controller, s, a.me, onExit)
            }
        }
        if (error != null) {
            LaunchedEffect(error) {
                kotlinx.coroutines.delay(3_000)
                controller.clearError()
            }
            Text(
                text = s.failed + (error?.takeIf { it.length < 120 }?.let { ": $it" } ?: ""),
                color = Color.Black,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(QMusicIcons.Back, contentDescription = null, tint = Color.White) }
        if (leading != null) {
            leading()
        } else {
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        actions()
    }
}

@Composable
fun ChatAvatar(user: ChatUser, size: androidx.compose.ui.unit.Dp) {
    if (user.uid == CLAUDE_BOT_UID) ClaudeAvatar(size) else ChatAvatar(user.name, user.photo, size)
}

private val ClaudeCoral = Color(0xFFD97757)

/** Claude's picture: its coral colour with a white spark. */
@Composable
fun ClaudeAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(ClaudeCoral), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.56f)) {
            val c = center
            val r = this.size.minDimension / 2
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0 + 22.5)
                val long = if (i % 2 == 0) r else r * 0.72f
                drawLine(
                    color = Color.White,
                    start = c,
                    end = androidx.compose.ui.geometry.Offset(
                        c.x + (kotlin.math.cos(angle) * long).toFloat(),
                        c.y + (kotlin.math.sin(angle) * long).toFloat(),
                    ),
                    strokeWidth = r * 0.24f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun ChatAvatar(name: String, photo: String?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = TextPrimary,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (photo != null) AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun WhiteButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Text(text, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, hint: String, prefix: String? = null, modifier: Modifier = Modifier, capitalize: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix != null) Text(prefix, color = TextMuted, fontSize = 16.sp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = TextMuted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = capitalize),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MessagePage(s: ChatStrings, onExit: () -> Unit, text: String) {
    Column(Modifier.fillMaxSize()) {
        TopBar(s.title, onExit)
        Centered { Text(text, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(32.dp)) }
    }
}

// ------------------------------------------------------------------ signing in

@Composable
private fun SignInPage(controller: ChatController, s: ChatStrings, onExit: () -> Unit) {
    val busy by controller.busy.collectAsState()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("", onExit)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(s.signInTitle, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(s.signInBody, color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Column(Modifier.padding(24.dp)) {
            WhiteButton(s.signInButton, busy = busy) { controller.signIn() }
            Spacer(Modifier.height(14.dp))
            Text(s.privacy, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ProfilePage(controller: ChatController, s: ChatStrings, a: ChatAccount.NeedsProfile, onExit: () -> Unit) {
    val busy by controller.busy.collectAsState()
    val taken by controller.usernameTaken.collectAsState()
    var name by remember(a.uid) { mutableStateOf(a.suggestedName.take(40)) }
    var username by remember(a.uid) {
        mutableStateOf(a.suggestedName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(20))
    }
    val valid = name.isNotBlank() && isValidUsername(username)
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        TopBar(s.profileTitle, onExit)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(name.ifBlank { "?" }, a.photo, 64.dp)
                Spacer(Modifier.width(16.dp))
                Text(s.profileBody, color = TextSecondary, fontSize = 15.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text(s.nameLabel, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            Field(name, { name = it.take(40) }, s.nameLabel)
            Spacer(Modifier.height(18.dp))
            Text(s.usernameLabel, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            Field(
                value = username,
                onValue = { v -> username = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }.take(20) },
                hint = "kullanici_adi",
                prefix = "@",
                capitalize = false,
            )
            Spacer(Modifier.height(8.dp))
            Text(if (taken) s.usernameTaken else s.usernameRules, color = if (taken) Color.White else TextMuted, fontSize = 12.sp)
        }
        Box(Modifier.padding(24.dp)) {
            WhiteButton(s.continueButton, enabled = valid, busy = busy) { controller.saveProfile(name, username) }
        }
    }
}

// ------------------------------------------------------------------ the list

@Composable
private fun ThreadListPage(controller: ChatController, s: ChatStrings, me: ChatUser, onExit: () -> Unit) {
    val threads by controller.threads.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = s.title,
            onBack = onExit,
            actions = {
                IconButton(onClick = { controller.openNewChat() }) { Icon(QMusicIcons.Edit, contentDescription = s.newChat, tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        DropdownMenuItem(text = { Text("@${me.username}", color = TextSecondary) }, onClick = {}, enabled = false)
                        DropdownMenuItem(text = { Text(s.signOut, color = TextPrimary) }, onClick = { menu = false; controller.signOut() })
                        DropdownMenuItem(text = { Text(s.deleteAccount, color = TextPrimary) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
        val list = threads
        when {
            list == null -> Centered { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(QMusicIcons.Chat, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(s.noChats, color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Box(Modifier.width(220.dp)) { WhiteButton(s.newChat) { controller.openNewChat() } }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.id }) { t -> ThreadRow(t, me, s) { controller.openConversation(t.other) } }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.deleteAccount, color = TextPrimary) },
            text = { Text(s.deleteAccountConfirm, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; controller.deleteAccount() }) { Text(s.delete, color = Color.White) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

@Composable
private fun ThreadRow(t: ChatThread, me: ChatUser, s: ChatStrings, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(t.other, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.other.name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = if (t.unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(listTime(t.lastAt, s), color = if (t.unread) TextPrimary else TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = if (t.lastFrom == me.uid) "${s.you}: " else ""
                Text(
                    prefix + t.lastText,
                    color = if (t.unread) TextPrimary else TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (t.unread) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

// ------------------------------------------------------------------ finding people

@Composable
private fun NewChatPage(controller: ChatController, s: ChatStrings) {
    val query by controller.query.collectAsState()
    val found by controller.found.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(s.newChat, { controller.pop() })
        Field(query, controller::setQuery, s.searchPeople, prefix = "@", capitalize = false, modifier = Modifier.padding(horizontal = 16.dp).focusRequester(focus))
        Spacer(Modifier.height(8.dp))
        val list = found
        when {
            list == null -> Unit
            list.isEmpty() -> Text(s.noPeople, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.uid }) { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { controller.openConversation(user) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(user, 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${user.username}", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ a conversation

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationPage(
    controller: ChatController,
    s: ChatStrings,
    me: ChatUser,
    other: ChatUser,
    shareables: () -> List<SharedItem>,
    onOpenShare: (SharedItem) -> Unit,
    onCall: ((ChatUser, Boolean) -> Unit)?,
) {
    val messages by remember(other.uid) { controller.messages(other) }.collectAsState(initial = null)
    val readAt by remember(other.uid) { controller.otherReadAt(other) }.collectAsState(initial = 0L)
    val blocked by controller.blocked.collectAsState()
    val isBlocked = other.uid in blocked
    var text by remember(other.uid) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var replyTo by remember(other.uid) { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<ByteArray?>(null) }
    val isClaude = other.uid == CLAUDE_BOT_UID
    val pickPhoto = rememberImagePicker { picked ->
        if (picked != null) {
            controller.sendImage(other, picked, text, replyTo?.let { ChatReply(it.id, it.from, quoteText(it)) })
            text = ""
            replyTo = null
        }
    }
    val presence by remember(other.uid) { controller.presence(other) }.collectAsState(initial = null)
    val typing by remember(other.uid) { controller.typing(other) }.collectAsState(initial = false)
    val reactions by remember(other.uid) { controller.reactions(other) }.collectAsState(initial = emptyMap())
    val assist by remember(other.uid) { controller.assist(other) }.collectAsState(initial = null)
    DisposableEffect(other.uid) { onDispose { controller.stopTyping(other) } }

    val chatId = controller.chatIdWith(other)
    DisposableEffect(chatId) {
        ChatPresence.openChatId = chatId
        onDispose { if (ChatPresence.openChatId == chatId) ChatPresence.openChatId = null }
    }
    // What arrives while the conversation is open counts as read.
    LaunchedEffect(messages?.lastOrNull()?.id) { if (messages?.lastOrNull()?.from == other.uid) controller.markRead(other) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            title = other.name,
            onBack = { controller.pop() },
            leading = {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    ChatAvatar(other, 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(other.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val seen = presence
                        val status = when {
                            typing -> s.typing
                            seen?.online == true -> s.online
                            seen != null && seen.lastSeen > 0 -> s.lastSeen + " " + seenLabel(seen.lastSeen, s)
                            else -> "@" + other.username
                        }
                        Text(status, color = if (typing || seen?.online == true) TextPrimary else TextSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                }
            },
            actions = {
                if (onCall != null && !isBlocked && !isClaude) {
                    IconButton(onClick = { onCall(other, true) }) { Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = Color.White) }
                    IconButton(onClick = { onCall(other, false) }) { Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = Color.White) }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) s.unblock else s.block, color = TextPrimary) },
                            onClick = {
                                menu = false
                                controller.setBlocked(other, !isBlocked)
                            },
                        )
                    }
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))
        val list = messages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list == null) {
                Centered { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
            } else {
                val rows = remember(list) { withDays(list) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(rows.asReversed(), key = { it.key }) { row ->
                        when (row) {
                            is ListRow.Day -> Text(
                                dayLabel(row.at, s),
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            )
                            is ListRow.Message -> Column { Bubble(
                                m = row.m,
                                mine = row.m.from == me.uid,
                                read = row.m.at <= readAt,
                                s = s,
                                otherName = other.name,
                                reactions = reactions[row.m.id].orEmpty(),
                                myUid = me.uid,
                                onOpenShare = onOpenShare,
                                onDelete = { controller.delete(other, row.m) },
                                onReply = { replyTo = row.m },
                                onReact = { emoji -> controller.react(other, row.m, emoji) },
                                loadImage = { id -> controller.imageBytes(other, id) },
                                onOpenImage = { viewing = it },
                                assisting = assist?.takeIf { it.target == row.m.id }?.phase,
                                inClaudeChat = isClaude,
                            ) }
                        }
                    }
                }
            }
        }
        if (isBlocked) {
            Text(s.blockedNote, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            if (!isClaude && me.uid == CLAUDE_OWNER_UID && callsClaude(text)) {
                Row(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ClaudeCoral.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClaudeAvatar(16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(s.claudeWillAnswer, color = TextPrimary, fontSize = 12.sp)
                }
            }
            replyTo?.let { quoted ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).background(Color.White))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nameOf(quoted.from, me.uid, other.name, s), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(quoteText(quoted), color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { replyTo = null }) { Icon(QMusicIcons.Close, contentDescription = null, tint = TextSecondary) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(onClick = { attach = true }) { Icon(QMusicIcons.Add, contentDescription = null, tint = Color.White) }
                    DropdownMenu(expanded = attach, onDismissRequest = { attach = false }, containerColor = DarkSurfaceVariant) {
                        val options = remember(attach) { if (attach) shareables() else emptyList() }
                        DropdownMenuItem(
                            leadingIcon = { Icon(QMusicIcons.Add, null, tint = Color.White) },
                            text = { Text(s.photo, color = TextPrimary, fontSize = 14.sp) },
                            onClick = {
                                attach = false
                                pickPhoto()
                            },
                        )
                        options.forEach { item ->
                            DropdownMenuItem(
                                leadingIcon = { Icon(if (item.kind == ShareKind.SONG) QMusicIcons.Headphones else QMusicIcons.VideoPlay, null, tint = Color.White) },
                                text = {
                                    Column {
                                        Text(if (item.kind == ShareKind.SONG) s.shareSong else s.shareVideo, color = TextPrimary, fontSize = 14.sp)
                                        Text(item.title, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                onClick = {
                                    attach = false
                                    controller.send(other, "", item)
                                },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    if (text.isEmpty()) Text(s.messageHint, color = TextMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v.take(ChatController.MAX_LENGTH)
                            if (v.isNotBlank()) controller.onTyping(other, Clock.System.now().toEpochMilliseconds()) else controller.stopTyping(other)
                        },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = text.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) Color.White else DarkSurfaceVariant)
                        .clickable(enabled = canSend) {
                            val quoted = replyTo?.let { ChatReply(it.id, it.from, quoteText(it)) }
                            if (controller.send(other, text, replyTo = quoted)) {
                                text = ""
                                replyTo = null
                                controller.stopTyping(other)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QMusicIcons.Send, contentDescription = null, tint = if (canSend) Color.Black else TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    viewing?.let { PhotoViewer(it) { viewing = null } }
}

/** A photo in a message: its blurred preview at once, the photo itself when it has come; a tap opens it full. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun ChatPhoto(image: ChatImage, load: suspend (String) -> ByteArray?, onOpen: (ByteArray) -> Unit, scanning: Boolean = false) {
    var bytes by remember(image.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(image.id) { bytes = load(image.id) }
    val thumb = remember(image.thumb) { runCatching { kotlin.io.encoding.Base64.decode(image.thumb) }.getOrNull() }
    val ratio = if (image.width > 0 && image.height > 0) (image.width.toFloat() / image.height).coerceIn(0.56f, 1.9f) else 1f
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .clickable(enabled = bytes != null) { bytes?.let(onOpen) },
    ) {
        if (thumb != null) {
            AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(16.dp))
        }
        bytes?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (bytes == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).align(Alignment.Center))
        }
        if (scanning) PhotoScan(Modifier.matchParentSize())
    }
}

private val ClaudeBubble = Color(0xFF2B201C)

/** Claude reading a photo: a coral band sweeps down and up over it, inside corner marks. */
@Composable
private fun PhotoScan(modifier: Modifier) {
    val t = rememberInfiniteTransition()
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse))
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(ClaudeCoral.copy(alpha = 0.10f))
        val lineY = size.height * y
        val band = size.height * 0.18f
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Transparent, ClaudeCoral.copy(alpha = 0.35f), Color.Transparent),
                startY = lineY - band,
                endY = lineY + band,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, lineY - band),
            size = androidx.compose.ui.geometry.Size(size.width, band * 2),
        )
        drawLine(ClaudeCoral, androidx.compose.ui.geometry.Offset(0f, lineY), androidx.compose.ui.geometry.Offset(size.width, lineY), strokeWidth = 3f)
        val m = 14f
        val l = size.minDimension * 0.12f
        val w = 4f
        listOf(
            androidx.compose.ui.geometry.Offset(m, m) to (1f to 1f),
            androidx.compose.ui.geometry.Offset(size.width - m, m) to (-1f to 1f),
            androidx.compose.ui.geometry.Offset(m, size.height - m) to (1f to -1f),
            androidx.compose.ui.geometry.Offset(size.width - m, size.height - m) to (-1f to -1f),
        ).forEach { (p, d) ->
            drawLine(Color.White, p, androidx.compose.ui.geometry.Offset(p.x + l * d.first, p.y), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, p, androidx.compose.ui.geometry.Offset(p.x, p.y + l * d.second), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Under the message Claude answers: its spark turning, and what it is doing. */
@Composable
private fun ClaudeWorking(label: String) {
    val t = rememberInfiniteTransition()
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.LinearEasing)))
    val alpha by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.graphicsLayer { rotationZ = turn }) { ClaudeAvatar(18.dp) }
        Spacer(Modifier.width(8.dp))
        Text(label, color = ClaudeCoral.copy(alpha = alpha), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Who wrote a quoted message. */
private fun nameOf(uid: String, myUid: String, otherName: String, s: ChatStrings): String = when (uid) {
    myUid -> s.you
    CLAUDE_BOT_UID -> "Claude"
    else -> otherName
}

/** A photo, full screen; a tap or back closes it. */
@Composable
private fun PhotoViewer(bytes: ByteArray, onClose: () -> Unit) {
    PlatformBackHandler(enabled = true) { onClose() }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(model = bytes, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

private sealed interface ListRow {
    val key: String

    data class Day(val at: Long) : ListRow {
        override val key get() = "day$at"
    }

    data class Message(val m: ChatMessage) : ListRow {
        override val key get() = m.id
    }
}

/** The messages with a date line wherever the day changes. */
private fun withDays(list: List<ChatMessage>): List<ListRow> {
    val out = ArrayList<ListRow>(list.size + 4)
    var day: kotlinx.datetime.LocalDate? = null
    for (m in list) {
        val d = local(m.at).date
        if (d != day) {
            out += ListRow.Day(m.at)
            day = d
        }
        out += ListRow.Message(m)
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    mine: Boolean,
    read: Boolean,
    s: ChatStrings,
    otherName: String,
    reactions: Map<String, String>,
    myUid: String,
    onOpenShare: (SharedItem) -> Unit,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onReact: (String?) -> Unit,
    loadImage: suspend (String) -> ByteArray?,
    onOpenImage: (ByteArray) -> Unit,
    /** Claude is answering this message: looking, thinking or writing. */
    assisting: String? = null,
    inClaudeChat: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val fromClaude = m.from == CLAUDE_BOT_UID
    // Pull a message to the right to answer it.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val swipe = remember { androidx.compose.animation.core.Animatable(0f) }
    val swipeScope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    // Claude at work: the glow pulses.
    val pulse = if (assisting != null) {
        rememberInfiniteTransition().animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(850), RepeatMode.Reverse)).value
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipe.value >= threshold) onReply()
                        swipeScope.launch { swipe.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f)) }
                    },
                    onDragCancel = { swipeScope.launch { swipe.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        val before = swipe.value
                        val next = (before + amount * 0.6f).coerceIn(0f, threshold * 1.4f)
                        if (before < threshold && next >= threshold) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        swipeScope.launch { swipe.snapTo(next) }
                        change.consume()
                    },
                )
            },
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // The answer arrow that appears while pulling.
        if (swipe.value > 4f) {
            Icon(
                QMusicIcons.Back,
                contentDescription = null,
                tint = Color.White.copy(alpha = (swipe.value / threshold).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = 180f },
            )
        }
        Column(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(swipe.value.roundToInt(), 0) }
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) Color.White else if (fromClaude) ClaudeBubble else DarkSurfaceVariant)
                .then(
                    if (assisting != null) {
                        Modifier.border(2.dp, ClaudeCoral.copy(alpha = pulse), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = { m.share?.let(onOpenShare) },
                    onLongClick = { menu = true },
                )
                .padding(if (m.share != null || m.image != null) 4.dp else 0.dp),
        ) {
            val fg = if (mine) Color.Black else TextPrimary
            val fgSoft = if (mine) Color.Black.copy(alpha = 0.55f) else TextSecondary
            m.replyTo?.let { quoted ->
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (mine) Color.Black.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.07f))
                        .padding(end = 10.dp),
                ) {
                    Box(Modifier.width(3.dp).height(40.dp).background(if (mine) Color.Black else Color.White))
                    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(
                            nameOf(quoted.from, myUid, otherName, s),
                            color = if (mine) Color.Black else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(quoted.text, color = fgSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Claude's answers: who (in other conversations) and which model.
            if (fromClaude && !inClaudeChat) {
                Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClaudeAvatar(16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Claude", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (m.model != null) Text("  ·  " + m.model, color = fgSoft, fontSize = 11.sp)
                }
            } else if (!mine && m.model != null) {
                Text(m.model, color = fgSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
            }
            m.image?.let { ChatPhoto(it, loadImage, onOpenImage, scanning = assisting != null) }
            m.share?.let { ShareCard(it, mine, s) }
            if (m.text.isNotEmpty()) {
                Text(
                    text = if (m.streaming) m.text + " ▍" else m.text,
                    color = fg,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = if (!mine && m.model != null) 2.dp else 8.dp),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End).padding(start = 12.dp, end = 10.dp, bottom = 6.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clock(m.at), color = fgSoft, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when {
                            m.pending -> "◷"
                            read -> "✓✓"
                            else -> "✓"
                        },
                        color = if (read && !m.pending) Color.Black else fgSoft,
                        fontSize = 11.sp,
                        fontWeight = if (read) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                REACTIONS.forEach { emoji ->
                    val chosen = reactions[myUid] == emoji
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (chosen) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable {
                                menu = false
                                onReact(if (chosen) null else emoji)
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
            DropdownMenuItem(text = { Text(s.reply, color = TextPrimary) }, onClick = { menu = false; onReply() })
            if (mine) DropdownMenuItem(text = { Text(s.deleteMessage, color = TextPrimary) }, onClick = { menu = false; onDelete() })
        }
    }
    if (assisting != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            ClaudeWorking(
                when (assisting) {
                    "looking" -> s.claudeLooking
                    "writing" -> s.claudeWriting
                    else -> s.claudeThinking
                },
            )
        }
    }
    // Reactions under the message: each emoji once, with how many put it.
    if (reactions.isNotEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant)
                    .clickable { if (reactions[myUid] != null) onReact(null) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val counts = reactions.values.groupingBy { it }.eachCount()
                counts.forEach { (emoji, count) ->
                    Text(emoji, fontSize = 14.sp)
                    if (count > 1) Text(count.toString(), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, end = 4.dp))
                }
            }
        }
    }
}

/** A message as a quote: its text, or what it shared. */
private fun quoteText(m: ChatMessage): String =
    m.text.ifBlank { m.share?.let { (if (it.kind == ShareKind.SONG) "🎵 " else "🎬 ") + it.title }.orEmpty() }

/** "today 14:32", "yesterday 09:10", "08.11 21:05". */
private fun seenLabel(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> s.today.lowercase() + " " + clock(ms)
    1 -> s.yesterday.lowercase() + " " + clock(ms)
    else -> local(ms).let { two(it.dayOfMonth) + "." + two(it.monthNumber) + " " + clock(ms) }
}

@Composable
private fun ShareCard(item: SharedItem, mine: Boolean, s: ChatStrings) {
    val video = item.kind == ShareKind.VIDEO
    Row(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (mine) Color(0xFFEDEDED) else DarkSurface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = if (video) 96.dp else 56.dp, height = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnail != null) AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val fg = if (mine) Color.Black else TextPrimary
            val soft = if (mine) Color.Black.copy(alpha = 0.55f) else TextSecondary
            Text(if (video) s.video else s.song, color = soft, fontSize = 11.sp)
            Text(item.title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ time

private fun local(ms: Long): LocalDateTime = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())

private fun two(n: Int) = n.toString().padStart(2, '0')

private fun clock(ms: Long): String = local(ms).let { "${two(it.hour)}:${two(it.minute)}" }

private fun daysAgo(ms: Long): Int {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return (today.toEpochDays() - local(ms).date.toEpochDays())
}

private fun dayLabel(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> s.today
    1 -> s.yesterday
    else -> local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}.${it.year}" }
}

private fun listTime(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> clock(ms)
    1 -> s.yesterday
    else -> local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}" }
}
