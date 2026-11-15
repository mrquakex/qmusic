package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.datetime.Clock

/** The colours a text status can stand on (dark, so white text reads on all of them). */
internal val StatusColors = listOf(
    0xFF1E1E1E, 0xFF3A3A3A, 0xFF5B2A86, 0xFF1F4E79, 0xFF0F5E4F, 0xFF7A2E2E, 0xFF8A4B08, 0xFF243B55, 0xFF6A1B4D, 0xFF2E4B2E,
)

/** How long a photo or a text stays on screen. */
private const val STILL_MS = 5_000L

/** "Just now", "12 min ago", "3 h ago". */
internal fun agoText(at: Long, now: Long, s: ChatStrings): String {
    val min = ((now - at) / 60_000L).coerceAtLeast(0)
    return when {
        min < 1 -> s.justNow
        min < 60 -> "$min ${s.minutesAgo}"
        min < 24 * 60 -> "${min / 60} ${s.hoursAgo}"
        else -> listTime(at, s)
    }
}

// ------------------------------------------------------------------ the tab

@Composable
internal fun StatusTab(controller: ChatController, s: ChatStrings, me: ChatUser, onExit: () -> Unit) {
    val mine by controller.myStatuses.collectAsState()
    val friends by controller.friendStatuses.collectAsState()
    val posting by controller.posting.collectAsState()
    val now = rememberNow()
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<PickedImage?>(null) }
    var pendingVideo by remember { mutableStateOf<PickedFile?>(null) }
    val camera = rememberCamera { shot -> if (shot != null) pendingImage = shot }
    val gallery = rememberImagePicker { photos -> photos.firstOrNull()?.let { pendingImage = it } }
    val video = rememberFilePicker(video = true) { file -> if (file != null) pendingVideo = file }
    val unseen = friends.filter { !it.seenAll }
    val seen = friends.filter { it.seenAll }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            item(key = "title") {
                HomeTitle(s.tabStatus, onExit) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                            DropdownMenuItem(text = { Text(s.statusPrivacyTitle, color = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.StatusPrivacyPage) })
                        }
                    }
                }
            }
            item(key = "mine") {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (mine.isNotEmpty()) controller.push(ChatRoute.StatusViewer(me.uid, mine = true)) else addMenu = true
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        StatusRinged(if (mine.isNotEmpty()) RingSeen else null) { ChatAvatar(me, if (mine.isNotEmpty()) 50.dp else 56.dp) }
                        if (mine.isEmpty()) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(BgRoot).padding(2.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) { Icon(QMusicIcons.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.myStatus, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (mine.isEmpty()) s.addStatus else agoText(mine.last().at, now, s),
                            color = TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    if (mine.isNotEmpty()) {
                        IconButton(onClick = { addMenu = true }) { Icon(QMusicIcons.Add, contentDescription = s.addStatus, tint = Color.White) }
                    }
                }
            }
            if (posting) {
                item(key = "posting") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.statusPosting, color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
            if (unseen.isNotEmpty()) item(key = "recentHead") { SectionTitle(s.recentUpdates) }
            items(unseen, key = { "u_" + it.owner.uid }) { g -> StatusRow(g, RingUnseen, now, s) { controller.push(ChatRoute.StatusViewer(g.owner.uid, mine = false)) } }
            if (seen.isNotEmpty()) item(key = "seenHead") { SectionTitle(s.viewedUpdates) }
            items(seen, key = { "s_" + it.owner.uid }) { g -> StatusRow(g, RingSeen, now, s) { controller.push(ChatRoute.StatusViewer(g.owner.uid, mine = false)) } }
            if (friends.isEmpty()) {
                item(key = "empty") {
                    Text(s.noStatuses, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp))
                }
            }
        }
        // A small pen for a text status over the camera.
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(end = 16.dp).size(44.dp).clip(RoundedCornerShape(14.dp)).background(DarkSurfaceVariant).clickable { controller.push(ChatRoute.TextStatus) },
                contentAlignment = Alignment.Center,
            ) { Icon(QMusicIcons.Edit, contentDescription = s.statusText, tint = Color.White, modifier = Modifier.size(20.dp)) }
            HomeFab(QMusicIcons.Camera, s.camera) { addMenu = true }
        }
    }
    if (addMenu) {
        ChoiceDialog(
            s.addStatus,
            listOf(
                s.camera to { camera() },
                s.statusPhoto to { gallery() },
                s.statusVideo to { video() },
                s.statusText to { controller.push(ChatRoute.TextStatus) },
            ),
            s,
        ) { addMenu = false }
    }
    pendingImage?.let { img ->
        StatusMediaComposer(s, image = img, video = null, onCancel = { pendingImage = null }) { caption ->
            controller.postPhotoStatus(img, caption)
            pendingImage = null
        }
    }
    pendingVideo?.let { file ->
        StatusMediaComposer(s, image = null, video = file, onCancel = { pendingVideo = null }) { caption ->
            controller.postVideoStatus(file, caption)
            pendingVideo = null
        }
    }
}

@Composable
private fun StatusRow(g: StatusGroup, ring: Color, now: Long, s: ChatStrings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusRinged(ring) { StatusPreview(g.items.last(), g.owner, 50.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.owner.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(agoText(g.items.last().at, now, s), color = TextSecondary, fontSize = 14.sp)
        }
    }
}

/** A status in small: the photo's or video's first frame, the text on its colour, or the person's picture. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun StatusPreview(item: StatusItem, owner: ChatUser, size: androidx.compose.ui.unit.Dp) {
    val thumb = remember(item.thumb) { runCatching { kotlin.io.encoding.Base64.decode(item.thumb) }.getOrNull() }
    when {
        item.kind == StatusKind.TEXT -> Box(Modifier.size(size).clip(CircleShape).background(Color(item.background)), contentAlignment = Alignment.Center) {
            Text(item.text, color = Color.White, fontSize = 7.sp, maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        }
        thumb != null || item.kind == StatusKind.PHOTO -> Box(Modifier.size(size).clip(CircleShape).background(DarkSurfaceVariant)) {
            if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.kind == StatusKind.PHOTO) AsyncImage(model = item.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        else -> ChatAvatar(owner, size)
    }
}

// ------------------------------------------------------------------ writing one

/** A text status: big white words on a colour; a tap on the palette changes it. */
@Composable
internal fun TextStatusPage(controller: ChatController, s: ChatStrings) {
    var text by remember { mutableStateOf("") }
    var colour by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val bg = Color(StatusColors[colour])
    Box(Modifier.fillMaxSize().background(bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.pop() }) { Icon(QMusicIcons.Close, contentDescription = s.cancel, tint = Color.White) }
            Spacer(Modifier.weight(1f))
            // The palette: the next colour.
            Box(
                Modifier.padding(8.dp).size(30.dp).clip(CircleShape).background(Color(StatusColors[(colour + 1) % StatusColors.size])).clickable { colour = (colour + 1) % StatusColors.size },
            )
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 96.dp), contentAlignment = Alignment.Center) {
            if (text.isEmpty()) Text(s.statusTypeHint, color = Color.White.copy(alpha = 0.55f), fontSize = 32.sp, textAlign = TextAlign.Center)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(700) },
                textStyle = TextStyle(color = Color.White, fontSize = if (text.length > 120) 24.sp else 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (text.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(56.dp).clip(CircleShape).background(Color.White)
                    .clickable {
                        controller.postTextStatus(text, StatusColors[colour])
                        controller.pop()
                        controller.selectTab(ChatTab.STATUS)
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(QMusicIcons.Send, contentDescription = s.statusShare, tint = Color.Black) }
        }
    }
}

/** A photo or a video before it goes up: shown full screen with a caption box. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
internal fun StatusMediaComposer(s: ChatStrings, image: PickedImage?, video: PickedFile?, onCancel: () -> Unit, onShare: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
            when {
                image != null -> AsyncImage(model = image.jpeg, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                video != null -> VideoMessagePlayer(video.location, Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp)) {
                IconButton(onClick = onCancel) { Icon(QMusicIcons.Close, contentDescription = s.cancel, tint = Color.White) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xCC1E1E1E)).padding(horizontal = 18.dp, vertical = 13.dp)) {
                    if (caption.isEmpty()) Text(s.statusCaption, color = TextMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = caption,
                        onValueChange = { caption = it.take(700) },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable { onShare(caption) }, contentAlignment = Alignment.Center) {
                    Icon(QMusicIcons.Send, contentDescription = s.statusShare, tint = Color.Black)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ watching

/**
 * Someone's statuses one after another, full screen: bars at the top fill as each plays, a tap on the right goes on,
 * on the left goes back, holding pauses. Their own: who saw it, and delete. Someone else's: an answer box (the answer
 * goes to the conversation with them); after their last, the next person's unseen ones follow.
 */
@Composable
internal fun StatusViewerPage(controller: ChatController, s: ChatStrings, me: ChatUser, ownerUid: String, mine: Boolean) {
    val myItems by controller.myStatuses.collectAsState()
    val groups by controller.friendStatuses.collectAsState()
    var owner by remember { mutableStateOf(ownerUid) }
    // The order of people is kept from the opening so marking one seen does not move the next ahead.
    val order = remember { groups.map { it.owner.uid } }
    val group = groups.firstOrNull { it.owner.uid == owner }
    val items = if (mine) myItems else group?.items.orEmpty()
    var index by remember(owner) { mutableIntStateOf(if (mine) 0 else group?.let { g -> g.items.indexOfFirst { it.id !in g.seen }.takeIf { it >= 0 } } ?: 0) }
    var held by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var viewersOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<StatusItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    var reply by remember(owner) { mutableStateOf("") }
    val paused = held || typing || viewersOpen || menu || confirmDelete != null
    PlatformBackHandler(enabled = true) { controller.pop() }

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { controller.pop() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val at = index.coerceIn(0, items.lastIndex)
    val item = items[at]
    val nextOwner: () -> Unit = {
        val i = order.indexOf(owner)
        val next = order.drop(i + 1).firstOrNull { uid -> groups.any { it.owner.uid == uid && !it.seenAll } }
        if (mine || next == null) controller.pop() else owner = next
    }
    val goNext: () -> Unit = { if (at < items.lastIndex) index = at + 1 else nextOwner() }
    val goBack: () -> Unit = { if (at > 0) index = at - 1 }
    LaunchedEffect(item.id) { if (!mine) controller.markStatusSeen(item) }

    // How far the current one has played (0..1); a video goes as long as it is.
    var progress by remember(item.id) { mutableFloatStateOf(0f) }
    var videoLength by remember(item.id) { mutableLongStateOf(if (item.kind == StatusKind.VIDEO) item.durationMs.coerceAtLeast(1_000L) else STILL_MS) }
    var videoReady by remember(item.id) { mutableStateOf(item.kind != StatusKind.VIDEO) }
    LaunchedEffect(item.id, paused, videoReady, videoLength) {
        if (paused || !videoReady) return@LaunchedEffect
        var last = Clock.System.now().toEpochMilliseconds()
        while (progress < 1f) {
            kotlinx.coroutines.delay(32)
            val t = Clock.System.now().toEpochMilliseconds()
            progress = (progress + (t - last).toFloat() / videoLength).coerceAtMost(1f)
            last = t
        }
        goNext()
    }

    val now = rememberNow()
    Box(Modifier.fillMaxSize().background(if (item.kind == StatusKind.TEXT) Color(item.background) else Color.Black).imePadding()) {
        // What is shown, with the taps and the hold over it.
        Box(
            Modifier.fillMaxSize().pointerInput(item.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val started = Clock.System.now().toEpochMilliseconds()
                    held = true
                    val up = waitForUpOrCancellation()
                    held = false
                    if (up != null && Clock.System.now().toEpochMilliseconds() - started < 250) {
                        if (down.position.x < size.width / 3f) goBack() else goNext()
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            when (item.kind) {
                StatusKind.TEXT -> Text(
                    item.text,
                    color = Color.White,
                    fontSize = if (item.text.length > 120) 24.sp else 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = if (item.text.length > 120) 32.sp else 42.sp,
                    modifier = Modifier.padding(28.dp),
                )
                StatusKind.PHOTO -> AsyncImage(model = item.url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                StatusKind.VIDEO -> StatusVideo(
                    url = item.url,
                    paused = paused,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { ms ->
                        if (ms > 0) videoLength = ms
                        videoReady = true
                    },
                )
            }
        }

        // The bars and who it is.
        Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f)).statusBarsPadding().padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { i, _ ->
                    val fill = when {
                        i < at -> 1f
                        i == at -> progress
                        else -> 0f
                    }
                    BoxWithConstraints(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxHeight().width(maxWidth * fill).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.pop() }) { Icon(QMusicIcons.Back, contentDescription = null, tint = Color.White) }
                val who = if (mine) me else item.owner
                Box(Modifier.clip(CircleShape).clickable { if (!mine) controller.openProfile(who) }) { ChatAvatar(who, 38.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mine) s.myStatus else who.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(agoText(item.at, now, s), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                if (mine) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                            DropdownMenuItem(text = { Text(s.statusDelete, color = TextPrimary) }, onClick = { menu = false; confirmDelete = item })
                            DropdownMenuItem(text = { Text(s.statusPrivacyTitle, color = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.StatusPrivacyPage) })
                        }
                    }
                }
            }
        }

        // The words under a photo or a video.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (item.kind != StatusKind.TEXT && item.text.isNotBlank()) {
                Text(
                    item.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            if (mine) {
                val viewers by remember(item.id) { controller.statusViewers(item) }.collectAsState(initial = emptyList())
                Row(
                    Modifier.align(Alignment.CenterHorizontally).padding(14.dp).clip(RoundedCornerShape(20.dp)).clickable { viewersOpen = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.ChevronUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${s.statusViews} ${viewers.size}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (viewersOpen) ViewersDialog(viewers, s, now) { viewersOpen = false }
            } else if (item.owner.uid != CLAUDE_BOT_UID) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x991E1E1E)).padding(horizontal = 18.dp, vertical = 13.dp),
                    ) {
                        if (reply.isEmpty()) Text(s.statusReplyHint, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                        BasicTextField(
                            value = reply,
                            onValueChange = { reply = it.take(2000) },
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                        )
                    }
                    if (reply.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color.White).clickable {
                                val quote = s.statusQuote + ": " + when (item.kind) {
                                    StatusKind.TEXT -> item.text.take(80)
                                    StatusKind.PHOTO -> item.text.ifBlank { s.statusPhoto }.take(80)
                                    StatusKind.VIDEO -> item.text.ifBlank { s.statusVideo }.take(80)
                                }
                                controller.replyToStatus(item, reply.trim(), quote)
                                reply = ""
                                controller.reportInfo(s.statusReplied)
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(QMusicIcons.Send, contentDescription = s.send, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { st ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.statusDelete, color = TextPrimary) },
            text = { Text(s.statusDeleteConfirm, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    controller.deleteStatus(st)
                    if (items.size <= 1) controller.pop() else if (at == items.lastIndex) index = at - 1
                }) { Text(s.delete, color = Color(0xFFFF5A5F)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

@Composable
private fun ViewersDialog(viewers: List<Pair<ChatUser, Long>>, s: ChatStrings, now: Long, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text("${s.statusViews} ${viewers.size}", color = TextPrimary) },
        text = {
            if (viewers.isEmpty()) {
                Text(s.statusNoViews, color = TextSecondary)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(viewers, key = { it.first.uid }) { (user, at) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChatAvatar(user, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(agoText(at, now, s), color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.closeLabel, color = TextPrimary) } },
    )
}

// ------------------------------------------------------------------ who sees it

/** All friends, all but some, or only some: the chosen ones are ticked from the friends list. */
@Composable
internal fun StatusPrivacyPage(controller: ChatController, s: ChatStrings) {
    val privacy by controller.statusPrivacy.collectAsState()
    val friends by controller.friends.collectAsState()
    val people = remember(friends) { friends.filter { it.uid != CLAUDE_BOT_UID }.sortedBy { it.name.lowercase() } }
    LazyColumn(Modifier.fillMaxSize().background(BgRoot), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar(s.statusPrivacyTitle, { controller.pop() }) }
        item { Text(s.statusPrivacyNote, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        item {
            listOf(
                StatusAudience.FRIENDS to s.audienceFriends,
                StatusAudience.EXCEPT to s.audienceExcept,
                StatusAudience.ONLY to s.audienceOnly,
            ).forEach { (audience, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { controller.setStatusPrivacy(privacy.copy(audience = audience)) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioDot(privacy.audience == audience)
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (privacy.audience == audience && audience != StatusAudience.FRIENDS) {
                        Text("${privacy.people.size} ${s.peopleSelected}", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        item { Text(s.audienceChanges, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (privacy.audience != StatusAudience.FRIENDS) {
            item { SectionTitle(s.friends) }
            items(people, key = { it.uid }) { user ->
                val on = user.uid in privacy.people
                Row(
                    Modifier.fillMaxWidth().clickable {
                        controller.setStatusPrivacy(privacy.copy(people = if (on) privacy.people - user.uid else privacy.people + user.uid))
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatAvatar(user, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(user.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) (if (privacy.audience == StatusAudience.EXCEPT) Color(0xFFFF5A5F) else Color.White) else DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(if (privacy.audience == StatusAudience.EXCEPT) QMusicIcons.Close else QMusicIcons.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (on) Color.White else TextMuted).padding(2.dp).clip(CircleShape).background(BgRoot).padding(4.dp).clip(CircleShape)
            .background(if (on) Color.White else BgRoot),
    )
}
