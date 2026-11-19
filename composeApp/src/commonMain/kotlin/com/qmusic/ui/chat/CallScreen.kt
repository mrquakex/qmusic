package com.qmusic.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.NightBgRoot as BgRoot
import com.qmusic.ui.theme.NightSurfaceVariant as DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.NightTextPrimary as TextPrimary
import com.qmusic.ui.theme.NightTextSecondary as TextSecondary
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
    // Back does not end a call: it shrinks it into the small bubble (as on WhatsApp), the app usable meanwhile.
    val canShrink = call.phase == CallPhase.CALLING || call.phase == CallPhase.CONNECTING || call.phase == CallPhase.ACTIVE
    PlatformBackHandler(enabled = true) { if (canShrink) CallUi.minimized.value = true }
    val showVideo = call.video && call.phase != CallPhase.ENDED
    val askCamera = rememberCallPermissions()
    var routesOpen by remember { mutableStateOf(false) }
    // Leaving the app during a video call: it goes on in a floating window, showing only the picture.
    val inWindow = CallPictureInPicture(armed = showVideo && call.phase == CallPhase.ACTIVE)
    if (inWindow) {
        Box(modifier.fillMaxSize().background(Color.Black)) {
            if (call.remoteVideo) {
                CallVideo(service, remote = true, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChatAvatar(callerUser(call.peer), 72.dp) }
            }
        }
        return
    }

    Box(modifier.fillMaxSize().background(BgRoot)) {
        // No picture of them on screen: their photo fills the back, blurred, over a wash of its own colour.
        val picture = showVideo && ((call.remoteVideo && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.CONNECTING)) || (call.cameraOn && call.phase != CallPhase.INCOMING))
        if (!picture) CallBackdrop(call.peer.photo)
        if (showVideo && call.remoteVideo && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.CONNECTING)) {
            CallVideo(service, remote = true, modifier = Modifier.fillMaxSize())
        } else if (showVideo && call.cameraOn && call.phase != CallPhase.INCOMING) {
            // Before they answer, our own camera fills the screen (as in WhatsApp).
            CallVideo(service, remote = false, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        if (canShrink) {
            IconButton(
                onClick = { CallUi.minimized.value = true },
                modifier = Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)),
            ) { Icon(QMusicIcons.ChevronDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        }
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val videoUp = showVideo && call.remoteVideo && call.phase == CallPhase.ACTIVE
            Spacer(Modifier.height(if (videoUp) 16.dp else 72.dp))
            if (!videoUp) {
                Pulse(active = call.phase == CallPhase.CALLING || call.phase == CallPhase.INCOMING) {
                    // While talking, a soft ring around them follows their voice.
                    val level by androidx.compose.animation.core.animateFloatAsState(
                        if (call.phase == CallPhase.ACTIVE) call.remoteLevel else 0f,
                        animationSpec = tween(120),
                    )
                    Box(contentAlignment = Alignment.Center) {
                        if (level > 0.01f) {
                            Box(
                                Modifier.size(120.dp).scale(1f + (level * 2.2f).coerceAtMost(0.45f)).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.10f + level * 0.25f)),
                            )
                        }
                        ChatAvatar(callerUser(call.peer), 120.dp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Text(callerShown(call.peer), color = TextPrimary, fontSize = if (videoUp) 20.sp else 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText(call, s), color = TextSecondary, fontSize = 15.sp)
                if (call.phase == CallPhase.ACTIVE && call.quality >= 0) {
                    Spacer(Modifier.width(10.dp))
                    SignalBars(call.quality)
                }
            }
            if (call.weak && call.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(6.dp))
                Text(s.callWeak, color = TextSecondary, fontSize = 13.sp)
            }
            // Calls go end to end encrypted (WebRTC's DTLS-SRTP: not even the relay server can listen).
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(com.qmusic.ui.theme.QMusicIcons.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(E2eWords(ChatMusic.english).encrypted, color = TextSecondary, fontSize = 12.sp)
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
                        } else if (call.phase == CallPhase.ACTIVE) {
                            // A voice call becomes a video call: the camera asks for its permission first.
                            Toggle(QMusicIcons.Camera, s.callCameraOn, false) {
                                askCamera(true) { granted -> if (granted) service.toggleCamera() }
                            }
                        }
                        // With a headset or Bluetooth there are more than two places for the sound: a list to pick from.
                        Box {
                            val current = call.routes.firstOrNull { it.id == call.route }
                            val many = call.routes.size > 2
                            Toggle(
                                if (current?.kind == AudioRouteKind.BLUETOOTH || current?.kind == AudioRouteKind.WIRED) QMusicIcons.Headphones else QMusicIcons.Volume,
                                if (many && current != null) routeName(current, s) else s.callSpeaker,
                                call.speaker || (many && current?.kind != AudioRouteKind.EARPIECE),
                            ) { if (many) routesOpen = true else service.toggleSpeaker() }
                            androidx.compose.material3.DropdownMenu(
                                expanded = routesOpen,
                                onDismissRequest = { routesOpen = false },
                                containerColor = DarkSurfaceVariant,
                            ) {
                                Text(s.soundOutput, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                call.routes.forEach { r ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                when (r.kind) {
                                                    AudioRouteKind.SPEAKER -> QMusicIcons.Volume
                                                    AudioRouteKind.EARPIECE -> QMusicIcons.Phone
                                                    else -> QMusicIcons.Headphones
                                                },
                                                null,
                                                tint = Color.White,
                                            )
                                        },
                                        text = { Text(routeName(r, s), color = TextPrimary, fontWeight = if (r.id == call.route) FontWeight.Bold else FontWeight.Normal) },
                                        trailingIcon = { if (r.id == call.route) Icon(QMusicIcons.Check, null, tint = Color.White) },
                                        onClick = {
                                            routesOpen = false
                                            service.setAudioRoute(r.id)
                                        },
                                    )
                                }
                            }
                        }
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
internal fun statusText(call: CallState, s: ChatStrings): String = when (call.phase) {
    CallPhase.CALLING -> if (call.reached) s.callRinging else s.callCalling
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
        CallEnd.UNREACHABLE -> s.callUnreachable
        else -> s.callEnded
    }
}

/** "Telefon", "Hoparlör", "Kulaklık", or the Bluetooth device's own name. */
internal fun routeName(r: AudioRoute, s: ChatStrings): String = when (r.kind) {
    AudioRouteKind.EARPIECE -> s.routeEarpiece
    AudioRouteKind.SPEAKER -> s.routeSpeaker
    AudioRouteKind.WIRED -> s.routeWired
    AudioRouteKind.BLUETOOTH -> r.name.ifBlank { s.routeBluetooth }
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

/** The connection in four bars: all lit is good, one is bad (red). */
@Composable
private fun SignalBars(quality: Int) {
    val lit = (quality + 1).coerceIn(1, 4)
    val colour = when (quality) {
        0 -> Color(0xFFFF5A5F)
        1 -> Color(0xFFFFB800)
        else -> Color.White
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(Modifier.width(3.dp).height((5 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i < lit) colour else Color.White.copy(alpha = 0.25f)))
        }
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


/**
 * Behind a call: the person's photo, large and blurred, with their photo's main colour glowing from the top and a dark
 * fall to the bottom, so the name and the buttons always read. No photo: a quiet dark glow.
 */
@androidx.compose.runtime.Composable
private fun CallBackdrop(photo: String?) {
    val tint = com.qmusic.ui.rememberDominantColor(photo)
    val glow = tint ?: Color(0xFF2A2A35)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (photo != null) {
            coil3.compose.AsyncImage(
                model = sizedPhoto(photo, 512),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize().blur(48.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to glow.copy(alpha = 0.55f),
                    0.45f to glow.copy(alpha = 0.18f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )
        // Phones without blur (before Android 12) see the photo sharp: a darker veil keeps it a background.
        if (photo != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (callBlurWorks) 0.15f else 0.5f)))
    }
}

/** The call shrunk into a bubble (the app usable under it), and the call service it shows. */
object CallUi {
    val minimized = kotlinx.coroutines.flow.MutableStateFlow(false)
    var service by androidx.compose.runtime.mutableStateOf<CallService?>(null)
    var strings by androidx.compose.runtime.mutableStateOf<ChatStrings?>(null)
}

/**
 * Over the whole app while a call is shrunk: a small bubble (their picture and the time, or their video), dragged
 * anywhere and settling on the nearer side; a tap brings the call back to full screen.
 */
@Composable
fun CallBubbleOverlay() {
    val service = CallUi.service ?: return
    val s = CallUi.strings ?: return
    val state by service.state.collectAsState()
    val call = state ?: return
    val minimized by CallUi.minimized.collectAsState()
    if (!minimized || call.phase == CallPhase.INCOMING || call.phase == CallPhase.ENDED) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val video = call.video && call.remoteVideo && call.phase == CallPhase.ACTIVE
    val margin = with(density) { 12.dp.toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val w = with(density) { (if (video) 112.dp else 84.dp).toPx() }
        val h = with(density) { (if (video) 160.dp else 104.dp).toPx() }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(areaW - w - margin, with(density) { 80.dp.toPx() })) }
        val shape = RoundedCornerShape(if (video) 16.dp else 26.dp)
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .shadow(14.dp, shape)
                .clip(shape)
                .background(Color(0xFF15151B))
                .pointerInput(areaW, areaH) {
                    detectDragGestures(
                        onDragEnd = {
                            // To the nearer side, like the system's own bubbles.
                            val x = if (offset.x + w / 2 < areaW / 2) margin else areaW - w - margin
                            offset = androidx.compose.ui.geometry.Offset(x, offset.y.coerceIn(margin, areaH - h - margin))
                        },
                    ) { change, drag ->
                        change.consume()
                        offset = androidx.compose.ui.geometry.Offset(
                            (offset.x + drag.x).coerceIn(0f, areaW - w),
                            (offset.y + drag.y).coerceIn(0f, areaH - h),
                        )
                    }
                }
                .clickable { CallUi.minimized.value = false },
            contentAlignment = Alignment.Center,
        ) {
            if (video) {
                CallVideo(service, remote = true, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, Color(0xFF1DAA61), CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                        ChatAvatar(callerUser(call.peer), 50.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(statusText(call, s), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** The caller's name, or "Nuvelo call" for someone kept in the secret space (when the person chose not to show it). */
internal fun callerShown(peer: ChatUser): String {
    if (SecretChats.showCallerName) return peer.name
    val secret = SecretChats.ids.any { id -> id.split("_").contains(peer.uid) }
    return if (secret) (if (ChatMusic.english) "Nuvelo call" else "Nuvelo araması") else peer.name
}

/** The caller as shown: hidden behind "Nuvelo" for someone kept in the secret space (when chosen so). */
internal fun callerUser(peer: ChatUser): ChatUser {
    val shown = callerShown(peer)
    return if (shown == peer.name) peer else ChatUser(peer.uid, "Nuvelo", "", null)
}
