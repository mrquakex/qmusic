@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.qmusic.ui.chat

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.abs
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.gestures.detectDragGestures
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha

/** The colours a text status can stand on (dark, so white text reads on all of them). */
internal val StatusColors = listOf(
    0xFF1E1E1E, 0xFF3A3A3A, 0xFF5B2A86, 0xFF1F4E79, 0xFF0F5E4F, 0xFF7A2E2E, 0xFF8A4B08, 0xFF243B55, 0xFF6A1B4D, 0xFF2E4B2E,
)

/** The typefaces a text status can be written in. */
internal val StatusFonts = listOf(FontFamily.Default, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive, FontFamily.SansSerif)
internal val StatusFontWeights = listOf(FontWeight.SemiBold, FontWeight.Normal, FontWeight.Medium, FontWeight.Normal, FontWeight.Black)

/** How long a photo or a text with a song stays on screen. */
private const val SONG_MS = STATUS_SONG_LENGTH

/** How long a status waits for its song before going on in silence. */
private const val SONG_WAIT_MS = 6_000L

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
    val muted by controller.mutedStatuses.collectAsState()
    val unseen = friends.filter { !it.seenAll && it.owner.uid !in muted }
    val seen = friends.filter { it.seenAll && it.owner.uid !in muted }
    val quiet = friends.filter { it.owner.uid in muted }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            stickyHeader(key = "title") {
                HomeTitle(s.tabStatus, onExit) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Ink) }
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
                                Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(BgRoot).padding(2.dp).clip(CircleShape).background(Ink),
                                contentAlignment = Alignment.Center,
                            ) { Icon(QMusicIcons.Add, contentDescription = null, tint = InkInverse, modifier = Modifier.size(16.dp)) }
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
                        IconButton(onClick = { addMenu = true }) { Icon(QMusicIcons.Add, contentDescription = s.addStatus, tint = Ink) }
                    }
                }
            }
            if (posting) {
                item(key = "posting") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.statusPosting, color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
            if (unseen.isNotEmpty()) item(key = "recentHead") { SectionTitle(s.recentUpdates) }
            items(unseen, key = { "u_" + it.owner.uid }) { g -> StatusRow(g, RingUnseen, now, s, false, { controller.setStatusMuted(g.owner, true) }) { controller.push(ChatRoute.StatusViewer(g.owner.uid, mine = false)) } }
            if (seen.isNotEmpty()) item(key = "seenHead") { SectionTitle(s.viewedUpdates) }
            items(seen, key = { "s_" + it.owner.uid }) { g -> StatusRow(g, RingSeen, now, s, false, { controller.setStatusMuted(g.owner, true) }) { controller.push(ChatRoute.StatusViewer(g.owner.uid, mine = false)) } }
            if (quiet.isNotEmpty()) item(key = "mutedHead") { SectionTitle(s.mutedUpdates) }
            items(quiet, key = { "m_" + it.owner.uid }) { g -> StatusRow(g, RingSeen, now, s, true, { controller.setStatusMuted(g.owner, false) }) { controller.push(ChatRoute.StatusViewer(g.owner.uid, mine = false)) } }
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
            ) { Icon(QMusicIcons.Edit, contentDescription = s.statusText, tint = Ink, modifier = Modifier.size(20.dp)) }
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
        StatusMediaComposer(s, image = img, video = null, songs = controller.shareables, searchSongs = controller.searchSongs, onCancel = { pendingImage = null }) { caption, edited, extras ->
            controller.postPhotoStatus(edited ?: img, caption, extras)
            pendingImage = null
        }
    }
    pendingVideo?.let { file ->
        StatusMediaComposer(s, image = null, video = file, songs = controller.shareables, searchSongs = controller.searchSongs, onCancel = { pendingVideo = null }) { caption, _, extras ->
            controller.postVideoStatus(file, caption, extras)
            pendingVideo = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusRow(g: StatusGroup, ring: Color, now: Long, s: ChatStrings, muted: Boolean, onToggleMute: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
        DropdownMenuItem(text = { Text(if (muted) s.statusUnmute else s.statusMute, color = TextPrimary) }, onClick = { menu = false; onToggleMute() })
    }
    Row(
        Modifier.fillMaxWidth().rightClick { menu = true }.hoverLight().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 10.dp).then(if (muted) Modifier.graphicsLayerAlpha(0.55f) else Modifier),
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
}

private fun Modifier.graphicsLayerAlpha(a: Float): Modifier = this.alpha(a)

/** A status in small: the photo's or video's first frame, the text on its colour, or the person's picture. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun StatusPreview(item: StatusItem, owner: ChatUser, size: androidx.compose.ui.unit.Dp) {
    val thumb = remember(item.thumb) { runCatching { kotlin.io.encoding.Base64.decode(item.thumb) }.getOrNull() }
    when {
        item.kind == StatusKind.TEXT -> Box(Modifier.size(size).clip(CircleShape).background(Color(item.background)), contentAlignment = Alignment.Center) {
            Text(item.text, color = Color.White, fontSize = 7.sp, maxLines = 3, fontFamily = StatusFonts.getOrElse(item.font) { FontFamily.Default }, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
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
    var font by remember { mutableIntStateOf(0) }
    var emoji by remember { mutableStateOf(false) }
    var song by remember { mutableStateOf<SharedItem?>(null) }
    var picking by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val bg = Color(StatusColors[colour])
    Box(Modifier.fillMaxSize().background(bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.pop() }) { Icon(QMusicIcons.Close, contentDescription = s.cancel, tint = Color.White) }
            Spacer(Modifier.weight(1f))
            // A song, emoji, the typeface ("Aa" in the next one), and the palette (the next colour).
            IconButton(onClick = { picking = true }) { Icon(QMusicIcons.Headphones, contentDescription = s.statusAddSong, tint = Color.White) }
            IconButton(onClick = {
                emoji = !emoji
                if (emoji) keyboard?.hide() else runCatching { focus.requestFocus() }.also { keyboard?.show() }
            }) { Icon(QMusicIcons.Mood, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Text(
                "Aa",
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = StatusFonts[(font + 1) % StatusFonts.size],
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).clickable { font = (font + 1) % StatusFonts.size }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(
                Modifier.padding(8.dp).size(30.dp).clip(CircleShape).background(Color(StatusColors[(colour + 1) % StatusColors.size])).clickable { colour = (colour + 1) % StatusColors.size },
            )
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 96.dp), contentAlignment = Alignment.Center) {
            if (text.isEmpty()) Text(s.statusTypeHint, color = Color.White.copy(alpha = 0.55f), fontSize = 32.sp, textAlign = TextAlign.Center)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(700) },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = if (text.length > 120) 24.sp else 32.sp,
                    fontWeight = StatusFontWeights[font],
                    fontFamily = StatusFonts[font],
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        song?.let { g ->
            ComposerSongChip(g, s, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), onPart = { song = g.copy(startMs = it) }) { song = null }
        }
        if (picking) StatusSongPicker(s, controller.shareables().filter { it.kind == ShareKind.SONG }, controller.searchSongs, onPick = { song = it.copy(startMs = 0, quote = "") }) { picking = false }
        if (emoji) {
            EmojiPanel(onEmoji = { e -> text = (text + e).take(700) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 88.dp))
        }
        if (text.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(56.dp).clip(CircleShape).background(Color.White)
                    .clickable {
                        controller.postTextStatus(text, StatusColors[colour], font, StatusExtras(song = song))
                        controller.pop()
                        controller.selectTab(ChatTab.STATUS)
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(QMusicIcons.Send, contentDescription = s.statusShare, tint = Color.Black) }
        }
    }
}

/** The colours words over a status can be: white first, then strong ones that read on any picture. */
internal val OverlayColors = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD60A, 0xFFFF3B30, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2)

/**
 * Words over a status picture, drawn where they were put ([StatusOverlay.x], [StatusOverlay.y]), on a soft shadow so
 * they read on any picture. [onMove]: they can be dragged (while writing the status).
 */
@Composable
internal fun StatusOverlayText(overlay: StatusOverlay, modifier: Modifier = Modifier, onMove: ((Float, Float) -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val dark = overlay.color == 0xFF000000
        Text(
            overlay.text,
            color = Color(overlay.color),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.7f), blurRadius = 8f)),
            modifier = Modifier
                .widthIn(max = maxWidth * 0.86f)
                .onSizeChanged { size = it }
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (overlay.x * w - size.width / 2f).toInt().coerceIn(0, (w - size.width).toInt().coerceAtLeast(0)),
                        (overlay.y * h - size.height / 2f).toInt().coerceIn(0, (h - size.height).toInt().coerceAtLeast(0)),
                    )
                }
                .then(
                    if (onMove == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onMove(drag.x / w, drag.y / h)
                            }
                        }
                    },
                ),
        )
    }
}

/**
 * The song of a status being made: a tap picks which 15 seconds play (it shows them), the cross takes it off. The
 * part picker opens by itself once, right after the song is picked.
 */
@Composable
private fun ComposerSongChip(song: SharedItem, s: ChatStrings, modifier: Modifier, onPart: (Long) -> Unit, onRemove: () -> Unit) {
    var choosing by remember(song.id) { mutableStateOf(song.startMs == 0L) }
    StatusSongChip(song, modifier, trailing = {
        Spacer(Modifier.width(8.dp))
        Text(
            secondsLabel(if (song.startMs == 1L) 0L else statusSongStart(song)),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Close, contentDescription = s.statusSongRemove, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }) { choosing = true }
    if (choosing) StatusSongPart(song, onPick = onPart) { choosing = false }
}

/** A status's song: its cover and name; a tap plays it. */
@Composable
internal fun StatusSongChip(song: SharedItem, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            if (song.thumbnail != null) {
                AsyncImage(model = song.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(QMusicIcons.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(song.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song.subtitle.isNotBlank()) Text(song.subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** Picks the song for a status: the one playing in Qeyf now, or any found by searching. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun StatusSongPicker(
    s: ChatStrings,
    songs: List<SharedItem>,
    search: suspend (String) -> List<SharedItem> = { emptyList() },
    onPick: (SharedItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SharedItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }.debounce(400).distinctUntilChanged().collect { q ->
            if (q.length < 2) {
                found = null
                return@collect
            }
            busy = true
            found = runCatching { search(q) }.getOrDefault(emptyList())
            busy = false
        }
    }
    val list = found ?: songs
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.statusSongPick, color = TextPrimary) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BgRoot).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(s.statusSongSearch, color = TextMuted, fontSize = 15.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it.take(60) },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(TextPrimary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (busy) CircularProgressIndicator(color = TextPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text(if (found == null) s.statusNoSong else s.noResults, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    if (found == null) Text(s.statusNowPlaying, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { song ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(song); onDismiss() }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                                    if (song.thumbnail != null) AsyncImage(model = song.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (song.subtitle.isNotBlank()) Text(song.subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } },
    )
}

/**
 * A photo or a video before it goes up, full screen: a caption, words written over it (dragged where they should be,
 * in a colour), and a song (the one playing in Qeyf).
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
internal fun StatusMediaComposer(
    s: ChatStrings,
    image: PickedImage?,
    video: PickedFile?,
    songs: () -> List<SharedItem> = { emptyList() },
    searchSongs: suspend (String) -> List<SharedItem> = { emptyList() },
    onCancel: () -> Unit,
    onShare: (String, PickedImage?, StatusExtras) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var photo by remember(image) { mutableStateOf(image) }
    var editing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<StatusOverlay?>(null) }
    var writing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(0) }
    var song by remember { mutableStateOf<SharedItem?>(null) }
    var picking by remember { mutableStateOf(false) }
    // The bars of the screen, measured out here: inside its own window the dialog does not hear about them, and the
    // caption box went under the gesture bar.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottomBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog's own window goes under the bars too (its size then is the screen's), and its bars are measured
        // in it: measured outside alone, the box with the caption and the send button sat under the navigation bar
        // on some phones (the window was laid out differently from the screen).
        DialogEdgeToEdge()
        @Suppress("NAME_SHADOWING")
        val topBar = maxOf(topBar, with(density) { WindowInsets.statusBars.getTop(this).toDp() })
        @Suppress("NAME_SHADOWING")
        val bottomBar = maxOf(bottomBar, with(density) { WindowInsets.navigationBars.getBottom(this).toDp() })
        val shown = photo
        if (editing && shown != null) {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(top = topBar, bottom = bottomBar)) {
                PhotoEditor(shown, onDone = { photo = it; editing = false }, onCancel = { editing = false })
            }
            return@Dialog
        }
        Box(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
            when {
                shown != null -> AsyncImage(model = shown.jpeg, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                video != null -> VideoMessagePlayer(video.location, Modifier.fillMaxSize())
            }
            // The words over it, draggable.
            overlay?.takeIf { !writing }?.let { o ->
                StatusOverlayText(o, onMove = { dx, dy -> overlay = o.copy(x = (o.x + dx).coerceIn(0.05f, 0.95f), y = (o.y + dy).coerceIn(0.08f, 0.92f)) })
            }
            Column(Modifier.fillMaxWidth().padding(top = topBar).padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(QMusicIcons.Close, contentDescription = s.cancel, tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    // Words over it ("Aa"), a song, and the photo editor.
                    Text(
                        "Aa",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).clickable {
                            draft = overlay?.text.orEmpty()
                            writing = true
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    IconButton(onClick = { picking = true }) { Icon(QMusicIcons.Headphones, contentDescription = s.statusAddSong, tint = Color.White) }
                    if (shown != null) {
                        if (!ChatPlatform.web) IconButton(onClick = { editing = true }) { Icon(QMusicIcons.Edit, contentDescription = s.statusEdit, tint = Color.White) }
                    }
                }
                song?.let { g ->
                    ComposerSongChip(g, s, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp), onPart = { song = g.copy(startMs = it) }) { song = null }
                }
            }
            if (video != null && video.durationMs > 60_000L) {
                Text(
                    s.statusVideoLimit,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topBar + 110.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (!writing) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomBar).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xCC1E1E1E)).padding(horizontal = 18.dp, vertical = 13.dp)) {
                        if (caption.isEmpty()) Text(s.statusCaption, color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                        BasicTextField(
                            value = caption,
                            onValueChange = { caption = it.take(700) },
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable {
                            onShare(caption, photo.takeIf { it !== image }, StatusExtras(overlay = overlay, song = song))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(QMusicIcons.Send, contentDescription = s.statusShare, tint = Color.Black) }
                }
            }
            // Writing the words: big, in the middle, with the colours under them.
            if (writing) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
                    Text(
                        s.statusDone,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = topBar + 8.dp, end = 12.dp).clip(RoundedCornerShape(16.dp)).clickable {
                            val text = draft.trim()
                            overlay = if (text.isEmpty()) null else (overlay?.copy(text = text, color = OverlayColors[colour]) ?: StatusOverlay(text, 0.5f, 0.4f, OverlayColors[colour]))
                            writing = false
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        if (draft.isEmpty()) Text(s.statusTextHint, color = Color.White.copy(alpha = 0.5f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it.take(200) },
                            textStyle = TextStyle(color = Color(OverlayColors[colour]), fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBar + 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OverlayColors.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(if (i == colour) 32.dp else 26.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(c))
                                    .clickable { colour = i },
                            )
                        }
                    }
                }
            }
        }
    }
    if (picking) StatusSongPicker(s, songs().filter { it.kind == ShareKind.SONG }, searchSongs, onPick = { song = it.copy(startMs = 0, quote = "") }) { picking = false }
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
    var listening by remember(owner) { mutableStateOf(false) }
    val paused = held || typing || viewersOpen || menu || confirmDelete != null || listening
    PlatformBackHandler(enabled = true) { controller.pop() }

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { controller.pop() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val at = index.coerceIn(0, items.lastIndex)
    val item = items[at]
    val muted by controller.mutedStatuses.collectAsState()
    val nextOwner: () -> Unit = {
        val i = order.indexOf(owner)
        val next = order.drop(i + 1).firstOrNull { uid -> uid !in muted && groups.any { it.owner.uid == uid && !it.seenAll } }
        if (mine || next == null) controller.pop() else owner = next
    }
    val previousOwner: () -> Unit = {
        val i = order.indexOf(owner)
        val previous = order.take(i.coerceAtLeast(0)).lastOrNull { uid -> groups.any { it.owner.uid == uid } }
        if (!mine && previous != null) owner = previous
    }
    val goNext: () -> Unit = { if (at < items.lastIndex) index = at + 1 else nextOwner() }
    val goBack: () -> Unit = { if (at > 0) index = at - 1 }
    LaunchedEffect(item.id) { if (!mine) controller.markStatusSeen(item) }

    // How far the current one has played (0..1); a video goes as long as it is.
    var progress by remember(item.id) { mutableFloatStateOf(0f) }
    var videoLength by remember(item.id) { mutableLongStateOf(if (item.kind == StatusKind.VIDEO) item.durationMs.coerceAtLeast(1_000L) else if (item.song != null) SONG_MS else STILL_MS) }
    // Its song plays by itself (the part its poster chose), for as long as the status shows. The status waits for
    // it (a few seconds at most), so the song is not half over when it is heard.
    val soundOn by ChatMusic.statusSound.collectAsState()
    var songUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var songReady by remember(item.id) { mutableStateOf(item.song == null || item.kind == StatusKind.VIDEO) }
    var songLoading by remember(item.id) { mutableStateOf(item.song != null && item.kind != StatusKind.VIDEO) }
    var songRetried by remember(item.id) { mutableStateOf(false) }
    val songScope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        val g = item.song?.takeIf { item.kind != StatusKind.VIDEO } ?: return@LaunchedEffect
        launch {
            kotlinx.coroutines.delay(SONG_WAIT_MS)
            songReady = true
        }
        songUrl = ChatMusic.songUrl(g.id)
        if (songUrl == null) {
            songReady = true
            songLoading = false
        }
    }
    // The songs of the next few statuses are looked up while this one shows.
    LaunchedEffect(owner, at) {
        items.drop(at + 1).take(3).mapNotNull { it.song?.id }.forEach { id -> launch { ChatMusic.songUrl(id) } }
    }
    if (!soundOn) songReady = true
    songUrl?.let { url ->
        androidx.compose.runtime.key(url) {
            StatusAudio(
                url = url,
                playing = !paused && !listening && soundOn,
                startMs = item.song?.let { statusSongStart(it) } ?: STATUS_SONG_DEFAULT_START,
                onReady = {
                    songReady = true
                    songLoading = false
                },
                onFailed = {
                    val g = item.song
                    if (songRetried || g == null) {
                        songReady = true
                        songLoading = false
                    } else {
                        // An address that stopped working: a fresh one, once.
                        songRetried = true
                        songScope.launch {
                            songUrl = null
                            songUrl = ChatMusic.songUrl(g.id, fresh = true)
                            if (songUrl == null) {
                                songReady = true
                                songLoading = false
                            }
                        }
                    }
                },
            )
        }
    }
    var videoReady by remember(item.id) { mutableStateOf(item.kind != StatusKind.VIDEO) }
    LaunchedEffect(item.id, paused, videoReady, videoLength, songReady) {
        if (paused || !videoReady || !songReady) return@LaunchedEffect
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
                // A tap goes on or back, holding pauses, sideways changes person, down closes.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val started = Clock.System.now().toEpochMilliseconds()
                    held = true
                    var moved = Offset.Zero
                    var lifted = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            lifted = true
                            break
                        }
                        moved += change.positionChange()
                    }
                    held = false
                    val quick = Clock.System.now().toEpochMilliseconds() - started < 250
                    when {
                        moved.y > 220f && abs(moved.y) > abs(moved.x) -> controller.pop()
                        abs(moved.x) > 160f && abs(moved.x) > abs(moved.y) -> if (moved.x < 0) nextOwner() else previousOwner()
                        lifted && quick && moved.getDistance() < 40f -> if (down.position.x < size.width / 3f) goBack() else goNext()
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
                    fontWeight = StatusFontWeights.getOrElse(item.font) { FontWeight.SemiBold },
                    fontFamily = StatusFonts.getOrElse(item.font) { FontFamily.Default },
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

        // Words written over it.
        item.overlay?.let { o -> StatusOverlayText(o) }
        // Its song: a tap plays it in Qeyf (the status waits meanwhile).
        item.song?.let { g ->
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusSongChip(g, trailing = {
                    Spacer(Modifier.width(8.dp))
                    if (songLoading && soundOn) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(QMusicIcons.Play, contentDescription = s.statusListen, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }) {
                    listening = true
                    controller.openShare(g)
                }
                if (item.kind != StatusKind.VIDEO) {
                    Spacer(Modifier.width(8.dp))
                    val mw = ChatMusic.words()
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { ChatMusic.statusSound.value = !soundOn },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (soundOn) QMusicIcons.Volume else QMusicIcons.VolumeMute,
                            contentDescription = if (soundOn) mw.soundOff else mw.soundOn,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
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
                    val likes by remember(item.id) { controller.statusLikes(item) }.collectAsState(initial = emptySet())
                    if (likes.isNotEmpty()) {
                        Spacer(Modifier.width(14.dp))
                        Icon(QMusicIcons.HeartFilled, contentDescription = null, tint = Color(0xFFFF5A5F), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(likes.size.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (viewersOpen) {
                    val likes by remember(item.id) { controller.statusLikes(item) }.collectAsState(initial = emptySet())
                    ViewersDialog(viewers, likes, s, now) { viewersOpen = false }
                }
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
                    if (reply.isBlank()) {
                        val liked by remember(item.id) { controller.likedStatus(item) }.collectAsState(initial = false)
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { controller.likeStatus(item, !liked) }) {
                            Icon(
                                if (liked) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                                contentDescription = s.statusLike,
                                tint = if (liked) Color(0xFFFF5A5F) else Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
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
private fun ViewersDialog(viewers: List<Pair<ChatUser, Long>>, likes: Set<String>, s: ChatStrings, now: Long, onDismiss: () -> Unit) {
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
                            if (user.uid in likes) Icon(QMusicIcons.HeartFilled, contentDescription = s.statusLike, tint = Color(0xFFFF5A5F), modifier = Modifier.size(18.dp))
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
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) (if (privacy.audience == StatusAudience.EXCEPT) Color(0xFFFF5A5F) else Ink) else DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(if (privacy.audience == StatusAudience.EXCEPT) QMusicIcons.Close else QMusicIcons.Check, contentDescription = null, tint = InkInverse, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink else TextMuted).padding(2.dp).clip(CircleShape).background(BgRoot).padding(4.dp).clip(CircleShape)
            .background(if (on) Ink else BgRoot),
    )
}
