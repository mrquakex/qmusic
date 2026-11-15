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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withLink
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

    /** A conversation was opened: its notification goes (set by the app). */
    var onOpened: ((chatId: String) -> Unit)? = null
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
    val voicePlayer = rememberVoicePlayer()
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
                is ChatRoute.Conversation -> ConversationPage(controller, s, a.me, top.other, shareables, onOpenShare, onCall, voicePlayer)
                ChatRoute.NewChat -> NewChatPage(controller, s)
                ChatRoute.NewGroup -> NewGroupPage(controller, s)
                is ChatRoute.GroupInfo -> GroupInfoPage(controller, s, a.me, top.group)
                is ChatRoute.AddMembers -> AddMembersPage(controller, s, top.group)
                ChatRoute.Archived -> ArchivedPage(controller, s, a.me)
                ChatRoute.Starred -> StarredPage(controller, s)
                ChatRoute.Settings -> SettingsPage(controller, s, a.me)
                ChatRoute.Friends -> FriendsPage(controller, s)
                is ChatRoute.Profile -> ProfilePage(controller, s, a.me, top.user, onCall)
                null -> ThreadListPage(controller, s, a.me, onExit)
            }
        }
        val info by controller.info.collectAsState()
        if (info != null && error == null) {
            LaunchedEffect(info) {
                kotlinx.coroutines.delay(1_800)
                controller.clearInfo()
            }
            Text(
                text = info.orEmpty(),
                color = Color.Black,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
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
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, leading: (@Composable () -> Unit)? = null) {
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
    when {
        user.uid == CLAUDE_BOT_UID -> ClaudeAvatar(size)
        user.isGroup && user.photo == null -> Box(Modifier.size(size).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Group, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
        else -> ChatAvatar(user.name, user.photo, size)
    }
}

internal val ClaudeCoral = Color(0xFFD97757)

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
internal fun WhiteButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
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
internal fun Field(value: String, onValue: (String) -> Unit, hint: String, prefix: String? = null, modifier: Modifier = Modifier, capitalize: Boolean = true) {
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
    val all by controller.threads.collectAsState()
    val archived by controller.archived.collectAsState()
    val togetherState by (controller.together?.active ?: remember { kotlinx.coroutines.flow.MutableStateFlow<TogetherState?>(null) }).collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val threads = remember(all, search) {
        val q = search.trim()
        if (q.isEmpty()) all else all?.filter { it.other.name.contains(q, ignoreCase = true) || it.lastText.contains(q, ignoreCase = true) || it.other.username.contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = s.title,
            onBack = onExit,
            actions = {
                val incoming by controller.friendRequests.collectAsState()
                IconButton(onClick = { controller.openFriends() }) {
                    Box {
                        Icon(QMusicIcons.Account, contentDescription = s.friends, tint = Color.White)
                        if (incoming.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(16.dp).clip(CircleShape).background(ClaudeCoral),
                                contentAlignment = Alignment.Center,
                            ) { Text(incoming.size.coerceAtMost(9).toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                IconButton(onClick = { searching = !searching; if (!searching) search = "" }) { Icon(QMusicIcons.Search, contentDescription = s.search, tint = Color.White) }
                IconButton(onClick = { controller.openNewChat() }) { Icon(QMusicIcons.Edit, contentDescription = s.newChat, tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        DropdownMenuItem(text = { Text("@${me.username}", color = TextSecondary) }, onClick = {}, enabled = false)
                        DropdownMenuItem(
                            leadingIcon = { Icon(QMusicIcons.Group, null, tint = Color.White) },
                            text = { Text(s.newGroup, color = TextPrimary) },
                            onClick = { menu = false; controller.openNewGroup() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Text("★", color = Color(0xFFFFB800), fontSize = 18.sp) },
                            text = { Text(s.starredTitle, color = TextPrimary) },
                            onClick = { menu = false; controller.openStarred() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(QMusicIcons.Account, null, tint = Color.White) },
                            text = { Text(s.settings, color = TextPrimary) },
                            onClick = { menu = false; controller.openSettings() },
                        )
                        DropdownMenuItem(text = { Text(s.signOut, color = TextPrimary) }, onClick = { menu = false; controller.signOut() })
                        DropdownMenuItem(text = { Text(s.deleteAccount, color = TextPrimary) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            PlatformBackHandler(enabled = true) { searching = false; search = "" }
            Field(search, { search = it }, s.searchChats, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus))
        }
        ReadinessCard(s)
        togetherState?.let { state ->
            val thread = all?.firstOrNull { it.id == state.chatId }
            TogetherBanner(state, s, onOpen = { thread?.let { controller.openConversation(it.other) } }, onLeave = { controller.together?.leave() })
        }
        val list = threads
        // "typing…" in the list fades by itself: the clock moves on every couple of seconds.
        var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                now = Clock.System.now().toEpochMilliseconds()
            }
        }
        var muteFor by remember { mutableStateOf<ChatThread?>(null) }
        muteFor?.let { t ->
            ChoiceDialog(
                s.mute,
                listOf(
                    s.mute8h to { controller.setMuted(t.other, now + 8 * 3600_000L) },
                    s.mute1w to { controller.setMuted(t.other, now + 7 * 24 * 3600_000L) },
                    s.muteAlways to { controller.setMuted(t.other, -1L) },
                ),
                s,
            ) { muteFor = null }
        }
        when {
            list == null -> Centered { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
            list.isEmpty() && search.isNotBlank() -> Centered { Text(s.noResults, color = TextSecondary, fontSize = 15.sp) }
            list.isEmpty() && archived.isEmpty() -> Column(
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
                if (archived.isNotEmpty() && search.isBlank()) {
                    item(key = "archived") {
                        Row(
                            Modifier.fillMaxWidth().clickable { controller.openArchived() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(QMusicIcons.Archive, null, tint = TextSecondary) }
                            Spacer(Modifier.width(14.dp))
                            Text(s.archivedTitle, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            val unread = archived.count { it.unread }
                            Text(if (unread > 0) unread.toString() else archived.size.toString(), color = if (unread > 0) TextPrimary else TextMuted, fontSize = 13.sp)
                        }
                    }
                }
                items(list, key = { it.id }) { t ->
                    ThreadRow(
                        t, me, s,
                        onAvatar = { if (t.isGroup) controller.openGroupInfo(t.other) else controller.openProfile(t.other) },
                        actions = listOf(
                            (if (t.pinned > 0) s.unpin else s.pin) to { controller.setPinned(t, t.pinned == 0L) },
                            (if (t.muted(now)) s.unmute else s.mute) to { if (t.muted(now)) controller.setMuted(t.other, 0L) else muteFor = t },
                            s.archive to { controller.setArchived(t, true) },
                        ),
                        now = now,
                    ) { controller.openConversation(t.other) }
                }
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

/** What on this phone keeps calls and messages from arriving, each a tap away from its settings page. */
@Composable
private fun ReadinessCard(s: ChatStrings) {
    val readiness = rememberReadiness()
    if (readiness.issues.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant)
            .padding(14.dp),
    ) {
        Text(s.readinessTitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            readiness.issues.forEach { issue ->
                Text(
                    when (issue) {
                        ReadinessIssue.NOTIFICATIONS -> s.readinessNotifications
                        ReadinessIssue.FULL_SCREEN -> s.readinessFullScreen
                        ReadinessIssue.BATTERY -> s.readinessBattery
                    },
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable { readiness.fix(issue) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ThreadRow(
    t: ChatThread,
    me: ChatUser,
    s: ChatStrings,
    onAvatar: () -> Unit,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    now: Long = 0L,
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val typingNow = now > 0 && now - t.typingAt < 8_000
    Box {
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
        actions.forEach { (label, run) -> DropdownMenuItem(text = { Text(label, color = TextPrimary) }, onClick = { menu = false; run() }) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (actions.isNotEmpty()) menu = true }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).clickable(onClick = onAvatar)) { ChatAvatar(t.other, 52.dp) }
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
                if (t.muted(if (now > 0) now else Clock.System.now().toEpochMilliseconds())) Icon(QMusicIcons.VolumeMute, contentDescription = null, tint = TextMuted, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                if (t.pinned > 0) Icon(QMusicIcons.Pin, contentDescription = null, tint = TextMuted, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                Text(listTime(t.lastAt, s), color = if (t.unread) TickRead else TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = when {
                    t.lastFrom == me.uid -> "${s.you}: "
                    t.isGroup && t.lastFromName.isNotEmpty() && !t.lastText.startsWith("Claude: ") -> "${t.lastFromName}: "
                    else -> ""
                }
                if (typingNow) {
                    Text(
                        (if (t.isGroup && t.typingName.isNotEmpty()) t.typingName + " " else "") + s.typing,
                        color = TickRead,
                        fontSize = 14.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        prefix + t.lastText,
                        color = if (t.unread) TextPrimary else TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (t.unread) {
                    val n = t.unreadCount.coerceAtLeast(1)
                    Box(
                        Modifier.padding(start = 8.dp).height(20.dp).widthIn(min = 20.dp).clip(CircleShape)
                            .background(if (t.muted(if (now > 0) now else Clock.System.now().toEpochMilliseconds())) TextMuted else TickRead).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
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
                    PersonRow(user, onClick = { controller.openProfile(user) }) { FriendButton(controller, user, s) }
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
    voicePlayer: VoicePlayer?,
) {
    val isGroup = other.isGroup
    // The last messages; scrolling up to the oldest shown asks for more.
    val limit = remember(other.uid) { kotlinx.coroutines.flow.MutableStateFlow(PAGE) }
    val messages by remember(other.uid) { controller.messages(other, limit) }.collectAsState(initial = null)
    val readAt by remember(other.uid) { controller.otherReadAt(other) }.collectAsState(initial = 0L)
    val deliveredAt by remember(other.uid) { controller.otherDeliveredAt(other) }.collectAsState(initial = 0L)
    val readMarks by remember(other.uid) { controller.readMarks(other) }.collectAsState(initial = emptyMap())
    val deliveredMarks by remember(other.uid) { controller.deliveredMarks(other) }.collectAsState(initial = emptyMap())
    val starredList by controller.starred.collectAsState()
    val ttl by remember(other.uid) { controller.ttl(other) }.collectAsState(initial = 0L)
    // Where the person had read up to when they opened it: the "unread" line goes there and stays.
    val openedReadAt = remember(other.uid) { controller.threadOf(other)?.takeIf { it.unreadCount > 0 }?.readAt ?: Long.MAX_VALUE }
    val threadsNow by controller.threads.collectAsState()
    val archivedNow by controller.archived.collectAsState()
    val thread = remember(threadsNow, archivedNow, other.uid) {
        val id = controller.chatIdWith(other)
        threadsNow?.firstOrNull { it.id == id } ?: archivedNow.firstOrNull { it.id == id }
    }
    var editing by remember(other.uid) { mutableStateOf<ChatMessage?>(null) }
    var selected by remember(other.uid) { mutableStateOf(setOf<String>()) }
    var emojiOpen by remember { mutableStateOf(false) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var forwarding by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var deleting by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var muteDialog by remember { mutableStateOf(false) }
    var ttlDialog by remember { mutableStateOf(false) }
    var pendingPhotos by remember { mutableStateOf<List<PickedImage>?>(null) }
    var locked by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val groupCalls = controller.groupCalls
    val callPeople by remember(other.uid) { if (other.isGroup) controller.groupCallParticipants(other) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val group by remember(other.uid) { if (isGroup) controller.group(other) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(initial = null)
    val memberNames = remember(group) { group?.members?.associate { it.user.uid to it.user.name }.orEmpty() }
    val title = group?.name ?: other.name
    val blocked by controller.blocked.collectAsState()
    val isBlocked = !isGroup && other.uid in blocked
    var text by remember(other.uid) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var replyTo by remember(other.uid) { mutableStateOf<ChatMessage?>(null) }
    // Answering a message (pulled to the right, or from its menu) puts the cursor in the box and opens the keyboard.
    val inputFocus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(replyTo?.id) {
        if (replyTo != null) {
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var viewing by remember { mutableStateOf<Pair<List<ChatImage>, Int>?>(null) }
    var videoOpen by remember { mutableStateOf<String?>(null) }
    val claudeState by controller.claudeState.collectAsState()
    var modelMenu by remember { mutableStateOf(false) }
    val isClaude = other.uid == CLAUDE_BOT_UID
    val quoted: () -> ChatReply? = { replyTo?.let { ChatReply(it.id, it.from, quoteText(it)) } }
    // Picked photos are shown first (caption, edits), then sent.
    val sendPhotos: (List<PickedImage>) -> Unit = { photos -> if (photos.isNotEmpty()) pendingPhotos = photos }
    val sendPicked: (PickedFile?) -> Unit = { file ->
        if (file != null) {
            controller.sendFile(other, file, text, quoted())
            text = ""
            replyTo = null
        }
    }
    val pickPhoto = rememberImagePicker { sendPhotos(it) }
    val takePhoto = rememberCamera { photo -> photo?.let { sendPhotos(listOf(it)) } }
    val pickVideo = rememberFilePicker(video = true, onPicked = sendPicked)
    val pickFile = rememberFilePicker(video = false, onPicked = sendPicked)
    val openFile = rememberAttachmentOpener()

    // Voice messages: hold the microphone to record, let go to send, slide left to throw it away.
    val recorder = rememberVoiceRecorder()
    val askMic = rememberCallPermissions()
    var micReady by remember { mutableStateOf(false) }
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    val level by (recorder?.level ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }).collectAsState()
    LaunchedEffect(recordingSince) {
        val since = recordingSince ?: return@LaunchedEffect
        while (true) {
            elapsed = Clock.System.now().toEpochMilliseconds() - since
            kotlinx.coroutines.delay(200)
        }
    }

    val togetherState by (controller.together?.active ?: remember { kotlinx.coroutines.flow.MutableStateFlow<TogetherState?>(null) }).collectAsState()
    var searching by remember(other.uid) { mutableStateOf(false) }
    var query by remember(other.uid) { mutableStateOf("") }
    var matchIndex by remember(other.uid) { mutableStateOf(0) }

    val presence by remember(other.uid) { controller.presence(other) }.collectAsState(initial = null)
    val typing by remember(other.uid) { controller.typing(other) }.collectAsState(initial = false)
    val reactions by remember(other.uid) { controller.reactions(other) }.collectAsState(initial = emptyMap())
    val assist by remember(other.uid) { controller.assist(other) }.collectAsState(initial = null)
    DisposableEffect(other.uid) {
        onDispose {
            controller.stopTyping(other)
            voicePlayer?.stop()
            recorder?.cancel()
        }
    }

    val chatId = controller.chatIdWith(other)
    DisposableEffect(chatId) {
        ChatPresence.openChatId = chatId
        chatId?.let { ChatPresence.onOpened?.invoke(it) }
        onDispose { if (ChatPresence.openChatId == chatId) ChatPresence.openChatId = null }
    }
    // What arrives while the conversation is open counts as read.
    LaunchedEffect(messages?.lastOrNull()?.id) {
        val last = messages?.lastOrNull()
        if (last != null && last.from != me.uid) controller.markRead(other)
    }

    // The rows newest first (the list is drawn from the bottom), and the messages the search finds, newest first.
    val rows = remember(messages) { messages?.let { withDays(it, openedReadAt, me.uid).asReversed() } }
    val selectedMessages = remember(messages, selected) { messages.orEmpty().filter { it.id in selected } }
    val starredIds = remember(starredList, chatId) { starredList.filter { it.chatId == chatId }.map { it.messageId }.toSet() }
    // In a group a message is read when every other member read it (delivered likewise).
    val others = remember(group, me.uid) { group?.members?.map { it.user.uid }?.filter { it != me.uid && it != CLAUDE_BOT_UID }.orEmpty() }
    fun readByAll(at: Long) = others.isNotEmpty() && others.all { (readMarks[it] ?: 0L) >= at }
    fun deliveredToAll(at: Long) = others.isNotEmpty() && others.all { maxOf(readMarks[it] ?: 0L, deliveredMarks[it] ?: 0L) >= at }
    // A tapped quote: the list goes to the message answered (fetching older ones when needed).
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jumpTo, rows) {
        val target = jumpTo ?: return@LaunchedEffect
        val index = rows?.indexOfFirst { it is ListRow.Message && it.m.id == target } ?: -1
        if (index >= 0) {
            listState.animateScrollToItem(index)
            jumpTo = null
            flash = target
            kotlinx.coroutines.delay(1600)
            flash = null
        } else if ((messages?.size ?: 0) >= limit.value && limit.value < 5_000) {
            limit.value += PAGE
        } else {
            jumpTo = null
        }
    }
    // Near the oldest message shown: more come.
    LaunchedEffect(listState, messages?.size) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val total = rows?.size ?: 0
            if (total > 0 && last >= total - 6 && (messages?.size ?: 0) >= limit.value) limit.value += PAGE
        }
    }
    val mineSelected = selectedMessages.isNotEmpty() && selectedMessages.all { it.from == me.uid }
    val matches = remember(messages, query) {
        val q = query.trim()
        if (q.length < 2) emptyList() else messages.orEmpty().filter { !it.system && it.text.contains(q, ignoreCase = true) }.asReversed()
    }
    val current = matches.getOrNull(matchIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0)))
    LaunchedEffect(current?.id) {
        val target = current ?: return@LaunchedEffect
        val index = rows?.indexOfFirst { it is ListRow.Message && it.m.id == target.id } ?: -1
        if (index >= 0) listState.animateScrollToItem(index)
    }

    if (selected.isNotEmpty()) PlatformBackHandler(enabled = true) { selected = emptySet() }
    if (emojiOpen) PlatformBackHandler(enabled = true) { emojiOpen = false }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(QMusicIcons.Close, null, tint = Color.White) }
                Text("${selected.size} ${s.selected}", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selectedMessages.any { it.text.isNotEmpty() }) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(selectedMessages.sortedBy { it.at }.filter { it.text.isNotEmpty() }.joinToString("\n") { it.text }))
                        controller.reportInfo(s.copied)
                        selected = emptySet()
                    }) { Text(s.copy, color = Color.White) }
                }
                IconButton(onClick = {
                    val chatName = title
                    val allStarred = selectedMessages.all { it.id in starredIds }
                    selectedMessages.forEach { m -> controller.setStarred(other, m, if (m.from == me.uid) me.name else memberNames[m.from] ?: other.name, chatName, !allStarred) }
                    selected = emptySet()
                }) { Text("★", color = Color(0xFFFFB800), fontSize = 20.sp) }
                TextButton(onClick = { forwarding = selectedMessages.filter { !it.deleted } }) { Text(s.forward, color = Color.White) }
                IconButton(onClick = { deleting = selectedMessages }) { Icon(QMusicIcons.Trash, null, tint = Color.White) }
            }
        } else
        TopBar(
            title = title,
            onBack = { controller.pop() },
            leading = {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { if (isGroup) controller.openGroupInfo(other) else controller.openProfile(other) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatAvatar(other.copy(name = title), 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val seen = presence
                        val status = when {
                            typing -> s.typing
                            isGroup -> group?.members?.joinToString(", ") { if (it.user.uid == me.uid) s.you else it.user.name.substringBefore(' ') }.orEmpty()
                            seen?.online == true -> s.online
                            seen != null && seen.lastSeen > 0 -> s.lastSeen + " " + seenLabel(seen.lastSeen, s)
                            else -> "@" + other.username
                        }
                        if (isClaude && claudeState != null && !typing) {
                            // The model answering now; a tap picks another.
                            Box {
                                Row(
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { modelMenu = true }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(claudeState!!.label, color = ClaudeCoral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Icon(QMusicIcons.ChevronDown, contentDescription = null, tint = ClaudeCoral, modifier = Modifier.size(14.dp))
                                }
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, containerColor = DarkSurfaceVariant) {
                                    CLAUDE_MODELS.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        "Claude " + model.replaceFirstChar { it.uppercase() },
                                                        color = if (claudeState?.model == model) ClaudeCoral else TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Text(modelBlurb(model, s), color = TextSecondary, fontSize = 12.sp)
                                                }
                                            },
                                            onClick = {
                                                modelMenu = false
                                                controller.setClaudeModel(model)
                                            },
                                        )
                                    }
                                }
                            }
                        } else if (status.isNotEmpty()) {
                            Text(status, color = if (typing || seen?.online == true) TextPrimary else TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            actions = {
                if (onCall != null && !isBlocked && !isClaude && !isGroup) {
                    IconButton(onClick = { onCall(other, true) }) { Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = Color.White) }
                    IconButton(onClick = { onCall(other, false) }) { Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = Color.White) }
                }
                if (isGroup && groupCalls != null) {
                    val startGroupCall: (Boolean) -> Unit = { video ->
                        if (callPeople.size >= MAX_GROUP_CALL) {
                            controller.reportError(s.groupCallFull)
                        } else {
                            askMic(video) { granted -> if (granted) groupCalls.join(other.copy(name = title), video) else controller.reportError(s.callPermission) }
                        }
                    }
                    IconButton(onClick = { startGroupCall(true) }) { Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = Color.White) }
                    IconButton(onClick = { startGroupCall(false) }) { Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = Color.White) }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        if (isGroup) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Group, null, tint = Color.White) },
                                text = { Text(s.groupInfo, color = TextPrimary) },
                                onClick = { menu = false; controller.openGroupInfo(other) },
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = { Icon(QMusicIcons.Search, null, tint = Color.White) },
                            text = { Text(s.searchMessages, color = TextPrimary) },
                            onClick = { menu = false; searching = true },
                        )
                        val muted = thread?.muted(Clock.System.now().toEpochMilliseconds()) == true
                        DropdownMenuItem(
                            leadingIcon = { Icon(if (muted) QMusicIcons.Bell else QMusicIcons.VolumeMute, null, tint = Color.White) },
                            text = { Text(if (muted) s.unmute else s.mute, color = TextPrimary) },
                            onClick = { menu = false; if (muted) controller.setMuted(other, 0L) else muteDialog = true },
                        )
                        if (!isClaude) {
                            DropdownMenuItem(
                                leadingIcon = { Text("⏱", fontSize = 18.sp) },
                                text = { Text(s.disappearing + if (ttl > 0) " · " + ttlLabel(ttl, s) else "", color = TextPrimary) },
                                onClick = { menu = false; ttlDialog = true },
                            )
                        }
                        if (!isClaude) {
                            val options = remember(menu) { if (menu) shareables() else emptyList() }
                            options.forEach { item ->
                                val song = item.kind == ShareKind.SONG
                                DropdownMenuItem(
                                    leadingIcon = { Icon(if (song) QMusicIcons.Headphones else QMusicIcons.VideoPlay, null, tint = ClaudeCoral) },
                                    text = {
                                        Column {
                                            Text(if (song) s.listenTogether else s.watchTogether, color = TextPrimary, fontSize = 14.sp)
                                            Text(item.title, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    onClick = {
                                        menu = false
                                        controller.startTogether(other, item, if (song) s.togetherInviteSong else s.togetherInviteVideo)
                                    },
                                )
                            }
                        }
                        if (!isGroup) {
                            DropdownMenuItem(
                                text = { Text(if (isBlocked) s.unblock else s.block, color = TextPrimary) },
                                onClick = {
                                    menu = false
                                    controller.setBlocked(other, !isBlocked)
                                },
                            )
                        }
                    }
                }
            },
        )
        if (searching) {
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            PlatformBackHandler(enabled = true) { searching = false; query = "" }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(query, { query = it; matchIndex = 0 }, s.searchMessages, modifier = Modifier.weight(1f).focusRequester(searchFocus))
                Text(
                    if (query.trim().length < 2) "" else if (matches.isEmpty()) s.noResults else "${matchIndex + 1}/${matches.size}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(enabled = matchIndex < matches.size - 1, onClick = { matchIndex++ }) { Icon(QMusicIcons.ChevronUp, null, tint = Color.White) }
                IconButton(enabled = matchIndex > 0, onClick = { matchIndex-- }) { Icon(QMusicIcons.ChevronDown, null, tint = Color.White) }
                IconButton(onClick = { searching = false; query = "" }) { Icon(QMusicIcons.Close, null, tint = TextSecondary) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))
        togetherState?.takeIf { it.chatId == chatId }?.let { state ->
            TogetherBanner(state, s, onOpen = { onOpenShare(state.item) }, onLeave = { controller.together?.leave() })
        }
        val inThisCall = groupCalls?.state?.collectAsState()?.value?.group?.uid == other.uid
        if (isGroup && groupCalls != null && callPeople.isNotEmpty() && !inThisCall) {
            GroupCallBanner(callPeople, s) {
                if (callPeople.size >= MAX_GROUP_CALL) controller.reportError(s.groupCallFull)
                else askMic(true) { granted -> if (granted) groupCalls.join(other.copy(name = title), true) else controller.reportError(s.callPermission) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = messages
            val shown = rows
            if (list == null || shown == null) {
                Centered { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
            } else {
                // A new message: ours always comes into view; theirs only when we are at the bottom already (reading
                // further up, the place is kept).
                val newest = list.lastOrNull()
                var newBelow by remember { mutableStateOf(0) }
                LaunchedEffect(newest?.id) {
                    if (newest != null && (newest.from == me.uid || listState.firstVisibleItemIndex <= 1)) {
                        listState.animateScrollToItem(0)
                        newBelow = 0
                    } else if (newest != null) {
                        newBelow++
                    }
                }
                // Opened with unread messages: the list starts at the unread line, not at the bottom.
                LaunchedEffect(shown.isNotEmpty()) {
                    val line = shown.indexOfFirst { it is ListRow.Unread }
                    if (line > 3) listState.scrollToItem(line, -200)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(shown, key = { it.key }) { row ->
                        when (row) {
                            is ListRow.Day -> Text(
                                dayLabel(row.at, s),
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            )
                            is ListRow.Unread -> Text(
                                "${row.count} ${s.unreadMessages}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(DarkSurfaceVariant).padding(vertical = 6.dp),
                            )
                            is ListRow.Message -> Column {
                                val mine = row.m.from == me.uid
                                val m = row.m
                                Bubble(
                                    m = row.m,
                                    mine = mine,
                                    read = if (isGroup) readByAll(row.m.at) else row.m.at <= readAt,
                                    delivered = if (isGroup) deliveredToAll(row.m.at) else row.m.at <= maxOf(deliveredAt, readAt),
                                    starred = m.id in starredIds,
                                    selecting = selected.isNotEmpty(),
                                    selected = m.id in selected,
                                    onToggleSelect = { selected = if (m.id in selected) selected - m.id else selected + m.id },
                                    onCopy = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                                        controller.reportInfo(s.copied)
                                    },
                                    onForward = { forwarding = listOf(m) },
                                    onEdit = if (mine && !m.deleted && m.attachment == null && m.images.isEmpty() && m.share == null && m.text.isNotEmpty() && !m.pending) {
                                        { editing = m; text = m.text; replyTo = null }
                                    } else null,
                                    onStar = { controller.setStarred(other, m, if (mine) me.name else memberNames[m.from] ?: other.name, title, m.id !in starredIds) },
                                    onInfo = if (mine && !m.deleted) ({ infoFor = m }) else null,
                                    onQuoteTap = { id -> jumpTo = id },
                                    loadPreview = { url -> controller.linkPreview(url) },
                                    s = s,
                                    otherName = title,
                                    names = memberNames,
                                    senderName = if (isGroup && !mine) (memberNames[row.m.from] ?: if (row.m.from == CLAUDE_BOT_UID) "Claude" else "?") else null,
                                    reactions = reactions[row.m.id].orEmpty(),
                                    myUid = me.uid,
                                    onOpenShare = onOpenShare,
                                    onDelete = { deleting = listOf(row.m) },
                                    onReply = { replyTo = row.m },
                                    onReact = { emoji -> controller.react(other, row.m, emoji) },
                                    loadImage = { image -> controller.imageBytes(other, image) },
                                    onOpenImages = { images, index -> viewing = images to index },
                                    assisting = assist?.takeIf { it.target == row.m.id }?.phase,
                                    inClaudeChat = isClaude,
                                    voicePlayer = voicePlayer,
                                    onOpenVideo = { url -> videoOpen = url },
                                    onOpenFile = openFile,
                                    onJoin = { controller.joinTogether(other) },
                                    highlight = current?.id == row.m.id || flash == row.m.id,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (isBlocked) {
            Text(s.blockedNote, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            editing?.let { e ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(36.dp).background(ClaudeCoral))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.editing, color = ClaudeCoral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(e.text, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editing = null; text = "" }) { Icon(QMusicIcons.Close, contentDescription = null, tint = TextSecondary) }
                }
            }
            if (isClaude && text.startsWith("/")) {
                SlashCommands(
                    query = text,
                    current = claudeState?.model,
                    s = s,
                    onNew = {
                        text = ""
                        controller.resetClaude()
                    },
                    onModel = { model ->
                        text = ""
                        controller.setClaudeModel(model)
                    },
                )
            }
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
            replyTo?.let { q ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).background(Color.White))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (q.from == me.uid) s.you else memberNames[q.from] ?: nameOf(q.from, me.uid, title, s), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(quoteText(q), color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { replyTo = null }) { Icon(QMusicIcons.Close, contentDescription = null, tint = TextSecondary) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (recordingSince != null) {
                    if (locked) {
                        IconButton(onClick = {
                            recorder?.cancel()
                            recordingSince = null
                            locked = false
                        }) { Icon(QMusicIcons.Trash, contentDescription = null, tint = Color(0xFFFF6B6B)) }
                    }
                    RecordingBar(elapsed, level, if (locked) s.copy(voiceSlideCancel = s.voiceLocked) else s.copy(voiceSlideCancel = s.voiceSlideCancel + "   " + s.voiceSlideLock), cancelling, Modifier.weight(1f))
                } else {
                    Box {
                        IconButton(onClick = { attach = true }) { Icon(QMusicIcons.Add, contentDescription = null, tint = Color.White) }
                        DropdownMenu(expanded = attach, onDismissRequest = { attach = false }, containerColor = DarkSurfaceVariant) {
                            val options = remember(attach) { if (attach) shareables() else emptyList() }
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Add, null, tint = Color.White) },
                                text = { Text(s.photo, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickPhoto() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Camera, null, tint = Color.White) },
                                text = { Text(s.camera, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; takePhoto() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.VideoPlay, null, tint = Color.White) },
                                text = { Text(s.videoAttach, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickVideo() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.File, null, tint = Color.White) },
                                text = { Text(s.fileAttach, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickFile() },
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
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(DarkSurfaceVariant)
                            .padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable {
                                emojiOpen = !emojiOpen
                                if (emojiOpen) keyboard?.hide() else { runCatching { inputFocus.requestFocus() }; keyboard?.show() }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(if (emojiOpen) "⌨" else "😊", fontSize = 20.sp, color = TextSecondary) }
                    Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
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
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onFocusChanged { if (it.isFocused) emojiOpen = false },
                        )
                    }
                    }
                }
                Spacer(Modifier.width(6.dp))
                val canSend = text.isNotBlank()
                if (locked && recordingSince != null) {
                    // Locked: the button sends.
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable {
                            recordingSince = null
                            locked = false
                            recorder?.stop()?.let { voice ->
                                controller.sendFile(other, voice, "", quoted())
                                replyTo = null
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(QMusicIcons.Send, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                } else if (!canSend && recorder != null && !isClaude && editing == null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val cancelAt = with(density) { 110.dp.toPx() }
                    val lockAt = with(density) { 90.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .size(if (recordingSince != null) 56.dp else 44.dp)
                            .clip(CircleShape)
                            .background(if (recordingSince != null) (if (cancelling) Color(0xFF555555) else Color(0xFFFF4D4D)) else Color.White)
                            .pointerInput(micReady) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    if (!micReady) {
                                        askMic(false) { granted -> micReady = granted }
                                        return@awaitEachGesture
                                    }
                                    if (!recorder.start()) return@awaitEachGesture
                                    voicePlayer?.stop()
                                    recordingSince = Clock.System.now().toEpochMilliseconds()
                                    cancelling = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        cancelling = change.position.x - down.position.x < -cancelAt
                                        // Slid up: it goes on without the finger, with its own send and bin buttons.
                                        if (change.position.y - down.position.y < -lockAt) {
                                            locked = true
                                            cancelling = false
                                            change.consume()
                                            return@awaitEachGesture
                                        }
                                        if (!change.pressed) break
                                        change.consume()
                                    }
                                    val thrownAway = cancelling
                                    recordingSince = null
                                    cancelling = false
                                    if (thrownAway) {
                                        recorder.cancel()
                                    } else {
                                        recorder.stop()?.let { voice ->
                                            controller.sendFile(other, voice, "", quoted())
                                            replyTo = null
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(QMusicIcons.Mic, contentDescription = s.voiceHold, tint = if (recordingSince != null) Color.White else Color.Black, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSend) Color.White else DarkSurfaceVariant)
                            .clickable(enabled = canSend) {
                                val e = editing
                                if (e != null) {
                                    if (text.trim() != e.text) controller.edit(other, e, text)
                                    editing = null
                                    text = ""
                                } else if (controller.send(other, text, replyTo = quoted())) {
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
        if (emojiOpen && !isBlocked) EmojiPanel(onEmoji = { e -> text = (text + e).take(ChatController.MAX_LENGTH) }, modifier = Modifier.navigationBarsPadding())
    }
    viewing?.let { (images, index) ->
        PhotoViewer(images, index, load = { image -> controller.imageBytes(other, image) }) { viewing = null }
    }
    videoOpen?.let { url -> VideoViewer(url) { videoOpen = null } }
    pendingPhotos?.let { photos ->
        SendPhotosPage(
            photos = photos,
            s = s,
            caption = text,
            onSend = { list, caption ->
                controller.sendImages(other, list, caption, quoted())
                text = ""
                replyTo = null
                pendingPhotos = null
            },
            onCancel = { pendingPhotos = null },
        )
    }
    forwarding?.let { list ->
        ForwardDialog(controller, s, onSend = { targets ->
            controller.forward(other, list, targets)
            controller.reportInfo(s.forwarded)
            selected = emptySet()
        }) { forwarding = null }
    }
    deleting?.let { list ->
        val allMine = list.all { it.from == me.uid && !it.deleted }
        ChoiceDialog(
            title = s.deleteWhich,
            choices = buildList {
                if (allMine) add(s.deleteForEveryone to { list.forEach { controller.deleteForEveryone(other, it) } })
                add(s.deleteForMe to { list.forEach { controller.deleteForMe(other, it) } })
            },
            s = s,
        ) {
            deleting = null
            selected = emptySet()
        }
    }
    infoFor?.let { m ->
        val people = if (isGroup) group?.members?.map { it.user }?.filter { it.uid != me.uid }.orEmpty() else listOf(other)
        val read = if (isGroup) readMarks else mapOf(other.uid to readAt)
        val delivered = if (isGroup) deliveredMarks else mapOf(other.uid to deliveredAt)
        MessageInfoDialog(m, people, read, delivered, s) { infoFor = null }
    }
    if (muteDialog) {
        val now = Clock.System.now().toEpochMilliseconds()
        ChoiceDialog(
            s.mute,
            listOf(
                s.mute8h to { controller.setMuted(other, now + 8 * 3600_000L) },
                s.mute1w to { controller.setMuted(other, now + 7 * 24 * 3600_000L) },
                s.muteAlways to { controller.setMuted(other, -1L) },
            ),
            s,
        ) { muteDialog = false }
    }
    if (ttlDialog) {
        ChoiceDialog(
            s.disappearing,
            listOf(
                (if (ttl == 0L) "✓ " else "") + s.ttlOff to { if (ttl != 0L) controller.setTtl(other, 0L) },
                (if (ttl == 24 * 3600_000L) "✓ " else "") + s.ttl24h to { controller.setTtl(other, 24 * 3600_000L) },
                (if (ttl == 7 * 24 * 3600_000L) "✓ " else "") + s.ttl7d to { controller.setTtl(other, 7 * 24 * 3600_000L) },
                (if (ttl == 90 * 24 * 3600_000L) "✓ " else "") + s.ttl90d to { controller.setTtl(other, 90 * 24 * 3600_000L) },
            ),
            s,
            note = s.ttlNote,
        ) { ttlDialog = false }
    }
}

private const val PAGE = 150

private fun ttlLabel(ttl: Long, s: ChatStrings): String = when (ttl) {
    24 * 3600_000L -> s.ttl24h
    7 * 24 * 3600_000L -> s.ttl7d
    90 * 24 * 3600_000L -> s.ttl90d
    else -> "${ttl / 3600_000L}h"
}

/** A photo in a message: its blurred preview at once, the photo itself when it has come; a tap opens it full. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun ChatAlbum(images: List<ChatImage>, load: suspend (ChatImage) -> ByteArray?, onOpen: (Int) -> Unit, scanning: Boolean) {
    if (images.size == 1) {
        val image = images[0]
        val ratio = if (image.width > 0 && image.height > 0) (image.width.toFloat() / image.height).coerceIn(0.56f, 1.9f) else 1f
        PhotoCell(image, load, { onOpen(0) }, scanning, Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)))
        return
    }
    // An album: two columns of squares; past four, the last square says how many more.
    val shown = images.take(4)
    Column(Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                pair.forEachIndexed { col, image ->
                    val index = row * 2 + col
                    val wide = pair.size == 1
                    Box(Modifier.weight(1f).aspectRatio(if (wide) 2f else 1f)) {
                        PhotoCell(image, load, { onOpen(index) }, scanning, Modifier.fillMaxSize())
                        if (index == 3 && images.size > 4) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { onOpen(3) }, contentAlignment = Alignment.Center) {
                                Text("+" + (images.size - 4), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One photo: its blurred preview at once, the photo itself when it has come. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
internal fun PhotoCell(image: ChatImage, load: suspend (ChatImage) -> ByteArray?, onOpen: () -> Unit, scanning: Boolean, modifier: Modifier) {
    var bytes by remember(image.id, image.url) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(image.id, image.url) { bytes = load(image) }
    val thumb = remember(image.thumb) { runCatching { kotlin.io.encoding.Base64.decode(image.thumb) }.getOrNull() }
    Box(
        modifier = modifier
            .background(DarkSurface)
            .clickable(enabled = bytes != null, onClick = onOpen),
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
internal fun nameOf(uid: String, myUid: String, otherName: String, s: ChatStrings): String = when (uid) {
    myUid -> s.you
    CLAUDE_BOT_UID -> "Claude"
    else -> otherName
}

/** The photos of a message, full screen: swipe between them; a tap or back closes it. */
@Composable
internal fun PhotoViewer(images: List<ChatImage>, start: Int, load: suspend (ChatImage) -> ByteArray?, onClose: () -> Unit) {
    PlatformBackHandler(enabled = true) { onClose() }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            var bytes by remember(images[page].id) { mutableStateOf<ByteArray?>(null) }
            LaunchedEffect(images[page].id) { bytes = load(images[page]) }
            Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                val b = bytes
                if (b != null) {
                    AsyncImage(model = b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            }
        }
        if (images.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${images.size}",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** What "/" offers in Claude's conversation: a new conversation, and the models. */
@Composable
private fun SlashCommands(query: String, current: String?, s: ChatStrings, onNew: () -> Unit, onModel: (String) -> Unit) {
    val q = query.lowercase().trim()
    val rows = buildList {
        add(Triple("/yeni", s.cmdNew) { onNew() })
        CLAUDE_MODELS.forEach { model -> add(Triple("/model $model", modelBlurb(model, s)) { onModel(model) }) }
    }.filter { (command, _, _) -> command.startsWith(q) || q == "/" || (q.startsWith("/m") && command.startsWith("/model")) }
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant)
            .padding(vertical = 6.dp),
    ) {
        rows.forEach { (command, blurb, run) ->
            val chosen = current != null && command == "/model $current"
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = run).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(command, color = if (chosen) ClaudeCoral else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
                Text(blurb, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (chosen) Icon(QMusicIcons.Check, contentDescription = null, tint = ClaudeCoral, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun modelBlurb(model: String, s: ChatStrings): String = when (model) {
    "opus" -> s.modelOpus
    "haiku" -> s.modelHaiku
    else -> s.modelSonnet
}

/** Claude's answer as Markdown (bold, lists, code with colours), drawn as it streams in. */
@Composable
private fun ClaudeMarkdown(text: String, streaming: Boolean, modifier: Modifier) {
    val body = TextStyle(color = TextPrimary, fontSize = 16.sp, lineHeight = 22.sp)
    com.mikepenz.markdown.m3.Markdown(
        content = if (streaming) "$text ▍" else text,
        colors = com.mikepenz.markdown.m3.markdownColor(
            text = TextPrimary,
            codeText = TextPrimary,
            codeBackground = Color(0xFF15100E),
            inlineCodeBackground = Color(0xFF15100E),
            linkText = ClaudeCoral,
        ),
        typography = com.mikepenz.markdown.m3.markdownTypography(
            text = body,
            paragraph = body,
            code = body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            h1 = body.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
            h2 = body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            h3 = body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            h4 = body.copy(fontWeight = FontWeight.Bold),
            h5 = body.copy(fontWeight = FontWeight.Bold),
            h6 = body.copy(fontWeight = FontWeight.Bold),
            bullet = body,
            list = body,
            ordered = body,
        ),
        components = com.mikepenz.markdown.compose.components.markdownComponents(
            codeBlock = { com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock(it.content, it.node) },
            codeFence = { com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence(it.content, it.node) },
        ),
        modifier = modifier,
    )
}

/** A person in a list: picture, name, @username, and what can be done ([trailing]). */
@Composable
internal fun PersonRow(user: ChatUser, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(user, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user.username.isNotEmpty()) Text("@" + user.username, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
        }
        trailing()
    }
}

/** A small pill button (white when it is the thing to do, grey otherwise). */
@Composable
internal fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (primary) Color.White else DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = if (primary) Color.Black else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Add / requested (tap to take back) / accept / friends, as fits. */
@Composable
private fun FriendButton(controller: ChatController, user: ChatUser, s: ChatStrings) {
    if (user.uid == CLAUDE_BOT_UID) return
    val status by remember(user.uid) { controller.friendStatus(user.uid) }.collectAsState(initial = FriendStatus.NONE)
    when (status) {
        FriendStatus.NONE -> Pill(s.addFriend, primary = true) { controller.requestFriend(user) }
        FriendStatus.REQUESTED -> Pill(s.requested, primary = false) { controller.unfriend(user) }
        FriendStatus.INCOMING -> Pill(s.accept, primary = true) { controller.acceptFriend(user) }
        FriendStatus.FRIENDS -> Pill("✓ " + s.friendsBadge, primary = false) {}
    }
}

/** Friend requests to answer, then the friends; a tap opens a profile. */
@Composable
private fun FriendsPage(controller: ChatController, s: ChatStrings) {
    val friends by controller.friends.collectAsState()
    val incoming by controller.friendRequests.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(s.friends, { controller.pop() }, actions = {
            IconButton(onClick = { controller.openNewChat() }) { Icon(QMusicIcons.Search, contentDescription = s.findFriends, tint = Color.White) }
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (incoming.isNotEmpty()) {
                item { SectionTitle(s.requests + " · " + incoming.size) }
                items(incoming, key = { "r" + it.uid }) { user ->
                    PersonRow(user, onClick = { controller.openProfile(user) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(s.decline, primary = false) { controller.unfriend(user) }
                            Pill(s.accept, primary = true) { controller.acceptFriend(user) }
                        }
                    }
                }
            }
            item { SectionTitle(s.friends + if (friends.isNotEmpty()) " · " + friends.size else "") }
            if (friends.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.noFriends, color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.width(220.dp)) { WhiteButton(s.findFriends) { controller.openNewChat() } }
                    }
                }
            }
            items(friends, key = { "f" + it.uid }) { user ->
                PersonRow(user, onClick = { controller.openProfile(user) }) {
                    IconButton(onClick = { controller.openConversation(user) }) { Icon(QMusicIcons.Chat, contentDescription = s.message, tint = Color.White) }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
}

/**
 * Someone's page: the big picture (a tap shows it full screen), name, @username, whether they are online, what can
 * be done with them, and the photos shared in the conversation.
 */
@Composable
private fun ProfilePage(controller: ChatController, s: ChatStrings, me: ChatUser, user: ChatUser, onCall: ((ChatUser, Boolean) -> Unit)?) {
    val presence by remember(user.uid) { controller.presence(user) }.collectAsState(initial = null)
    val blocked by controller.blocked.collectAsState()
    val messages by remember(user.uid) { controller.messages(user) }.collectAsState(initial = null)
    var bigPhoto by remember { mutableStateOf(false) }
    var viewingPhotos by remember { mutableStateOf<Pair<List<ChatImage>, Int>?>(null) }
    var videoOpen by remember { mutableStateOf<String?>(null) }
    val openFile = rememberAttachmentOpener()
    // Their latest profile (the about line, a new picture).
    val fresh by androidx.compose.runtime.produceState(user, user.uid) { controller.userFresh(user.uid)?.let { value = it } }
    val isClaude = user.uid == CLAUDE_BOT_UID
    val isBlocked = user.uid in blocked
    val friendStatus by remember(user.uid) { controller.friendStatus(user.uid) }.collectAsState(initial = FriendStatus.NONE)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item { TopBar("", { controller.pop() }) }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(CircleShape).clickable(enabled = user.photo != null) { bigPhoto = true }) { ChatAvatar(user, 132.dp) }
                    Spacer(Modifier.height(16.dp))
                    Text(user.name, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (user.username.isNotEmpty() && !isClaude) Text("@" + user.username, color = TextSecondary, fontSize = 15.sp)
                    if (fresh.about.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(fresh.about, color = TextPrimary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    }
                    val seen = presence
                    val status = when {
                        seen?.online == true -> s.online
                        seen != null && seen.lastSeen > 0 -> s.lastSeen + " " + seenLabel(seen.lastSeen, s)
                        else -> null
                    }
                    if (status != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(status, color = if (seen?.online == true) TextPrimary else TextMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProfileAction(QMusicIcons.Chat, s.message) { controller.openConversation(user) }
                        if (onCall != null && !isClaude && !isBlocked) {
                            ProfileAction(QMusicIcons.Phone, s.callVoice) { onCall(user, false) }
                            ProfileAction(QMusicIcons.Camera, s.callVideo) { onCall(user, true) }
                        }
                    }
                    if (!isClaude && user.uid != me.uid) {
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FriendButton(controller, user, s)
                            if (friendStatus == FriendStatus.FRIENDS) Pill(s.removeFriend, primary = false) { controller.unfriend(user) }
                            Pill(if (isBlocked) s.unblock else s.block, primary = false) { controller.setBlocked(user, !isBlocked) }
                        }
                    }
                }
            }
            if (!messages.isNullOrEmpty()) {
                item { SectionTitle(s.media) }
                item {
                    MediaTabs(
                        messages.orEmpty(),
                        s,
                        loadImage = { i -> controller.imageBytes(user, i) },
                        onOpenPhotos = { list, index -> viewingPhotos = list to index },
                        onOpenVideo = { videoOpen = it },
                        onOpenFile = openFile,
                    )
                }
            }
        }
        if (bigPhoto && user.photo != null) {
            PlatformBackHandler(enabled = true) { bigPhoto = false }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { bigPhoto = false }, contentAlignment = Alignment.Center) {
                AsyncImage(model = user.photo.replace("=s96-c", "=s1024-c"), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
        viewingPhotos?.let { (list, index) ->
            PhotoViewer(list, index, load = { i -> controller.imageBytes(user, i) }) { viewingPhotos = null }
        }
        videoOpen?.let { url -> VideoViewer(url) { videoOpen = null } }
    }
}

@Composable
private fun ProfileAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
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

    /** "5 unread messages", over the first one not read when the conversation was opened. */
    data class Unread(val count: Int) : ListRow {
        override val key get() = "unread"
    }
}

/** The messages with a date line wherever the day changes. */
private fun withDays(list: List<ChatMessage>, unreadAfter: Long = Long.MAX_VALUE, myUid: String = ""): List<ListRow> {
    val out = ArrayList<ListRow>(list.size + 5)
    var day: kotlinx.datetime.LocalDate? = null
    val firstUnread = list.indexOfFirst { it.at > unreadAfter && it.from != myUid && !it.system }
    for ((i, m) in list.withIndex()) {
        val d = local(m.at).date
        if (d != day) {
            out += ListRow.Day(m.at)
            day = d
        }
        if (i == firstUnread) out += ListRow.Unread(list.size - i)
        out += ListRow.Message(m)
    }
    return out
}

/** A message's text with its links tappable. */
@Composable
private fun LinkedText(text: String, color: Color, linkColor: Color, modifier: Modifier) {
    val found = links(text)
    if (found.isEmpty()) {
        Text(text = text, color = color, fontSize = 16.sp, lineHeight = 21.sp, modifier = modifier)
        return
    }
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var at = 0
        for (link in found) {
            val start = text.indexOf(link, at)
            if (start < 0) continue
            append(text.substring(at, start))
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    link,
                    androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)),
                ),
            ) { append(link) }
            at = start + link.length
        }
        append(text.substring(at))
    }
    Text(text = annotated, color = color, fontSize = 16.sp, lineHeight = 21.sp, modifier = modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    mine: Boolean,
    read: Boolean,
    delivered: Boolean = false,
    s: ChatStrings,
    otherName: String,
    reactions: Map<String, String>,
    myUid: String,
    onOpenShare: (SharedItem) -> Unit,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onReact: (String?) -> Unit,
    loadImage: suspend (ChatImage) -> ByteArray?,
    onOpenImages: (List<ChatImage>, Int) -> Unit,
    /** Claude is answering this message: looking, thinking or writing. */
    assisting: String? = null,
    inClaudeChat: Boolean = false,
    /** The group members' names, for quotes. */
    names: Map<String, String> = emptyMap(),
    /** In a group: who wrote it (shown over it). */
    senderName: String? = null,
    voicePlayer: VoicePlayer? = null,
    onOpenVideo: (String) -> Unit = {},
    onOpenFile: (ChatAttachment) -> Unit = {},
    onJoin: () -> Unit = {},
    /** The message the search is on. */
    highlight: Boolean = false,
    starred: Boolean = false,
    /** Picking several messages: a tap ticks this one. */
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onCopy: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onStar: () -> Unit = {},
    onInfo: (() -> Unit)? = null,
    onQuoteTap: (String) -> Unit = {},
    loadPreview: suspend (String) -> LinkPreview? = { null },
) {
    if (m.system) {
        SystemLine(m.text)
        return
    }
    var menu by remember { mutableStateOf(false) }
    // One to three emoji alone: big, without a bubble, as people expect.
    val bigEmoji = !m.deleted && m.images.isEmpty() && m.attachment == null && m.share == null && m.replyTo == null && isBigEmoji(m.text)
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
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .pointerInput(m.id, selecting) {
                if (selecting) return@pointerInput
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
                .background(if (bigEmoji) Color.Transparent else if (m.deleted) DarkSurface else if (mine) Color.White else if (fromClaude) ClaudeBubble else DarkSurfaceVariant)
                .then(
                    if (highlight) {
                        Modifier.border(2.dp, ClaudeCoral, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    } else if (assisting != null) {
                        Modifier.border(2.dp, ClaudeCoral.copy(alpha = pulse), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() else m.share?.let(onOpenShare) },
                    onLongClick = { if (selecting) onToggleSelect() else menu = true },
                )
                .padding(if (m.share != null || m.images.isNotEmpty() || m.attachment != null) 4.dp else 0.dp),
        ) {
            val fg = if (mine && !m.deleted && !bigEmoji) Color.Black else TextPrimary
            val fgSoft = if (mine && !m.deleted && !bigEmoji) Color.Black.copy(alpha = 0.55f) else TextSecondary
            if (m.forwarded && !m.deleted) {
                Text("↪ " + s.forwarded, color = fgSoft, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp))
            }
            if (senderName != null) {
                Text(
                    senderName,
                    color = if (m.from == CLAUDE_BOT_UID) ClaudeCoral else memberColor(m.from),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = if (m.share != null || m.images.isNotEmpty() || m.attachment != null) 4.dp else 8.dp),
                )
            }
            if (m.deleted) {
                Text(
                    "🚫 " + s.deletedMessage,
                    color = TextSecondary,
                    fontSize = 15.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
            }
            m.replyTo?.takeIf { !m.deleted }?.let { quoted ->
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (mine) Color.Black.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.07f))
                        .clickable(enabled = !selecting) { onQuoteTap(quoted.id) }
                        .padding(end = 10.dp),
                ) {
                    Box(Modifier.width(3.dp).height(40.dp).background(if (mine) Color.Black else Color.White))
                    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(
                            if (quoted.from == myUid) s.you else names[quoted.from] ?: nameOf(quoted.from, myUid, otherName, s),
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
            if (m.images.isNotEmpty()) {
                ChatAlbum(m.images, loadImage, { index -> onOpenImages(m.images, index) }, scanning = assisting != null)
                m.progress?.let { p ->
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { p },
                        color = if (mine) Color.Black else Color.White,
                        trackColor = Color.Transparent,
                        modifier = Modifier.width(260.dp).padding(top = 4.dp),
                    )
                }
            }
            m.attachment?.let { a ->
                when (a.kind) {
                    AttachmentKind.VOICE -> VoiceBubble(a, mine, voicePlayer, m.progress)
                    AttachmentKind.VIDEO -> VideoBubble(a, m.progress) { onOpenVideo(a.url) }
                    AttachmentKind.FILE -> FileBubble(a, mine, m.progress, s) { onOpenFile(a) }
                }
            }
            m.share?.let { ShareCard(it, mine, s) }
            if (m.together && m.share != null && !mine) {
                Box(Modifier.padding(start = 8.dp, top = 8.dp)) { Pill((if (m.share.kind == ShareKind.SONG) "🎧 " else "🍿 ") + s.join, primary = true, onClick = onJoin) }
            }
            if (m.text.isNotEmpty() && !m.deleted) {
                val textModifier = Modifier.padding(start = 12.dp, end = 12.dp, top = if (!mine && m.model != null) 2.dp else 8.dp)
                when {
                    fromClaude -> ClaudeMarkdown(m.text, m.streaming, textModifier)
                    bigEmoji -> Text(text = m.text, fontSize = 46.sp, lineHeight = 54.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    else -> LinkedText(m.text, fg, if (mine) Color(0xFF0B57D0) else Color(0xFF8AB4F8), textModifier)
                }
                if (!fromClaude) firstLink(m.text)?.let { link -> LinkPreviewCard(link, mine, loadPreview) }
            }
            Row(
                modifier = Modifier.align(Alignment.End).padding(start = 12.dp, end = 10.dp, bottom = 6.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.expireAt > 0) Text("⏱ ", color = fgSoft, fontSize = 10.sp)
                if (starred) Text("★ ", color = Color(0xFFFFB800), fontSize = 11.sp)
                if (m.edited && !m.deleted) Text(s.edited + "  ", color = fgSoft, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Text(clock(m.at), color = fgSoft, fontSize = 11.sp)
                if (mine && !m.deleted) {
                    Spacer(Modifier.width(4.dp))
                    // Waiting (no connection): a clock; sent: one grey tick; on their phone: two grey; read: two green.
                    Text(
                        text = when {
                            m.pending -> "◷"
                            read || delivered -> "✓✓"
                            else -> "✓"
                        },
                        color = when {
                            m.pending -> fgSoft
                            read -> TickRead
                            else -> fgSoft.copy(alpha = if (delivered) 0.75f else 0.55f)
                        },
                        fontSize = 12.sp,
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
            if (!m.deleted) DropdownMenuItem(text = { Text(s.reply, color = TextPrimary) }, onClick = { menu = false; onReply() })
            if (onCopy != null && m.text.isNotEmpty() && !m.deleted) DropdownMenuItem(text = { Text(s.copy, color = TextPrimary) }, onClick = { menu = false; onCopy() })
            if (onForward != null && !m.deleted) DropdownMenuItem(text = { Text(s.forward, color = TextPrimary) }, onClick = { menu = false; onForward() })
            if (onEdit != null) DropdownMenuItem(text = { Text(s.edit, color = TextPrimary) }, onClick = { menu = false; onEdit() })
            if (!m.deleted) DropdownMenuItem(text = { Text(if (starred) s.unstar else s.star, color = TextPrimary) }, onClick = { menu = false; onStar() })
            if (onInfo != null) DropdownMenuItem(text = { Text(s.info, color = TextPrimary) }, onClick = { menu = false; onInfo() })
            DropdownMenuItem(text = { Text(s.select, color = TextPrimary) }, onClick = { menu = false; onToggleSelect() })
            DropdownMenuItem(text = { Text(s.deleteMessage, color = TextPrimary) }, onClick = { menu = false; onDelete() })
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
internal fun quoteText(m: ChatMessage): String = m.text.ifBlank {
    when {
        m.attachment?.kind == AttachmentKind.VOICE -> "🎤 " + mmss(m.attachment.durationMs)
        m.attachment?.kind == AttachmentKind.VIDEO -> "🎬 Video"
        m.attachment?.kind == AttachmentKind.FILE -> "📄 " + m.attachment.name
        m.images.isNotEmpty() -> if (m.images.size == 1) "📷" else "📷 ×" + m.images.size
        m.share != null -> (if (m.share.kind == ShareKind.SONG) "🎵 " else "🎬 ") + m.share.title
        else -> ""
    }
}

/** "today 14:32", "yesterday 09:10", "08.11 21:05". */
private fun seenLabel(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> s.today.lowercase() + " " + clock(ms)
    1 -> s.yesterday.lowercase() + " " + clock(ms)
    else -> local(ms).let { two(it.dayOfMonth) + "." + two(it.monthNumber) + " " + clock(ms) }
}

@Composable
internal fun ShareCard(item: SharedItem, mine: Boolean, s: ChatStrings) {
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

internal fun clock(ms: Long): String = local(ms).let { "${two(it.hour)}:${two(it.minute)}" }

private fun daysAgo(ms: Long): Int {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return (today.toEpochDays() - local(ms).date.toEpochDays())
}

private fun dayLabel(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> s.today
    1 -> s.yesterday
    else -> local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}.${it.year}" }
}

internal fun listTime(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> clock(ms)
    1 -> s.yesterday
    else -> local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}" }
}
