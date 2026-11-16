package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.rememberShare
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary

/** What a person's Qeyf code holds. */
internal fun qeyfLink(username: String) = "qeyf://u/$username"

/** The username in a scanned Qeyf code (qeyf://u/name, or a bare @name); null for anything else. */
internal fun qeyfUsername(text: String): String? {
    val t = text.trim()
    val name = when {
        t.startsWith("qeyf://u/", ignoreCase = true) -> t.substring(9)
        t.startsWith("@") -> t.substring(1)
        else -> return null
    }.substringBefore('?').substringBefore('/').lowercase()
    return name.takeIf { isValidUsername(it) }
}

/** A group's invite link. */
internal fun qeyfGroupLink(code: String) = "qeyf://g/$code"

/** A call link: opening it shows the person with the voice and video buttons. */
internal fun qeyfCallLink(username: String) = "qeyf://c/$username"

/** What a qeyf:// link (tapped, or scanned) opens; null for anything else. */
fun qeyfLaunchOf(text: String): ChatLaunchRequest? {
    val t = text.trim()
    fun tail(prefix: String) = t.substring(prefix.length).substringBefore('?').substringBefore('/')
    return when {
        t.startsWith("qeyf://g/", ignoreCase = true) -> tail("qeyf://g/").takeIf { it.matches(Regex("^[A-Za-z0-9]{10,40}$")) }?.let { ChatLaunchRequest(null, invite = it) }
        t.startsWith("qeyf://c/", ignoreCase = true) -> tail("qeyf://c/").lowercase().takeIf { isValidUsername(it) }?.let { ChatLaunchRequest(null, callUsername = it) }
        else -> qeyfUsername(t)?.let { ChatLaunchRequest(null, username = it) }
    }
}

/** A scanned code: a person, a group invite or a call link; anything else says so. */
internal fun ChatController.openQeyfCode(text: String, s: ChatStrings) {
    val request = qeyfLaunchOf(text)
    when {
        request?.invite != null -> openInvite(request.invite)
        request?.callUsername != null -> openCallLink(request.callUsername)
        request?.username != null -> openByUsername(request.username)
        else -> reportError(s.notFoundUser)
    }
}

/** [text] as a QR code, black on white. */
@Composable
expect fun QrCode(text: String, modifier: Modifier)

/** Opens the phone's code scanner; [onResult] gets what it read. Null where there is none. */
@Composable
expect fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)?

/**
 * A status's song from [startMs] (moved back when the song is shorter), fading in, playing while [playing]; with
 * [loopMs] it goes back to [startMs] after that long. [onReady] says, once it can play, how long the whole song is;
 * [onFailed] that it cannot be played (the address may have gone stale).
 */
@Composable
expect fun StatusAudio(
    url: String,
    playing: Boolean,
    startMs: Long = STATUS_SONG_DEFAULT_START,
    loopMs: Long = 0,
    onReady: (Long) -> Unit = {},
    onFailed: () -> Unit = {},
)

/** Where a status's song starts when its poster did not choose (where songs usually get going). */
const val STATUS_SONG_DEFAULT_START = 30_000L

/** How long of a status's song is heard (the status shows that long). */
const val STATUS_SONG_LENGTH = 15_000L

/** Where [song] starts in a status: the part its poster chose (1 ms stands for the very start), else the default. */
fun statusSongStart(song: SharedItem): Long = if (song.startMs > 0) song.startMs else STATUS_SONG_DEFAULT_START

/** A status video: no controls, paused when asked; [onReady] says how long it is (ms). */
@Composable
expect fun StatusVideo(url: String, paused: Boolean, modifier: Modifier, onReady: (Long) -> Unit)

/** The person's own code: friends scan it to find them; it can be shared as a link too. */
@Composable
internal fun MyQrPage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val share = rememberShare()
    val scan = rememberQrScanner { text -> controller.openQeyfCode(text, s) }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(s.myQrTitle, { controller.pop() })
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.padding(top = 36.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(me.name, color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("@" + me.username, color = Color(0xFF555555), fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    QrCode(qeyfLink(me.username), Modifier.size(232.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("qeyf", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
                Box(Modifier.clip(CircleShape).background(BgRoot).padding(4.dp)) { ChatAvatar(me, 68.dp) }
            }
            Spacer(Modifier.height(18.dp))
            Text(s.myQrNote, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QrAction(s.shareProfile, QMusicIcons.Share) { share(s.title + ": @" + me.username + "  " + qeyfLink(me.username)) }
                if (scan != null) QrAction(s.scanQr, QMusicIcons.Camera) { scan() }
            }
        }
    }
}

@Composable
private fun QrAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(DarkSurfaceVariant).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
