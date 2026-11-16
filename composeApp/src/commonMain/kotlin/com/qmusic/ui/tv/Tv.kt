package com.qmusic.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.chat.TogetherPlayer
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A TV this phone was linked to with the TV's code: its own long secret name, and what it is called. */
data class LinkedTv(val id: String, val name: String)

/** What a TV plays for a phone. */
enum class TvMode { IDLE, VIDEO, MUSIC }

/** What the TV shows of the song it plays. */
data class TvNow(val title: String, val subtitle: String, val thumbnail: String?, val positionMs: Long, val durationMs: Long, val playing: Boolean)

/** The remote's keys the TV acts on. */
enum class TvKey { CENTER, PLAY_PAUSE, LEFT, RIGHT, NEXT, PREVIOUS, BACK }

/**
 * The phone's side: the TVs linked by code (Qeyf on the TV shows one), and the one played on now. Linking needs no
 * Chromecast and no shared network search: the phone and the TV meet in the database, and the TV fetches what it plays
 * itself; the phone is its remote.
 */
object TvLink {
    val tvs = MutableStateFlow<List<LinkedTv>>(emptyList())
    val active = MutableStateFlow<LinkedTv?>(null)

    /** Links the TV that shows [code]: null when linked (and in use), else why not. */
    var link: (suspend (code: String) -> String?)? = null

    /** Plays on [tv] from now on (null: back to the phone). */
    var use: ((LinkedTv?) -> Unit)? = null
    var forget: ((LinkedTv) -> Unit)? = null

    /** The app's language (set by the app). */
    var english: Boolean = false
}

/** The TV's side: Qeyf running on a TV, waiting for a phone or playing what one sent. */
object TvScreen {
    /** Qeyf runs on a TV (set at start). */
    var isTv: Boolean = false

    /** The code shown for linking a phone, or null while there is none yet. */
    val code = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(TvMode.IDLE)
    val now = MutableStateFlow<TvNow?>(null)

    /** The person went on into the app with the remote (the code screen is put away). */
    val browsing = MutableStateFlow(false)

    /** The app's music and video players (set by the app). */
    var music: TogetherPlayer? = null
    var video: TogetherPlayer? = null
    var playQueue: ((songs: List<SongItem>, index: Int, positionMs: Long) -> Unit)? = null
    var jump: ((Int) -> Unit)? = null
    var musicIndex: (() -> Int)? = null
    var musicDuration: (() -> Long)? = null
    var videoDuration: (() -> Long)? = null
    var next: (() -> Unit)? = null
    var previous: (() -> Unit)? = null

    /** The player of what plays now for the phone. */
    private fun player(): TogetherPlayer? = when (mode.value) {
        TvMode.VIDEO -> video
        TvMode.MUSIC -> music
        TvMode.IDLE -> null
    }

    /** A key of the remote; true when it was used here (the app does not get it then). */
    fun onKey(key: TvKey): Boolean {
        val p = player() ?: return false
        when (key) {
            TvKey.CENTER, TvKey.PLAY_PAUSE -> if (p.isPlaying()) p.pause() else p.play()
            TvKey.LEFT -> p.seekTo((p.positionMs() - SEEK_MS).coerceAtLeast(0))
            TvKey.RIGHT -> p.seekTo(p.positionMs() + SEEK_MS)
            TvKey.NEXT -> if (mode.value == TvMode.MUSIC) next?.invoke() else return false
            TvKey.PREVIOUS -> if (mode.value == TvMode.MUSIC) previous?.invoke() else return false
            TvKey.BACK -> stop()
        }
        return true
    }

    /** Stops what plays for the phone and shows the code again. */
    fun stop() {
        video?.pause()
        music?.pause()
        mode.value = TvMode.IDLE
        browsing.value = false
    }

    private const val SEEK_MS = 10_000L
}

// ---------------------------------------------------------------------------------------------- the TV's screens

private val TvBg = Color(0xFF0E0E12)
private val TvAccent = Color(0xFFFF4E6A)

/** Over the app on a TV: the code to link a phone (while nothing plays), or the song playing. */
@Composable
fun TvOverlay(english: Boolean) {
    if (!TvScreen.isTv) return
    val mode by TvScreen.mode.collectAsState()
    val browsing by TvScreen.browsing.collectAsState()
    when {
        mode == TvMode.MUSIC -> TvMusicScreen(english)
        mode == TvMode.IDLE && !browsing -> TvHomeScreen(english)
    }
}

@Composable
private fun TvHomeScreen(english: Boolean) {
    val code by TvScreen.code.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxSize().background(TvBg).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Nuvelo", color = TvAccent, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            Text(if (english) "Link your phone" else "Telefonunu bağla", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            val steps = if (english) {
                listOf("Open a video or a song in Nuvelo on the phone", "Tap the TV button, then \"Link a new TV\"", "Enter this code, or scan it")
            } else {
                listOf("Telefonda Nuvelo'da bir video ya da şarkı aç", "TV düğmesine bas, \"Yeni TV bağla\"yı seç", "Bu kodu gir ya da kodu okut")
            }
            steps.forEachIndexed { i, step ->
                Text("${i + 1}.  $step", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                code?.let { "${it.take(3)} ${it.drop(3)}" } ?: "...",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(30.dp))
            TvTextButton(if (english) "Browse the app" else "Uygulamada gez", Modifier.focusRequester(focus)) { TvScreen.browsing.value = true }
        }
        code?.let {
            Box(Modifier.size(280.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp)) {
                com.qmusic.ui.chat.QrCode(tvLinkText(it), Modifier.fillMaxSize())
            }
        }
    }
}

/** What the TV's QR code says: the phone reads the code from it. */
fun tvLinkText(code: String) = "qeyf://tv/$code"

/** The code in a scanned [text] (the TV's QR code, or the code typed), or null. */
fun tvCodeOf(text: String): String? =
    Regex("""(\d{3})\s?(\d{3})""").find(text.trim())?.let { it.groupValues[1] + it.groupValues[2] }

@Composable
private fun TvTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Text(
        text,
        color = if (focused) Color.Black else Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    )
}

@Composable
private fun TvMusicScreen(english: Boolean) {
    val now by TvScreen.now.collectAsState()
    val n = now
    Box(Modifier.fillMaxSize().background(TvBg)) {
        n?.thumbnail?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxSize().blur(60.dp))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(340.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
                n?.thumbnail?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(n?.title ?: "", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(n?.subtitle ?: "", color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(36.dp))
                val dur = n?.durationMs ?: 0L
                LinearProgressIndicator(
                    progress = { if (dur > 0) ((n?.positionMs ?: 0L).toFloat() / dur).coerceIn(0f, 1f) else 0f },
                    color = TvAccent,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(clock(n?.positionMs ?: 0L), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(clock(dur), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    if (n?.playing == false) (if (english) "Paused" else "Duraklatıldı") else "",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                )
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "$m:${if (s < 10) "0$s" else "$s"}"
}

// ---------------------------------------------------------------------------------------------- the phone's side

/**
 * The phone's TV button: opens the list of linked TVs (and linking a new one by its code). Lit while a TV plays for
 * this phone.
 */
@Composable
fun TvButton(tint: Color = Color.White, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active by TvLink.active.collectAsState()
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(QMusicIcons.Tv, contentDescription = "TV", tint = if (active != null) TvAccent else tint, modifier = Modifier.size(24.dp))
    }
    if (open) TvPicker(onDismiss = { open = false })
}

@Composable
private fun TvPicker(onDismiss: () -> Unit) {
    val english = TvLink.english
    val tvs by TvLink.tvs.collectAsState()
    val active by TvLink.active.collectAsState()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun linkWith(raw: String) {
        val c = tvCodeOf(raw) ?: run {
            error = if (english) "The code has 6 digits" else "Kod 6 haneli olmalı"
            return
        }
        val link = TvLink.link ?: return
        busy = true
        error = null
        scope.launch {
            val why = link(c)
            busy = false
            if (why == null) onDismiss() else error = why
        }
    }
    val scan = com.qmusic.ui.chat.rememberQrScanner { text -> linkWith(text) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF1C1C22)).padding(20.dp),
        ) {
            Text(if (english) "Play on a TV" else "TV'de oynat", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            tvs.forEach { tv ->
                val on = active?.id == tv.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) TvAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                        .clickable {
                            TvLink.use?.invoke(if (on) null else tv)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.Tv, null, tint = if (on) TvAccent else Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (on) (if (english) "Playing here - tap to stop" else "Burada oynatılıyor - durdurmak için dokun") else (if (english) "Tap to play on it" else "Oynatmak için dokun"),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        if (english) "Remove" else "Kaldır",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { TvLink.forget?.invoke(tv) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(if (english) "Link a new TV" else "Yeni TV bağla", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (english) "Install Nuvelo on the TV and open it: it shows a code." else "TV'ye Nuvelo'yu kur ve aç: ekranda bir kod çıkar.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    placeholder = { Text("123456", color = Color.White.copy(alpha = 0.3f)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TvAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = TvAccent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (english) "Link" else "Bağla",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (code.length == 6 && !busy) TvAccent else Color.White.copy(alpha = 0.15f))
                        .clickable(enabled = code.length == 6 && !busy) { linkWith(code) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
            if (scan != null) {
                Text(
                    if (english) "Scan the code on the TV" else "TV'deki kodu okut",
                    color = TvAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).clickable { scan() }.padding(vertical = 6.dp),
                )
            }
            if (busy) LinearProgressIndicator(color = TvAccent, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Chromecast", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                com.qmusic.ui.CastButton(Modifier.size(48.dp))
            }
        }
    }
}
