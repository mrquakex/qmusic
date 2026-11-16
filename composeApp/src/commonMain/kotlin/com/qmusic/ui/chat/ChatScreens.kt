package com.qmusic.ui.chat

import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
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
import androidx.compose.foundation.lazy.itemsIndexed
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

/** Loads a photo that the auto-download setting held back (it was tapped). */
internal val LocalForceImage = androidx.compose.runtime.staticCompositionLocalOf<(ChatImage) -> Unit> { {} }

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
    controller.shareables = shareables
    controller.openShare = onOpenShare
    androidx.compose.runtime.CompositionLocalProvider(LocalForceImage provides { image -> controller.forceImage(image) }) {
        ChatAppContent(controller, s, shareables, onOpenShare, onExit, modifier)
    }
}

@Composable
private fun ChatAppContent(
    controller: ChatController,
    s: ChatStrings,
    shareables: () -> List<SharedItem>,
    onOpenShare: (SharedItem) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val account by controller.account.collectAsState()
    val routes by controller.routes.collectAsState()
    val error by controller.error.collectAsState()
    // Messages as home: Back on the list leaves the app (as any home screen), instead of going to the music.
    val home by AppHome.messagesFirst.collectAsState()
    PlatformBackHandler(enabled = routes.isNotEmpty() || !home || account !is ChatAccount.Ready) { if (!controller.pop()) onExit() }
    val askPermissions = rememberCallPermissions()
    val voicePlayer = rememberVoicePlayer()
    val lock = rememberAppLock()
    val lockOn by (lock?.enabled ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val unlocked by ChatLock.unlocked.collectAsState()
    val calls = controller.calls
    val onCall: ((ChatUser, Boolean) -> Unit)? = calls?.let { service ->
        { other, video -> askPermissions(video) { granted -> if (granted) service.call(other, video) else controller.reportError(s.callPermission) } }
    }
    val onGroupCall: ((ChatUser, Boolean) -> Unit)? = controller.groupCalls?.let { service ->
        { group, video -> askPermissions(video) { granted -> if (granted) service.join(group, video) else controller.reportError(s.callPermission) } }
    }

    Box(modifier.fillMaxSize().background(BgRoot)) {
        when (val a = account) {
            ChatAccount.Loading -> Centered { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            is ChatAccount.Unavailable -> MessagePage(s, onExit, if (a.reason == "setup") s.setupNeeded else s.unavailable)
            ChatAccount.SignedOut -> SignInPage(controller, s, onExit)
            is ChatAccount.NeedsProfile -> ProfilePage(controller, s, a, onExit)
            is ChatAccount.Ready -> if (lock != null && lockOn && !unlocked && lock.available) LockedPage(lock, s, onExit) else when (val top = routes.lastOrNull()) {
                is ChatRoute.Conversation -> ConversationPage(controller, s, a.me, top.other, shareables, onOpenShare, onCall, voicePlayer)
                ChatRoute.NewChat -> NewChatPage(controller, s)
                ChatRoute.NewGroup -> NewGroupPage(controller, s)
                is ChatRoute.GroupInfo -> GroupInfoPage(controller, s, a.me, top.group)
                is ChatRoute.AddMembers -> AddMembersPage(controller, s, top.group)
                ChatRoute.Archived -> ArchivedPage(controller, s, a.me)
                ChatRoute.Starred -> StarredPage(controller, s)
                ChatRoute.Calls -> CallsPage(controller, s, onCall)
                is ChatRoute.CallDetails -> CallDetailsPage(controller, s, top.peer, onCall, onGroupCall)
                is ChatRoute.NewCall -> NewCallPage(controller, s, top.group, onCall, onGroupCall)
                is ChatRoute.StatusViewer -> StatusViewerPage(controller, s, a.me, top.ownerUid, top.mine)
                ChatRoute.TextStatus -> TextStatusPage(controller, s)
                ChatRoute.StatusPrivacyPage -> StatusPrivacyPage(controller, s)
                ChatRoute.MyQr -> MyQrPage(controller, s, a.me)
                ChatRoute.LockedFolder -> LockedFolderPage(controller, s, a.me)
                is ChatRoute.JoinGroup -> JoinGroupPage(controller, s, top.code)
                ChatRoute.Broadcasts -> BroadcastsPage(controller, s)
                is ChatRoute.BroadcastEdit -> BroadcastEditPage(controller, s, top.id)
                is ChatRoute.BroadcastChat -> BroadcastChatPage(controller, s, top.id)
                ChatRoute.Settings -> SettingsPage(controller, s, a.me)
                ChatRoute.Friends -> FriendsPage(controller, s)
                is ChatRoute.Profile -> ProfilePage(controller, s, a.me, top.user, onCall)
                null -> ChatHome(controller, s, a.me, onExit, onCall, onGroupCall)
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
                color = InkInverse,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink)
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
                color = InkInverse,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink)
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
        IconButton(onClick = onBack) { Icon(QMusicIcons.Back, contentDescription = null, tint = Ink) }
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
            Icon(QMusicIcons.Group, contentDescription = null, tint = Ink, modifier = Modifier.size(size * 0.5f))
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
            .background(if (enabled) Ink else Ink.copy(alpha = 0.3f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = InkInverse, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Text(text, color = InkInverse, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                cursorBrush = SolidColor(Ink),
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
                Icon(QMusicIcons.Chat, contentDescription = null, tint = Ink, modifier = Modifier.size(40.dp))
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
            Text(if (taken) s.usernameTaken else s.usernameRules, color = if (taken) Ink else TextMuted, fontSize = 12.sp)
        }
        Box(Modifier.padding(24.dp)) {
            WhiteButton(s.continueButton, enabled = valid, busy = busy) { controller.saveProfile(name, username) }
        }
    }
}

// ------------------------------------------------------------------ the list

internal fun readinessLabel(issue: ReadinessIssue, s: ChatStrings): String = when (issue) {
    ReadinessIssue.NOTIFICATIONS -> s.readinessNotifications
    ReadinessIssue.CHANNELS -> s.readinessChannels
    ReadinessIssue.FULL_SCREEN -> s.readinessFullScreen
    ReadinessIssue.BATTERY -> s.readinessBattery
    ReadinessIssue.AUTOSTART -> s.readinessAutostart
    ReadinessIssue.LOCK_SCREEN -> s.readinessLockScreen
}

private fun readinessHint(issue: ReadinessIssue, s: ChatStrings): String = when (issue) {
    ReadinessIssue.NOTIFICATIONS -> s.setupHintNotifications
    ReadinessIssue.CHANNELS -> s.setupHintChannels
    ReadinessIssue.FULL_SCREEN -> s.setupHintFullScreen
    ReadinessIssue.BATTERY -> s.setupHintBattery
    ReadinessIssue.AUTOSTART -> s.setupHintAutostart
    ReadinessIssue.LOCK_SCREEN -> s.setupHintLockScreen
}

/** Shown once per opening when this phone is not ready: dismissed for the rest of the time the app is open. */
internal object SetupWizardState {
    var dismissed = false
}

/**
 * The setup, start to end: one button walks through every missing setting. Each page opens by itself; coming back,
 * the app checks it, and opens the next. What could not be turned on is listed, with a way to try again.
 */
@Composable
internal fun SetupWizard(s: ChatStrings) {
    val readiness = rememberReadiness()
    var open by remember { mutableStateOf(!SetupWizardState.dismissed && readiness.issues.isNotEmpty()) }
    var running by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(setOf<ReadinessIssue>()) }
    var current by remember { mutableStateOf<ReadinessIssue?>(null) }
    val issues = readiness.issues
    // Back from a page (the issues were checked again): the next one not tried yet opens by itself.
    LaunchedEffect(running, issues) {
        if (!running) return@LaunchedEffect
        val next = issues.firstOrNull { it !in tried }
        if (next == null) {
            running = false
            current = null
            return@LaunchedEffect
        }
        current = next
        kotlinx.coroutines.delay(900)
        tried = tried + next
        readiness.fix(next)
    }
    if (!open) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(DarkSurfaceVariant).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(QMusicIcons.Phone, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text(s.setupTitle, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            when {
                issues.isEmpty() -> Text(s.setupDone, color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                running && current != null -> {
                    Text(readinessLabel(current!!, s), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(readinessHint(current!!, s), color = TextSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                else -> {
                    Text(if (tried.isEmpty()) s.setupBody else s.setupStill, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    issues.forEach { issue ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5A5F)))
                            Spacer(Modifier.width(10.dp))
                            Text(readinessLabel(issue, s), color = TextPrimary, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (issues.isEmpty()) {
                WhiteButton(s.closeLabel) { open = false; SetupWizardState.dismissed = true }
            } else if (!running) {
                WhiteButton(if (tried.isEmpty()) s.setupStart else s.setupAgain) {
                    tried = emptySet()
                    running = true
                }
                Text(
                    s.setupLater,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { open = false; SetupWizardState.dismissed = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** What on this phone keeps calls and messages from arriving, each a tap away from its settings page. */
@Composable
internal fun ReadinessCard(s: ChatStrings) {
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
                    readinessLabel(issue, s),
                    color = InkInverse,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Ink)
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
    ring: Color? = null,
    listening: SharedItem? = null,
    onListening: () -> Unit = {},
    /** The state of our last message, when the last one is ours (sent, on their phone, read). */
    tick: TickState? = null,
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
        Box(Modifier.clip(CircleShape).clickable(onClick = onAvatar)) { StatusRinged(ring) { ChatAvatar(t.other, if (ring != null) 46.dp else 52.dp) } }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.other.name,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = if (t.unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!t.isGroup) UserBadge(t.other.uid, compact = true, modifier = Modifier.padding(start = 5.dp))
                }
                if (t.muted(if (now > 0) now else Clock.System.now().toEpochMilliseconds())) Icon(QMusicIcons.VolumeMute, contentDescription = null, tint = TextMuted, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                if (t.pinned > 0) Icon(QMusicIcons.Pin, contentDescription = null, tint = TextMuted, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                Text(listTime(t.lastAt, s), color = if (t.unread) TickRead else TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = when {
                    // Ours: the ticks say it (as on WhatsApp), not "You:".
                    t.lastFrom == me.uid && tick != null -> ""
                    t.lastFrom == me.uid -> "${s.you}: "
                    t.isGroup && t.lastFromName.isNotEmpty() && !t.lastText.startsWith("Claude: ") -> "${t.lastFromName}: "
                    else -> ""
                }
                if (typingNow) {
                    Text(
                        (if (t.isGroup && t.typingName.isNotEmpty()) t.typingName + " " else "") + (if (t.typingRec) s.recordingVoice else s.typing),
                        color = TickRead,
                        fontSize = 14.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    if (tick != null) Box(Modifier.padding(end = 4.dp)) { MessageTick(tick, TextSecondary) }
                    val parts = remember(t.lastText, s.english) { ChatPreview.split(t.lastText, s.english) }
                    parts.kind?.let { kind ->
                        Text(prefix, color = if (t.unread) TextPrimary else TextSecondary, fontSize = 14.sp, maxLines = 1)
                        Icon(
                            ChatPreview.icon(kind),
                            contentDescription = null,
                            tint = if (kind == PreviewKind.MISSED_CALL) Color(0xFFFF5A5F) else TextSecondary,
                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                        )
                    }
                    Text(
                        if (parts.kind != null) parts.text else prefix + parts.text,
                        color = if (t.unread) TextPrimary else TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (t.unread && t.mentioned) {
                    // Named in it: a green @ before the count.
                    Box(
                        Modifier.padding(start = 8.dp).size(20.dp).clip(CircleShape).background(TickRead),
                        contentAlignment = Alignment.Center,
                    ) { Text("@", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                if (t.unread) {
                    val n = t.unreadCount.coerceAtLeast(1)
                    Box(
                        Modifier.padding(start = 8.dp).height(20.dp).widthIn(min = 20.dp).clip(CircleShape)
                            .background(if (t.muted(if (now > 0) now else Clock.System.now().toEpochMilliseconds())) TextMuted else TickRead).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (n > 99) "99+" else n.toString(), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            if (listening != null && !typingNow) ListeningLine(listening, Modifier.padding(top = 2.dp), onListening)
        }
    }
    }
}

// ------------------------------------------------------------------ finding people

@Composable
private fun NewChatPage(controller: ChatController, s: ChatStrings) {
    val w = ChatMusic.words()
    val query by controller.query.collectAsState()
    val found by controller.found.collectAsState()
    val friends by controller.friends.collectAsState()
    val incoming by controller.friendRequests.collectAsState()
    val focus = remember { FocusRequester() }
    val scan = rememberQrScanner { text -> controller.openQeyfCode(text, s) }
    val q = query.trim().removePrefix("@")
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Friends whose name or @username has the words, found here at once; people on Qeyf come from the search.
    val friendHits = remember(friends, q) {
        if (q.isEmpty()) friends else friends.filter { it.name.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }
    }
    val others = found.orEmpty().filter { f -> friendHits.none { it.uid == f.uid } }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(s.newChat, { controller.pop() })
        // The search, always there at the top.
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(DarkSurfaceVariant).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QMusicIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(w.findPeople, color = TextMuted, fontSize = 16.sp, maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = controller::setQuery,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { controller.setQuery("") }, contentAlignment = Alignment.Center) {
                    Icon(QMusicIcons.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 32.dp)) {
            if (q.isEmpty()) {
                // What one starts a conversation with, besides a name.
                item(key = "group") { QuickRow(QMusicIcons.Group, s.newGroup, w.newGroupNote) { controller.openNewGroup() } }
                if (scan != null) item(key = "scan") { QuickRow(QMusicIcons.Camera, w.scanCode, w.scanCodeNote) { scan() } }
                item(key = "mine") { QuickRow(QMusicIcons.Share, w.myCode, w.myCodeNote) { controller.push(ChatRoute.MyQr) } }
                if (incoming.isNotEmpty()) {
                    item(key = "reqHead") { SectionTitle(s.requests + " · " + incoming.size) }
                    items(incoming, key = { "r" + it.uid }) { user ->
                        PeopleRow(user, onClick = { controller.openProfile(user) }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(s.decline, primary = false) { controller.unfriend(user) }
                                Pill(s.accept, primary = true) { controller.acceptFriend(user) }
                            }
                        }
                    }
                }
                item(key = "friendsHead") { SectionTitle(if (friends.isEmpty()) w.yourFriends else w.yourFriends + " · " + friends.size) }
                if (friends.isEmpty()) {
                    item(key = "noFriends") {
                        EmptyPeople(QMusicIcons.Account, w.noFriendsYet, w.noFriendsNote)
                    }
                }
                items(friendHits, key = { "f" + it.uid }) { user -> PeopleRow(user, onClick = { controller.openConversation(user) }) }
            } else {
                if (friendHits.isNotEmpty()) {
                    item(key = "fHead") { SectionTitle(w.yourFriends) }
                    items(friendHits, key = { "f" + it.uid }) { user -> PeopleRow(user, onClick = { controller.openConversation(user) }) }
                }
                item(key = "oHead") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle(w.onQeyf) }
                        if (found == null && q.length >= 2) {
                            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.padding(end = 20.dp).size(16.dp))
                        }
                    }
                }
                when {
                    q.length < 2 -> item(key = "short") { Text(w.typeMore, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    found != null && others.isEmpty() && friendHits.isEmpty() -> item(key = "none") {
                        EmptyPeople(QMusicIcons.Search, w.noOne(q), w.noOneNote)
                    }
                    else -> items(others, key = { "o" + it.uid }) { user ->
                        PeopleRow(user, onClick = { controller.openProfile(user) }) { FriendButton(controller, user, s) }
                    }
                }
            }
        }
    }
}

/** A way to start something (a group, scanning a code): a round icon, a title and a line about it. */
@Composable
private fun QuickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(TickRead), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A person in the lists of the new conversation: their picture, name, and their about line (or @username). */
@Composable
private fun PeopleRow(user: ChatUser, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(user, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                user.about.takeIf { it.isNotBlank() } ?: ("@" + user.username),
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** Nothing to show yet: an icon in a soft circle, what is the matter, and what to do. */
@Composable
private fun EmptyPeople(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(note, color = TextSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    // The "unread" line marks what came before the chat was opened; it goes after a few seconds, or when the person writes.
    val openedAt = remember(other.uid) { Clock.System.now().toEpochMilliseconds() + 5_000 }
    var unreadLineOn by remember(other.uid) { mutableStateOf(openedReadAt != Long.MAX_VALUE) }
    LaunchedEffect(other.uid, unreadLineOn) {
        if (unreadLineOn) {
            kotlinx.coroutines.delay(8_000)
            unreadLineOn = false
        }
    }
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
    var sharedListOpen by remember { mutableStateOf(false) }
    var pendingPhotos by remember { mutableStateOf<List<PickedImage>?>(null) }
    var sendViewOnce by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val sounds = rememberChatSounds()
    // The last message heard here: a new one after it plays the send or arrive sound (not the first load).
    var lastHeard by remember(other.uid) { mutableStateOf<String?>(null) }
    val pollVotes by remember(other.uid) { controller.pollVotes(other) }.collectAsState(initial = emptyMap())
    var pollDialog by remember { mutableStateOf(false) }
    var votersFor by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    val saveContact = rememberContactSaver()
    val shareTextFile = rememberTextFileShare()
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    val pickContact = rememberContactPicker { contact -> controller.sendContact(other, contact) }
    val groupCalls = controller.groupCalls
    val callPeople by remember(other.uid) { if (other.isGroup) controller.groupCallParticipants(other) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val group by remember(other.uid) { if (isGroup) controller.group(other) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(initial = null)
    val memberNames = remember(group) { group?.members?.associate { it.user.uid to it.user.name }.orEmpty() }
    val memberUsers = remember(group) { group?.members?.associate { it.user.uid to it.user }.orEmpty() }
    val title = group?.name ?: other.name
    val blocked by controller.blocked.collectAsState()
    val isBlocked = !isGroup && other.uid in blocked
    val pins by remember(other.uid) { controller.pinned(other) }.collectAsState(initial = emptyList())
    val openedMap by remember(other.uid) { controller.opened(other) }.collectAsState(initial = emptyMap())
    val iAmAdmin = group?.members?.any { it.user.uid == me.uid && it.admin } == true
    // A group where only admins write: the box gives way to a note for everyone else.
    val writeLocked = isGroup && group?.settings?.sendAdmins == true && !iAmAdmin
    val canPin = !isGroup || group?.settings?.editAdmins != true || iAmAdmin
    var viewOnceOpen by remember { mutableStateOf<ChatMessage?>(null) }
    // Who is named with @ in the box, by the name written for them.
    var mentionNames by remember(other.uid) { mutableStateOf(mapOf<String, String>()) }
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
    val pickLocation = rememberLocationPicker(
        onPicked = { place -> controller.sendLocation(other, place) },
        onError = { why ->
            controller.reportError(
                when (why) {
                    "off" -> s.locationOff
                    "permission" -> s.locationDenied
                    else -> s.locationNone
                },
            )
        },
    )

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
    val recordingThem by remember(other.uid) { controller.recording(other) }.collectAsState(initial = false)
    DisposableEffect(other.uid) { onDispose { controller.stopRecordingIfAny(other) } }
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
    val rows = remember(messages, unreadLineOn) { messages?.let { withDays(it, if (unreadLineOn) openedReadAt else Long.MAX_VALUE, me.uid, openedAt).asReversed() } }
    val selectedMessages = remember(messages, selected) { messages.orEmpty().filter { it.id in selected } }
    val starredIds = remember(starredList, chatId) { starredList.filter { it.chatId == chatId }.map { it.messageId }.toSet() }
    // In a group a message is read when every other member read it (delivered likewise).
    val others = remember(group, me.uid) { group?.members?.map { it.user.uid }?.filter { it != me.uid && it != CLAUDE_BOT_UID }.orEmpty() }
    fun readByAll(at: Long) = others.isNotEmpty() && others.all { (readMarks[it] ?: 0L) >= at }
    fun deliveredToAll(at: Long) = others.isNotEmpty() && others.all { maxOf(readMarks[it] ?: 0L, deliveredMarks[it] ?: 0L) >= at }
    // A tapped quote: the list goes to the message answered (fetching older ones when needed).
    // Opened from a search: straight to the message found.
    var jumpTo by remember { mutableStateOf(controller.takeJump(other)) }
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
                IconButton(onClick = { selected = emptySet() }) { Icon(QMusicIcons.Close, null, tint = Ink) }
                Text("${selected.size} ${s.selected}", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selectedMessages.any { it.text.isNotEmpty() }) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(selectedMessages.sortedBy { it.at }.filter { it.text.isNotEmpty() }.joinToString("\n") { it.text }))
                        controller.reportInfo(s.copied)
                        selected = emptySet()
                    }) { Text(s.copy, color = Ink) }
                }
                IconButton(onClick = {
                    val chatName = title
                    val allStarred = selectedMessages.all { it.id in starredIds }
                    selectedMessages.forEach { m -> controller.setStarred(other, m, if (m.from == me.uid) me.name else memberNames[m.from] ?: other.name, chatName, !allStarred) }
                    selected = emptySet()
                }) { Icon(QMusicIcons.Star, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp)) }
                TextButton(onClick = { forwarding = selectedMessages.filter { !it.deleted } }) { Text(s.forward, color = Ink) }
                IconButton(onClick = { deleting = selectedMessages }) { Icon(QMusicIcons.Trash, null, tint = Ink) }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (!isGroup) UserBadge(other.uid, compact = true, modifier = Modifier.padding(start = 5.dp))
                        }
                        val seen = presence
                        val status = when {
                            recordingThem -> s.recordingVoice
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
                        // What they play now; a tap plays it here.
                        val playingNow = rememberListening("conversation", if (isGroup || isClaude) emptySet() else setOf(other.uid))[other.uid]
                        if (playingNow != null && !typing && !recordingThem) ListeningLine(playingNow) { onOpenShare(playingNow) }
                    }
                }
            },
            actions = {
                if (onCall != null && !isBlocked && !isClaude && !isGroup) {
                    IconButton(onClick = { onCall(other, true) }) { Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = Ink) }
                    IconButton(onClick = { onCall(other, false) }) { Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = Ink) }
                }
                if (isGroup && groupCalls != null) {
                    val startGroupCall: (Boolean) -> Unit = { video ->
                        if (callPeople.size >= MAX_GROUP_CALL) {
                            controller.reportError(s.groupCallFull)
                        } else {
                            askMic(video) { granted -> if (granted) groupCalls.join(other.copy(name = title), video) else controller.reportError(s.callPermission) }
                        }
                    }
                    IconButton(onClick = { startGroupCall(true) }) { Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = Ink) }
                    IconButton(onClick = { startGroupCall(false) }) { Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = Ink) }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                        if (isGroup) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Group, null, tint = Ink) },
                                text = { Text(s.groupInfo, color = TextPrimary) },
                                onClick = { menu = false; controller.openGroupInfo(other) },
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = { Icon(QMusicIcons.Search, null, tint = Ink) },
                            text = { Text(s.searchMessages, color = TextPrimary) },
                            onClick = { menu = false; searching = true },
                        )
                        val muted = thread?.muted(Clock.System.now().toEpochMilliseconds()) == true
                        DropdownMenuItem(
                            leadingIcon = { Icon(if (muted) QMusicIcons.Bell else QMusicIcons.VolumeMute, null, tint = Ink) },
                            text = { Text(if (muted) s.unmute else s.mute, color = TextPrimary) },
                            onClick = { menu = false; if (muted) controller.setMuted(other, 0L) else muteDialog = true },
                        )
                        if (!isClaude) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Timer, null, tint = Ink) },
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
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Queue, null, tint = Ink) },
                                text = { Text(ChatMusic.words().sharedList, color = TextPrimary) },
                                onClick = { menu = false; sharedListOpen = true },
                            )
                        }
                        thread?.let { t ->
                            DropdownMenuItem(
                                text = { Text(if (t.locked) s.unlockChat else s.lockChat, color = TextPrimary) },
                                onClick = { menu = false; controller.setLocked(t, !t.locked) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(s.exportChat, color = TextPrimary) },
                            onClick = {
                                menu = false
                                controller.reportInfo(s.exporting)
                                exportScope.launch {
                                    val body = controller.exportText(other, me, s)
                                    if (body.isNotEmpty()) shareTextFile("Nuvelo - " + title.take(40) + ".txt", body)
                                }
                            },
                        )
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
        if (pins.isNotEmpty() && !searching) {
            var pinIndex by remember(other.uid) { mutableStateOf(0) }
            val pin = pins[pinIndex.coerceIn(0, pins.lastIndex)]
            Row(
                Modifier.fillMaxWidth().background(DarkSurface).clickable {
                    jumpTo = pin.messageId
                    pinIndex = (pinIndex + 1) % pins.size
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One bar per pin, the shown one bright.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    pins.indices.forEach { i ->
                        Box(Modifier.width(3.dp).height((24 / pins.size).dp).background(if (i == pinIndex.coerceIn(0, pins.lastIndex)) Ink else TextMuted))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Icon(QMusicIcons.Pin, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.pinnedMessages + if (pins.size > 1) " · ${pinIndex.coerceIn(0, pins.lastIndex) + 1}/${pins.size}" else "", color = TextSecondary, fontSize = 11.sp)
                    Text(pin.text, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
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
                IconButton(enabled = matchIndex < matches.size - 1, onClick = { matchIndex++ }) { Icon(QMusicIcons.ChevronUp, null, tint = Ink) }
                IconButton(enabled = matchIndex > 0, onClick = { matchIndex-- }) { Icon(QMusicIcons.ChevronDown, null, tint = Ink) }
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
                Centered { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            } else {
                // A new message: ours always comes into view; theirs only when we are at the bottom already (reading
                // further up, the place is kept).
                val newest = list.lastOrNull()
                var newBelow by remember { mutableStateOf(0) }
                LaunchedEffect(newest?.id) {
                    if (newest != null) {
                        val before = lastHeard
                        lastHeard = newest.id
                        if (before != null && before != newest.id && !newest.system) {
                            sounds?.play(if (newest.from == me.uid) SoundKind.SEND else SoundKind.RECEIVE)
                        }
                    }
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
                val wallpaperIndex by controller.wallpaper.collectAsState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(ChatWallpapers.getOrNull(wallpaperIndex) ?: Color.Transparent),
                    reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(shown, key = { _, it -> it.key }) { index, row ->
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
                                    onForward = if (m.viewOnce) null else ({ forwarding = listOf(m) }),
                                    pinned = pins.any { it.messageId == m.id },
                                    onPin = if (canPin && !m.deleted && !m.pending) ({ controller.pin(other, m, quoteText(m), pins.none { it.messageId == m.id }) }) else null,
                                    openedOnce = if (mine) openedMap[m.id].orEmpty().any { it != me.uid } else me.uid in openedMap[m.id].orEmpty(),
                                    onOpenOnce = { viewOnceOpen = m },
                                    mentionsMe = me.uid in m.mentions,
                                    onEdit = if (mine && !m.deleted && m.attachment == null && m.images.isEmpty() && m.share == null && m.text.isNotEmpty() && !m.pending) {
                                        { editing = m; text = m.text; replyTo = null }
                                    } else null,
                                    onStar = { controller.setStarred(other, m, if (mine) me.name else memberNames[m.from] ?: other.name, title, m.id !in starredIds) },
                                    onInfo = if (mine && !m.deleted) ({ infoFor = m }) else null,
                                    onQuoteTap = { id ->
                                        if (id.startsWith("status:")) {
                                            val owner = id.removePrefix("status:")
                                            val mine = owner == me.uid
                                            val there = if (mine) controller.myStatuses.value.isNotEmpty() else controller.friendStatuses.value.any { it.owner.uid == owner }
                                            if (there) controller.push(ChatRoute.StatusViewer(owner, mine)) else controller.reportInfo(s.statusGone)
                                        } else {
                                            jumpTo = id
                                        }
                                    },
                                    loadPreview = { url -> controller.linkPreview(url) },
                                    pollVotes = pollVotes[m.id].orEmpty(),
                                    onVote = { picks -> controller.vote(other, m, picks) },
                                    onShowVoters = { votersFor = m },
                                    onSaveContact = { c -> saveContact(c) },
                                    onShowReactions = { reactionsFor = m },
                                    s = s,
                                    otherName = title,
                                    names = memberNames,
                                    senderName = if (isGroup && !mine) (memberNames[row.m.from] ?: if (row.m.from == CLAUDE_BOT_UID) "Claude" else "?") else null,
                                    // In a group, others' messages carry their picture, on the first of a run of theirs
                                    // (the list is newest first: the one above is the next item).
                                    avatar = if (isGroup && !mine) (memberUsers[row.m.from] ?: ChatUser(row.m.from, memberNames[row.m.from] ?: if (row.m.from == CLAUDE_BOT_UID) "Claude" else "?", "")) else null,
                                    firstOfRun = (shown.getOrNull(index + 1) as? ListRow.Message)?.m?.let { above -> above.from != row.m.from || above.system } ?: true,
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
                // Reading further up: a button back to the newest, with how many came meanwhile.
                val away by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 3 } }
                if (!away) newBelow = 0
                if (away) {
                    val downScope = androidx.compose.runtime.rememberCoroutineScope()
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(14.dp).size(44.dp).clip(CircleShape).background(DarkSurfaceVariant)
                            .clickable { downScope.launch { listState.animateScrollToItem(0) }; newBelow = 0 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(QMusicIcons.ChevronDown, contentDescription = s.scrollDown, tint = Ink)
                        if (newBelow > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).height(18.dp).widthIn(min = 18.dp).clip(CircleShape).background(TickRead).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (newBelow > 99) "99+" else newBelow.toString(), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        if (isBlocked) {
            Text(s.blockedNote, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else if (writeLocked) {
            Text(s.onlyAdminsCanSend, color = TextSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            // "@" at the end of the box in a group: its members to name.
            val typedMention = if (isGroup) Regex("(^|\\s)@([^\\s@]*)$").find(text)?.groupValues?.get(2) else null
            if (typedMention != null) {
                val people = group?.members.orEmpty().map { it.user }.filter { it.uid != me.uid && it.name.contains(typedMention, ignoreCase = true) }.take(6)
                if (people.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).background(DarkSurfaceVariant)) {
                        people.forEach { person ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val name = person.name.substringBefore(' ')
                                    text = text.dropLast(typedMention.length) + name + " "
                                    mentionNames = mentionNames + (person.uid to name)
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChatAvatar(person, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(person.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
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
                    Box(Modifier.width(3.dp).height(36.dp).background(Ink))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (q.from == me.uid) s.you else memberNames[q.from] ?: nameOf(q.from, me.uid, title, s), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(ChatPreview.plain(quoteText(q), s.english), color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    RecordingBar(elapsed, level, if (locked) s.voiceLocked else s.voiceSlideCancel + "   " + s.voiceSlideLock, cancelling, Modifier.weight(1f))
                } else {
                    Box {
                        IconButton(onClick = { attach = true }) { Icon(QMusicIcons.Add, contentDescription = null, tint = Ink) }
                        DropdownMenu(expanded = attach, onDismissRequest = { attach = false }, containerColor = DarkSurfaceVariant) {
                            val options = remember(attach) { if (attach) shareables() else emptyList() }
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Add, null, tint = Ink) },
                                text = { Text(s.photo, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickPhoto() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Camera, null, tint = Ink) },
                                text = { Text(s.camera, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; takePhoto() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.VideoPlay, null, tint = Ink) },
                                text = { Text(s.videoAttach, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickVideo() },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.File, null, tint = Ink) },
                                text = { Text(s.fileAttach, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickFile() },
                            )
                            if (!isClaude) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(QMusicIcons.Poll, null, tint = Ink) },
                                    text = { Text(s.poll, color = TextPrimary, fontSize = 14.sp) },
                                    onClick = { attach = false; pollDialog = true },
                                )
                            }
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Location, null, tint = Ink) },
                                text = { Text(s.location, color = TextPrimary, fontSize = 14.sp) },
                                onClick = {
                                    attach = false
                                    controller.reportInfo(s.locationFinding)
                                    pickLocation()
                                },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(QMusicIcons.Contact, null, tint = Ink) },
                                text = { Text(s.contact, color = TextPrimary, fontSize = 14.sp) },
                                onClick = { attach = false; pickContact() },
                            )
                            options.forEach { item ->
                                DropdownMenuItem(
                                    leadingIcon = { Icon(if (item.kind == ShareKind.SONG) QMusicIcons.Headphones else QMusicIcons.VideoPlay, null, tint = Ink) },
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
                        ) { Icon(if (emojiOpen) QMusicIcons.ChevronDown else QMusicIcons.Mood, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp)) }
                    Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                        if (text.isEmpty()) Text(s.messageHint, color = TextMuted, fontSize = 16.sp)
                        BasicTextField(
                            value = text,
                            onValueChange = { v ->
                                // A key's click for each letter typed or deleted (not when the text is set at once).
                                if (kotlin.math.abs(v.length - text.length) in 1..2) sounds?.playKey()
                                text = v.take(ChatController.MAX_LENGTH)
                                if (v.isNotBlank()) controller.onTyping(other, Clock.System.now().toEpochMilliseconds()) else controller.stopTyping(other)
                            },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(Ink),
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
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Ink).clickable {
                            recordingSince = null
                            locked = false
                            recorder?.stop()?.let { voice ->
                                sounds?.playSmall(SmallSound.REC_SENT)
                                controller.sendFile(other, voice, "", quoted())
                                replyTo = null
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(QMusicIcons.Send, contentDescription = null, tint = InkInverse, modifier = Modifier.size(20.dp)) }
                } else if (!canSend && recorder != null && !isClaude && editing == null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val cancelAt = with(density) { 110.dp.toPx() }
                    val lockAt = with(density) { 90.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .size(if (recordingSince != null) 56.dp else 44.dp)
                            .clip(CircleShape)
                            .background(if (recordingSince != null) (if (cancelling) Color(0xFF555555) else Color(0xFFFF4D4D)) else Ink)
                            .pointerInput(micReady) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    if (!micReady) {
                                        askMic(false) { granted -> micReady = granted }
                                        return@awaitEachGesture
                                    }
                                    sounds?.playSmall(SmallSound.REC_START)
                                    if (!recorder.start()) return@awaitEachGesture
                                    controller.setRecording(other, true)
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
                                        controller.setRecording(other, false)
                                    } else {
                                        val voice = recorder.stop()
                                        controller.setRecording(other, false)
                                        if (voice != null) {
                                            sounds?.playSmall(SmallSound.REC_SENT)
                                            controller.sendFile(other, voice, "", quoted())
                                            replyTo = null
                                        } else if (recorder.lastSilent) {
                                            controller.reportError(s.micSilent)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(QMusicIcons.Mic, contentDescription = s.voiceHold, tint = if (recordingSince != null) Ink else InkInverse, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSend) Ink else DarkSurfaceVariant)
                            .clickable(enabled = canSend) {
                                val e = editing
                                if (e != null) {
                                    if (text.trim() != e.text) controller.edit(other, e, text)
                                    editing = null
                                    text = ""
                                } else if (controller.send(other, text, replyTo = quoted(), mentions = mentionNames.filter { (_, name) -> text.contains("@$name") }.keys)) {
                                    text = ""
                                    unreadLineOn = false
                                    mentionNames = emptyMap()
                                    replyTo = null
                                    controller.stopTyping(other)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(QMusicIcons.Send, contentDescription = null, tint = if (canSend) InkInverse else TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (emojiOpen && !isBlocked && !writeLocked) {
            MediaKeyboard(
                controller, s,
                onEmoji = { e -> text = (text + e).take(ChatController.MAX_LENGTH) },
                onGif = { item, sticker ->
                    controller.sendGif(other, item, sticker, quoted())
                    replyTo = null
                    emojiOpen = false
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
    viewing?.let { (images, index) ->
        PhotoViewer(images, index, load = { image -> controller.imageBytes(other, image) }) { viewing = null }
    }
    viewOnceOpen?.let { m ->
        // Shown once: closing it marks it opened, and it does not open again.
        PhotoViewer(m.images.take(1), 0, load = { image -> controller.imageBytes(other, image) }) {
            if (m.from != me.uid) controller.markOpened(other, m)
            viewOnceOpen = null
        }
    }
    videoOpen?.let { url -> VideoViewer(url) { videoOpen = null } }
    pendingPhotos?.let { photos ->
        SendPhotosPage(
            photos = photos,
            s = s,
            caption = text,
            onSend = { list, caption ->
                controller.sendImages(other, list, caption, quoted(), viewOnce = sendViewOnce && list.size == 1)
                sendViewOnce = false
                text = ""
                replyTo = null
                pendingPhotos = null
            },
            onCancel = { pendingPhotos = null; sendViewOnce = false },
            viewOnce = sendViewOnce,
            onViewOnce = { on ->
                sendViewOnce = on
                if (on) controller.reportInfo(s.viewOnceNote)
            },
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
    if (pollDialog) PollDialog(s, onSend = { poll -> controller.sendPoll(other, poll) }) { pollDialog = false }
    // A person's name in this conversation (us, the other, or a group member).
    val nameOfUid: (String) -> String = { uid ->
        when (uid) {
            me.uid -> s.you
            CLAUDE_BOT_UID -> "Claude"
            else -> memberNames[uid] ?: if (uid == other.uid) other.name else "?"
        }
    }
    votersFor?.let { m ->
        m.poll?.let { poll -> VotersDialog(poll, pollVotes[m.id].orEmpty(), nameOfUid, s) { votersFor = null } }
    }
    reactionsFor?.let { m ->
        val users = group?.members?.associate { it.user.uid to it.user }.orEmpty()
        ReactionsDialog(
            reactions = reactions[m.id].orEmpty(),
            myUid = me.uid,
            nameOf = nameOfUid,
            userOf = { uid -> users[uid] ?: if (uid == me.uid) me else if (uid == other.uid) other else ChatUser(uid, nameOfUid(uid), "") },
            s = s,
            onRemoveMine = { controller.react(other, m, null) },
        ) { reactionsFor = null }
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
    if (sharedListOpen && chatId != null) {
        SharedListPage(controller, s, me, chatId, title) { sharedListOpen = false }
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
    // Not loaded by itself (the auto-download setting): a tap loads it.
    var waiting by remember(image.id, image.url) { mutableStateOf(true) }
    var tries by remember(image.id, image.url) { mutableStateOf(0) }
    val force = LocalForceImage.current
    LaunchedEffect(image.id, image.url, tries) {
        waiting = true
        bytes = load(image)
        waiting = false
    }
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
            if (waiting) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).align(Alignment.Center))
            } else {
                Box(
                    Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable {
                        force(image)
                        tries++
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(QMusicIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
        }
        if (scanning) PhotoScan(Modifier.matchParentSize())
    }
}

private val ClaudeBubble: Color get() = if (com.qmusic.ui.theme.lightTheme) Color(0xFFFBEDE7) else Color(0xFF2B201C)

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
            drawLine(Ink, p, androidx.compose.ui.geometry.Offset(p.x + l * d.first, p.y), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Ink, p, androidx.compose.ui.geometry.Offset(p.x, p.y + l * d.second), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
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
            codeBackground = if (com.qmusic.ui.theme.lightTheme) Color(0xFFF6EEEA) else Color(0xFF15100E),
            inlineCodeBackground = if (com.qmusic.ui.theme.lightTheme) Color(0xFFF6EEEA) else Color(0xFF15100E),
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
            .background(if (primary) Ink else DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = if (primary) InkInverse else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
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
            IconButton(onClick = { controller.openNewChat() }) { Icon(QMusicIcons.Search, contentDescription = s.findFriends, tint = Ink) }
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
                    IconButton(onClick = { controller.openConversation(user) }) { Icon(QMusicIcons.Chat, contentDescription = s.message, tint = Ink) }
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
                    UserBadge(user.uid, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
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
                        // What they play now, and their profile song.
                        val playingNow = rememberListening("profile", setOf(user.uid))[user.uid]
                        if (playingNow != null) {
                            Spacer(Modifier.height(10.dp))
                            ListeningLine(playingNow) { controller.openShare(playingNow) }
                        }
                        Spacer(Modifier.height(8.dp))
                        ProfileSongCard(user.uid) { controller.openShare(it) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FriendButton(controller, user, s)
                            if (friendStatus == FriendStatus.FRIENDS) Pill(s.removeFriend, primary = false) { controller.unfriend(user) }
                            Pill(if (isBlocked) s.unblock else s.block, primary = false) { controller.setBlocked(user, !isBlocked) }
                        }
                    }
                }
            }
            // Their own ringtone for Qeyf calls (a song, or one of Qeyf's sounds).
            if (isFounder(me.uid) && !isClaude && user.uid != me.uid) item { DeveloperBadgeSwitch(controller, user) }
            if (!isClaude && user.uid != me.uid) item { PersonRingRow(s, user) }
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
            Box(Modifier.fillMaxSize().background(InkInverse).clickable { bigPhoto = false }, contentAlignment = Alignment.Center) {
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
            Icon(icon, contentDescription = label, tint = Ink, modifier = Modifier.size(22.dp))
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
private fun withDays(list: List<ChatMessage>, unreadAfter: Long = Long.MAX_VALUE, myUid: String = "", openedAt: Long = Long.MAX_VALUE): List<ListRow> {
    val out = ArrayList<ListRow>(list.size + 5)
    var day: kotlinx.datetime.LocalDate? = null
    val firstUnread = list.indexOfFirst { it.at > unreadAfter && it.from != myUid && !it.system }
    for ((i, m) in list.withIndex()) {
        val d = local(m.at).date
        if (d != day) {
            out += ListRow.Day(m.at)
            day = d
        }
        // Only what others wrote before the chat was opened counts (not one's own, not what came while reading).
        if (i == firstUnread) out += ListRow.Unread(list.drop(i).count { it.from != myUid && !it.system && it.at <= openedAt })
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
    pollVotes: Map<String, List<Int>> = emptyMap(),
    onVote: (List<Int>) -> Unit = {},
    onShowVoters: () -> Unit = {},
    onSaveContact: (ChatContact) -> Unit = {},
    onShowReactions: () -> Unit = {},
    pinned: Boolean = false,
    onPin: (() -> Unit)? = null,
    /** A view-once photo: opened (by the other side, on the sender's phone; by this person, on theirs). */
    openedOnce: Boolean = false,
    onOpenOnce: () -> Unit = {},
    mentionsMe: Boolean = false,
    /** In a group, the sender (their picture beside the bubble); null for one's own and in a chat of two. */
    avatar: ChatUser? = null,
    /** The first of a run of the same sender's messages: it has the picture, the name and the tail. */
    firstOfRun: Boolean = true,
) {
    if (m.system) {
        SystemLine(m.text)
        return
    }
    var menu by remember { mutableStateOf(false) }
    // One to three emoji alone: big, without a bubble, as people expect.
    val bigEmoji = !m.deleted && m.images.isEmpty() && m.attachment == null && m.share == null && m.replyTo == null && m.poll == null && m.location == null && m.contact == null &&
        (isBigEmoji(m.text) || (m.gif?.sticker == true && m.text.isBlank()))
    val hasCard = m.share != null || m.images.isNotEmpty() || m.attachment != null || m.poll != null || m.location != null || m.contact != null
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
            .background(if (selected) Ink.copy(alpha = 0.10f) else Color.Transparent)
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
                tint = Ink.copy(alpha = (swipe.value / threshold).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = 180f },
            )
        }
        // In a group: the picture, then a tail from the bubble's top corner towards it (the first of a run).
        val tailed = avatar != null && firstOfRun && !bigEmoji
        val bubbleShape = if (avatar != null) {
            RoundedCornerShape(topStart = if (tailed) 0.dp else 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp)
        }
        val bubbleColor = if (bigEmoji) Color.Transparent else if (m.deleted) DarkSurface else if (mine) Ink else if (fromClaude) ClaudeBubble else DarkSurfaceVariant
        Row(Modifier.offset { androidx.compose.ui.unit.IntOffset(swipe.value.roundToInt(), 0) }, verticalAlignment = Alignment.Top) {
        if (avatar != null) {
            if (firstOfRun) ChatAvatar(avatar, 30.dp) else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(2.dp))
            if (tailed) {
                androidx.compose.foundation.Canvas(Modifier.size(width = 7.dp, height = 12.dp)) {
                    drawPath(
                        androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(0f, 0f)
                            quadraticBezierTo(size.width * 0.55f, size.height * 0.35f, size.width, size.height)
                            close()
                        },
                        bubbleColor,
                    )
                }
            } else {
                Spacer(Modifier.width(7.dp))
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .then(
                    if (highlight) {
                        Modifier.border(2.dp, ClaudeCoral, bubbleShape)
                    } else if (assisting != null) {
                        Modifier.border(2.dp, ClaudeCoral.copy(alpha = pulse), bubbleShape)
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() else m.share?.let(onOpenShare) },
                    onLongClick = { if (selecting) onToggleSelect() else menu = true },
                    // Two quick taps: a heart (again: taken back).
                    onDoubleClick = if (selecting || m.deleted || m.pending) null else ({ onReact(if (reactions[myUid] == REACTIONS.first()) null else REACTIONS.first()) }),
                )
                .padding(if (hasCard) 4.dp else 0.dp),
        ) {
            val fg = if (mine && !m.deleted && !bigEmoji) InkInverse else TextPrimary
            val fgSoft = if (mine && !m.deleted && !bigEmoji) InkInverse.copy(alpha = 0.55f) else TextSecondary
            if (m.forwarded && !m.deleted) {
                Text(s.forwarded, color = fgSoft, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp))
            }
            if (senderName != null && firstOfRun) {
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
                    s.deletedMessage,
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
                        .background(if (mine) InkInverse.copy(alpha = 0.07f) else Ink.copy(alpha = 0.07f))
                        .clickable(enabled = !selecting) { onQuoteTap(if (quoted.id.startsWith("status_")) "status:" + quoted.from else quoted.id) }
                        .padding(end = 10.dp),
                ) {
                    Box(Modifier.width(3.dp).height(40.dp).background(if (mine) InkInverse else Ink))
                    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(
                            if (quoted.from == myUid) s.you else names[quoted.from] ?: nameOf(quoted.from, myUid, otherName, s),
                            color = if (mine) InkInverse else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(ChatPreview.plain(quoted.text, s.english), color = fgSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (quoted.thumb.isNotEmpty()) QuoteThumb(quoted.thumb)
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
            if (!m.deleted) m.gif?.let { g -> GifView(g) }
            if (m.images.isNotEmpty() && m.viewOnce && !m.deleted) {
                ViewOnceCard(mine, openedOnce, s, onOpenOnce)
            } else if (m.images.isNotEmpty()) {
                ChatAlbum(m.images, loadImage, { index -> onOpenImages(m.images, index) }, scanning = assisting != null)
                m.progress?.let { p ->
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { p },
                        color = if (mine) InkInverse else Ink,
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
            if (!m.deleted) {
                m.poll?.let { poll -> PollCard(poll, pollVotes, myUid, mine, s, onVote = onVote, onShowVoters = onShowVoters) }
                m.location?.let { place -> LocationCard(place, mine, s) }
                m.contact?.let { c -> ContactCard(c, mine, s) { onSaveContact(c) } }
            }
            m.share?.let { ShareCard(it, mine, s) }
            if (m.together && m.share != null && !mine) {
                Box(Modifier.padding(start = 8.dp, top = 8.dp)) { Pill(s.join, primary = true, onClick = onJoin) }
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
                if (m.expireAt > 0) Icon(QMusicIcons.Timer, contentDescription = null, tint = fgSoft, modifier = Modifier.padding(end = 3.dp).size(12.dp))
                if (starred) Icon(QMusicIcons.Star, contentDescription = null, tint = fgSoft, modifier = Modifier.padding(end = 3.dp).size(12.dp))
                if (m.edited && !m.deleted) Text(s.edited + "  ", color = fgSoft, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Text(clock(m.at), color = fgSoft, fontSize = 11.sp)
                if (mine && !m.deleted) {
                    Spacer(Modifier.width(4.dp))
                    // Waiting (no connection): a clock; sent: one grey tick; on their phone: two grey; read: two green.
                    MessageTick(
                        when {
                            m.pending -> TickState.WAITING
                            read -> TickState.READ
                            delivered -> TickState.DELIVERED
                            else -> TickState.SENT
                        },
                        fgSoft,
                    )
                }
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
                            .background(if (chosen) Ink.copy(alpha = 0.18f) else Color.Transparent)
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
            if (onForward != null && !m.deleted && m.poll == null) DropdownMenuItem(text = { Text(s.forward, color = TextPrimary) }, onClick = { menu = false; onForward() })
            if (onEdit != null) DropdownMenuItem(text = { Text(s.edit, color = TextPrimary) }, onClick = { menu = false; onEdit() })
            if (!m.deleted && !m.viewOnce) DropdownMenuItem(text = { Text(if (starred) s.unstar else s.star, color = TextPrimary) }, onClick = { menu = false; onStar() })
            if (onPin != null) DropdownMenuItem(text = { Text(if (pinned) s.unpinMessage else s.pinMessage, color = TextPrimary) }, onClick = { menu = false; onPin() })
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
                    .clickable(onClick = onShowReactions)
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
        m.poll != null -> "📊 " + m.poll.question
        m.location != null -> "📍"
        m.contact != null -> "👤 " + m.contact.name
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
    if (item.quote.isNotEmpty()) {
        // A line of the lyrics: the words big, the song under them.
        Column(
            Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Color(0xFFEDEDED) else DarkSurface).padding(12.dp),
        ) {
            val fg = if (mine) InkInverse else TextPrimary
            val soft = if (mine) InkInverse.copy(alpha = 0.55f) else TextSecondary
            Text(ChatMusic.words().lyricLine, color = soft, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "“" + item.quote + "”",
                color = fg,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(InkInverse), contentAlignment = Alignment.Center) {
                    if (item.thumbnail != null) AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Icon(QMusicIcons.Play, contentDescription = null, tint = Ink, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(item.subtitle, if (item.startMs > 0) secondsLabel(item.startMs) else "").filter { it.isNotBlank() }.joinToString(" · "),
                        color = soft,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }
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
                .background(InkInverse),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnail != null) AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(26.dp).clip(CircleShape).background(InkInverse.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.Play, contentDescription = null, tint = Ink, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val fg = if (mine) InkInverse else TextPrimary
            val soft = if (mine) InkInverse.copy(alpha = 0.55f) else TextSecondary
            Text(if (video) s.video else s.song, color = soft, fontSize = 11.sp)
            Text(item.title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.startMs > 0) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(QMusicIcons.Timer, contentDescription = null, tint = soft, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(ChatMusic.words().startsAt(secondsLabel(item.startMs)), color = soft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
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

/** A view-once photo in its bubble: nothing of it shows; a tap opens it once (for whoever got it). */
@Composable
private fun ViewOnceCard(mine: Boolean, opened: Boolean, s: ChatStrings, onOpen: () -> Unit) {
    val canOpen = !mine && !opened
    Row(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(12.dp))
            .background(if (mine) InkInverse.copy(alpha = 0.07f) else Ink.copy(alpha = 0.07f))
            .clickable(enabled = canOpen, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (mine) InkInverse else TextPrimary
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (opened) Color.Transparent else fg.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text("1", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(s.viewOncePhoto, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    opened -> s.viewOnceOpened
                    mine -> s.viewOnce
                    else -> s.viewOnceTap
                },
                color = fg.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}

/** Where a message is: not sent yet (no connection), on the server, on their phone, read. */
internal enum class TickState { WAITING, SENT, DELIVERED, READ }

/**
 * A message's state, drawn: a small clock while it waits for a connection, one grey tick once the server has it, two
 * grey ticks once it is on their phone, two green ticks once they read it.
 */
@Composable
internal fun MessageTick(state: TickState, soft: Color) {
    val colour = when (state) {
        TickState.READ -> TickRead
        TickState.DELIVERED -> soft.copy(alpha = 0.85f)
        else -> soft
    }
    androidx.compose.foundation.Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun tick(dx: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(dx + w * 0.04f, h * 0.55f)
                lineTo(dx + w * 0.22f, h * 0.82f)
                lineTo(dx + w * 0.58f, h * 0.18f)
            }
            drawPath(path, colour, style = stroke)
        }
        when (state) {
            TickState.WAITING -> {
                val r = h * 0.42f
                val c = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)
                drawCircle(colour, r, c, style = stroke)
                drawLine(colour, c, androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.6f), strokeWidth = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(colour, c, androidx.compose.ui.geometry.Offset(c.x + r * 0.45f, c.y), strokeWidth = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            TickState.SENT -> tick(w * 0.2f)
            else -> {
                tick(0f)
                tick(w * 0.36f)
            }
        }
    }
}

/** The tiny picture of a quoted status. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun QuoteThumb(thumb: String) {
    val bytes = remember(thumb) { runCatching { kotlin.io.encoding.Base64.decode(thumb) }.getOrNull() } ?: return
    AsyncImage(
        model = bytes,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).size(40.dp).clip(RoundedCornerShape(6.dp)),
    )
}

/** "15.11.2026 14:03": a line's time in an exported conversation. */
internal fun exportTime(ms: Long): String = local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}.${it.year} ${two(it.hour)}:${two(it.minute)}" }

internal fun listTime(ms: Long, s: ChatStrings): String = when (daysAgo(ms)) {
    0 -> clock(ms)
    1 -> s.yesterday
    else -> local(ms).let { "${two(it.dayOfMonth)}.${two(it.monthNumber)}" }
}
