package com.qmusic.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val EndRed = Color(0xFFE5484D)
private val AnswerGreen = Color(0xFF2EB67D)

/** A call, full screen: who, how long, and the buttons. Over everything else while it lasts. */
@Composable
fun CallScreen(
    service: CallService,
    s: ChatStrings,
    /** Answer: asks for the microphone (and camera) first. */
    onAccept: (video: Boolean) -> Unit = { service.accept() },
    modifier: Modifier = Modifier,
) {
    val state by service.state.collectAsState()
    val call = state ?: return
    // Back does not end a call by accident.
    PlatformBackHandler(enabled = true) {}
    val showVideo = call.video && call.phase != CallPhase.ENDED

    Box(modifier.fillMaxSize().background(BgRoot)) {
        if (showVideo && call.remoteVideo && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.CONNECTING)) {
            CallVideo(service, remote = true, modifier = Modifier.fillMaxSize())
        } else if (showVideo && call.cameraOn && call.phase != CallPhase.INCOMING) {
            // Before they answer, our own camera fills the screen (as in WhatsApp).
            CallVideo(service, remote = false, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val videoUp = showVideo && call.remoteVideo && call.phase == CallPhase.ACTIVE
            Spacer(Modifier.height(if (videoUp) 16.dp else 72.dp))
            if (!videoUp) {
                Pulse(active = call.phase == CallPhase.CALLING || call.phase == CallPhase.INCOMING) {
                    ChatAvatar(call.peer, 120.dp)
                }
                Spacer(Modifier.height(24.dp))
            }
            Text(call.peer.name, color = TextPrimary, fontSize = if (videoUp) 20.sp else 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(statusText(call, s), color = TextSecondary, fontSize = 15.sp)
            if (call.weak && call.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(6.dp))
                Text(s.callWeak, color = TextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundButton(QMusicIcons.PhoneEnd, s.callDecline, EndRed, Color.White, big = true) { service.decline() }
                    RoundButton(if (call.video) QMusicIcons.Camera else QMusicIcons.Phone, s.callAccept, AnswerGreen, Color.White, big = true) {
                        onAccept(call.video)
                    }
                }
                CallPhase.ENDED -> Spacer(Modifier.height(120.dp))
                else -> Column(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Toggle(QMusicIcons.MicOff, s.callMute, call.muted) { service.toggleMute() }
                        if (call.video) {
                            Toggle(QMusicIcons.CameraOff, s.callCameraOff, !call.cameraOn) { service.toggleCamera() }
                            Toggle(QMusicIcons.SwitchCamera, s.callFlip, false) { service.switchCamera() }
                        }
                        Toggle(QMusicIcons.Volume, s.callSpeaker, call.speaker) { service.toggleSpeaker() }
                    }
                    Spacer(Modifier.height(28.dp))
                    RoundButton(QMusicIcons.PhoneEnd, s.callEnd, EndRed, Color.White, big = true) { service.hangUp() }
                }
            }
        }

        // Our own picture, small in the corner, once theirs fills the screen.
        if (showVideo && call.remoteVideo && call.cameraOn && call.phase == CallPhase.ACTIVE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
            ) {
                CallVideo(service, remote = false, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun statusText(call: CallState, s: ChatStrings): String = when (call.phase) {
    CallPhase.CALLING -> s.callCalling
    CallPhase.INCOMING -> if (call.video) s.callIncomingVideo else s.callIncomingVoice
    CallPhase.CONNECTING -> s.callConnecting
    CallPhase.ACTIVE -> {
        var now by remember { mutableLongStateOf(0L) }
        LaunchedEffect(call.startedAt) {
            while (true) {
                now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                delay(1_000)
            }
        }
        duration(((now - (call.startedAt ?: now)) / 1000).coerceAtLeast(0))
    }
    CallPhase.ENDED -> when (call.endReason) {
        CallEnd.DECLINED -> s.callDeclined
        CallEnd.NO_ANSWER -> s.callNoAnswer
        CallEnd.BUSY -> s.callBusy
        CallEnd.FAILED -> s.callFailed
        else -> s.callEnded
    }
}

fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val sec = seconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = sec.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

@Composable
private fun Pulse(active: Boolean, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition()
    val scale by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Box(contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(150.dp).scale(scale).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
        content()
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, bg: Color, fg: Color, big: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(if (big) 72.dp else 60.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(if (big) 32.dp else 26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (on) Color.White else DarkSurfaceVariant.copy(alpha = 0.85f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
    }
}
