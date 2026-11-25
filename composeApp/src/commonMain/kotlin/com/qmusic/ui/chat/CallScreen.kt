package com.qmusic.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.NightBgRoot as BgRoot
import com.qmusic.ui.theme.NightSurfaceVariant as DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.NightTextPrimary as TextPrimary
import com.qmusic.ui.theme.NightTextSecondary as TextSecondary
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /** Who can be added to the call (the person's friends); empty: no "Add person". */
    people: List<ChatUser> = emptyList(),
    /** Declining with a short message: it goes to the caller's conversation. Null: not offered. */
    onQuickReply: ((ChatUser, String) -> Unit)? = null,
) {
    val state by service.state.collectAsState()
    val call = state ?: return
    // Back does not end a call: it shrinks it into the small bubble (as on WhatsApp), the app usable meanwhile.
    val canShrink = call.phase == CallPhase.CALLING || call.phase == CallPhase.CONNECTING || call.phase == CallPhase.ACTIVE
    PlatformBackHandler(enabled = true) { if (canShrink) CallUi.minimized.value = true }
    val showVideo = call.video && call.phase != CallPhase.ENDED
    val askCamera = rememberCallPermissions()
    val shareScreen = rememberScreenShare(service)
    var routesOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var reactOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var repliesOpen by remember { mutableStateOf(false) }
    // Their picture is on the screen (not stopped by a weak line), and ours.
    val remoteShown = showVideo && call.remoteVideo && !call.remotePaused && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.CONNECTING)
    val localShown = showVideo && call.cameraOn && !call.sharingScreen && call.phase != CallPhase.INCOMING
    // Leaving the app during a call (ringing out too): it goes on in a floating window (their picture, or their photo
    // and the time). Armed across camera toggles whenever the call can shrink.
    val inWindow = CallPictureInPicture(armed = canShrink)
    if (inWindow) {
        CallWindow(service, call, s, Modifier.fillMaxSize())
        return
    }

    val videoUp = remoteShown && call.phase == CallPhase.ACTIVE
    // A video call on screen: the buttons step aside after a few seconds, and a tap brings them back.
    var controlsShown by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    val menuOpen = routesOpen || moreOpen || reactOpen || addOpen
    LaunchedEffect(videoUp, controlsShown, touchedAt, menuOpen) {
        if (!videoUp) {
            controlsShown = true
        } else if (controlsShown && !menuOpen) {
            delay(CONTROLS_SHOWN_MS)
            controlsShown = false
        }
    }
    val showControls = controlsShown || !videoUp || call.held
    // Double tap on the small picture: theirs and ours change places.
    var swapped by remember { mutableStateOf(false) }
    val swap = swapped && videoUp && localShown

    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
    Box(modifier.fillMaxSize().background(BgRoot)) {
        // No picture of them on screen: their photo fills the back, blurred, over a wash of its own colour.
        if (!remoteShown && !localShown) CallBackdrop(call.peer.photo)
        if (remoteShown && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.CONNECTING)) {
            CallVideo(service, remote = !swap, modifier = Modifier.fillMaxSize(), overlay = false)
        } else if (localShown) {
            // Before they answer (or while their picture is off), our own camera fills the screen (as in WhatsApp).
            CallVideo(service, remote = false, modifier = Modifier.fillMaxSize(), overlay = false)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        } else if (showVideo && call.remotePaused) {
            CallBackdrop(call.peer.photo)
        }
        // A tap anywhere on the picture shows or hides the buttons.
        if (videoUp) {
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controlsShown = !controlsShown
                    touchedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                },
            )
        }

        // Our picture small in a corner once theirs fills the screen: dragged to any corner, double tap to swap.
        if (videoUp && localShown) {
            SmallPicture(
                service = service,
                remote = swap,
                bottomSpace = if (showControls) 250.dp else 24.dp,
                onSwap = { swapped = !swapped },
            )
        }

        // The top: shrink the call (left), send an emoji (right).
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canShrink) {
                    IconButton(
                        onClick = { CallUi.minimized.value = true },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)),
                    ) { Icon(QMusicIcons.ChevronDown, contentDescription = com.qmusic.ui.A11y.minimize, tint = Color.White, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.weight(1f))
                if (call.phase == CallPhase.ACTIVE) {
                    if (reactOpen) {
                        Row(
                            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CALL_REACTIONS.forEach { e ->
                                Text(
                                    e,
                                    fontSize = 26.sp,
                                    modifier = Modifier.clip(CircleShape).clickable {
                                        service.react(e)
                                        reactOpen = false
                                        touchedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                                    }.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = { reactOpen = !reactOpen },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(if (reactOpen) Color.White else Color.Black.copy(alpha = 0.25f)),
                    ) { Icon(if (reactOpen) QMusicIcons.Close else QMusicIcons.Mood, contentDescription = s.callReact, tint = if (reactOpen) Color.Black else Color.White, modifier = Modifier.size(24.dp)) }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (videoUp) 64.dp else 72.dp))
            if (!videoUp) {
                Pulse(active = call.phase == CallPhase.CALLING || call.phase == CallPhase.INCOMING) {
                    // While talking, a soft ring around them follows their voice.
                    val level by androidx.compose.animation.core.animateFloatAsState(
                        if (call.phase == CallPhase.ACTIVE && !call.reconnecting) call.remoteLevel else 0f,
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
            AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(callerShown(call.peer), color = TextPrimary, fontSize = if (videoUp) 20.sp else 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (call.reconnecting) {
                            Spinner()
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(statusText(call, s), color = TextSecondary, fontSize = 15.sp)
                        if (call.phase == CallPhase.ACTIVE && call.quality >= 0 && !call.reconnecting) {
                            Spacer(Modifier.width(10.dp))
                            SignalBars(call.quality)
                        }
                    }
                    // What the line is doing to the pictures, in a word.
                    val note = when {
                        call.phase != CallPhase.ACTIVE -> null
                        call.videoPaused -> s.callVideoPaused
                        call.remotePaused -> s.callRemotePaused
                        call.remoteScreen && videoUp -> s.callTheirScreen
                        call.weak && !call.reconnecting -> s.callWeak
                        else -> null
                    }
                    if (note != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(note, color = TextSecondary, fontSize = 13.sp)
                    }
                    // Calls go end to end encrypted (WebRTC's DTLS-SRTP: not even the relay server can listen).
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(QMusicIcons.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(E2eWords(ChatMusic.english).encrypted, color = TextSecondary, fontSize = 12.sp)
                    }
                    // Subtle status banner while Telecom holds the call (e.g. a cellular call is active).
                    if (call.held && call.phase != CallPhase.ENDED) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(DarkSurfaceVariant.copy(alpha = 0.85f))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (s.english) "Call on hold" else "Arama bekletiliyor",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // Our screen is on show: one tap stops it.
                    if (call.sharingScreen) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(EndRed.copy(alpha = 0.9f)).clickable { service.stopScreenShare() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.ScreenShare, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.callSharingScreen + " · " + s.callStopSharing, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (onQuickReply != null) {
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.3f)).clickable { repliesOpen = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.Chat, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.callDeclineMessage, color = Color.White, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    SwipeToAnswer(
                        video = call.video,
                        s = s,
                        onAnswer = { onAccept(call.video) },
                        onDecline = { service.decline() },
                    )
                }
                CallPhase.ENDED -> Spacer(Modifier.height(120.dp))
                else -> AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                                DropdownMenu(
                                    expanded = routesOpen,
                                    onDismissRequest = { routesOpen = false },
                                    containerColor = DarkSurfaceVariant,
                                ) {
                                    Text(s.soundOutput, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                    call.routes.forEach { r ->
                                        DropdownMenuItem(
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
                            // The rest, once the call is on: add a person, show the screen, clean the noise.
                            if (call.phase == CallPhase.ACTIVE) {
                                Box {
                                    Toggle(QMusicIcons.More, s.callMore, moreOpen) { moreOpen = true }
                                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = DarkSurfaceVariant) {
                                        val addable = people.filter { it.uid != call.peer.uid }
                                        if (service.canAddPeople && addable.isNotEmpty()) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(QMusicIcons.PersonAdd, null, tint = Color.White) },
                                                text = { Text(s.callAddPerson, color = TextPrimary) },
                                                onClick = {
                                                    moreOpen = false
                                                    addOpen = true
                                                },
                                            )
                                        }
                                        if (shareScreen != null) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(QMusicIcons.ScreenShare, null, tint = Color.White) },
                                                text = { Text(if (call.sharingScreen) s.callStopSharing else s.callShareScreen, color = TextPrimary) },
                                                onClick = {
                                                    moreOpen = false
                                                    if (call.sharingScreen) service.stopScreenShare() else shareScreen()
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(QMusicIcons.Waveform, null, tint = Color.White) },
                                            text = { Text(s.callNoise, color = TextPrimary) },
                                            trailingIcon = { if (call.noiseSuppression) Icon(QMusicIcons.Check, null, tint = Color.White) },
                                            onClick = {
                                                moreOpen = false
                                                service.toggleNoiseSuppression()
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
        }

        // Emojis sent in the call float up from the bottom and fade.
        FloatingReactions(call.reaction)

        // Showing the screen did not start: said for a few seconds at the top.
        var shareFailShown by remember { mutableStateOf(false) }
        LaunchedEffect(call.shareFailures) {
            if (call.shareFailures > 0) {
                shareFailShown = true
                delay(4000)
                shareFailShown = false
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = shareFailShown,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6202020)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QMusicIcons.ScreenShare, null, tint = EndRed, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(s.callShareFailed, color = Color.White, fontSize = 14.sp)
            }
        }

        if (addOpen) {
            AddPersonSheet(
                s = s,
                people = people.filter { it.uid != call.peer.uid },
                onPick = { user ->
                    addOpen = false
                    service.addPerson(user)
                },
                onClose = { addOpen = false },
            )
        }
        if (repliesOpen && call.phase == CallPhase.INCOMING && onQuickReply != null) {
            QuickReplies(
                s = s,
                onPick = { text ->
                    repliesOpen = false
                    service.decline()
                    onQuickReply(call.peer, text)
                },
                onClose = { repliesOpen = false },
            )
        }
    }
    }
}

private const val CONTROLS_SHOWN_MS = 4_000L

/**
 * Answering a ringing call by swiping (as on the lock screen of a phone): the button up answers, down declines. A tap
 * makes it hop up to show the way.
 */
@Composable
private fun SwipeToAnswer(video: Boolean, s: ChatStrings, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val density = LocalDensity.current
    val up = with(density) { 120.dp.toPx() }
    val down = with(density) { 90.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    val t = rememberInfiniteTransition()
    // The arrows above climb on their own, inviting the swipe.
    val climb by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart))
    val pull = (offset.value / down).coerceIn(0f, 1f)
    val colour = lerp(AnswerGreen, EndRed, pull)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.callSwipeAnswer, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(54.dp), contentAlignment = Alignment.BottomCenter) {
            for (i in 0 until 3) {
                val phase = ((climb + i / 3f) % 1f)
                Icon(
                    QMusicIcons.ChevronUp,
                    null,
                    tint = Color.White,
                    modifier = Modifier.offset(y = (-phase * 36).dp).alpha((1f - phase) * (1f - pull)).size(22.dp),
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .size(78.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(colour)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    done -> Unit
                                    offset.value < -up -> {
                                        done = true
                                        offset.animateTo(-up * 1.4f, tween(140))
                                        onAnswer()
                                    }
                                    offset.value > down -> {
                                        done = true
                                        offset.animateTo(down * 1.3f, tween(140))
                                        onDecline()
                                    }
                                    else -> offset.animateTo(0f, spring(dampingRatio = 0.55f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    ) { change, amount ->
                        change.consume()
                        if (!done) scope.launch { offset.snapTo((offset.value + amount).coerceIn(-up * 1.5f, down * 1.4f)) }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures {
                        scope.launch {
                            offset.animateTo(-up * 0.35f, tween(160, easing = FastOutSlowInEasing))
                            offset.animateTo(0f, spring(dampingRatio = 0.4f))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (pull > 0.5f) QMusicIcons.PhoneEnd else if (video) QMusicIcons.Camera else QMusicIcons.Phone,
                contentDescription = s.callAccept,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(s.callSwipeDecline, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

/** Our picture (or theirs, swapped) small over the big one: dragged, it settles in the nearest corner. */
@Composable
private fun SmallPicture(service: CallService, remote: Boolean, bottomSpace: androidx.compose.ui.unit.Dp, onSwap: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        val w = with(density) { 110.dp.toPx() }
        val h = with(density) { 160.dp.toPx() }
        val top = with(density) { 56.dp.toPx() }
        val bottom = with(density) { bottomSpace.toPx() }
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        // 0 top left, 1 top right, 2 bottom left, 3 bottom right.
        var corner by remember { mutableIntStateOf(1) }
        var dragged by remember { mutableStateOf<Offset?>(null) }
        val target = Offset(
            if (corner % 2 == 0) 0f else (areaW - w).coerceAtLeast(0f),
            if (corner < 2) top else (areaH - h - bottom).coerceAtLeast(top),
        )
        val settled = animateOffsetAsState(target, spring(dampingRatio = 0.75f, stiffness = 400f))
        val at = dragged ?: settled.value
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .size(110.dp, 160.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .pointerInput(areaW, areaH) {
                    detectDragGestures(
                        onDragStart = { dragged = settled.value },
                        onDragEnd = {
                            val p = dragged ?: settled.value
                            val right = p.x + w / 2 > areaW / 2
                            val low = p.y + h / 2 > areaH / 2
                            corner = (if (low) 2 else 0) + (if (right) 1 else 0)
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, amount ->
                        change.consume()
                        val p = (dragged ?: settled.value) + amount
                        dragged = Offset(p.x.coerceIn(0f, (areaW - w).coerceAtLeast(0f)), p.y.coerceIn(0f, (areaH - h).coerceAtLeast(0f)))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onSwap() }) },
        ) {
            CallVideo(service, remote = remote, modifier = Modifier.fillMaxSize(), overlay = true)
        }
    }
}

/** The emojis of the call rising from the bottom, each once. */
@Composable
private fun FloatingReactions(latest: CallReaction?) {
    val shown = remember { mutableStateListOf<CallReaction>() }
    LaunchedEffect(latest?.id) {
        if (latest != null && shown.none { it.id == latest.id }) shown.add(latest)
    }
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
        val height = maxHeight
        shown.toList().forEach { r ->
            androidx.compose.runtime.key(r.id) {
                val rise = remember { Animatable(0f) }
                // Each from its own place along the bottom: ours on the right, theirs on the left.
                val lane = remember { (if (r.mine) 0.62f else 0.18f) + (r.id % 7) / 40f }
                LaunchedEffect(Unit) {
                    rise.animateTo(1f, tween(2_400, easing = LinearEasing))
                    shown.remove(r)
                }
                val v = rise.value
                Text(
                    r.emoji,
                    fontSize = 44.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = maxWidth * lane, y = -(height * 0.15f) - (height * 0.55f) * v)
                        .scale(0.6f + 0.6f * kotlin.math.min(1f, v * 4f))
                        .alpha(if (v < 0.7f) 1f else (1f - v) / 0.3f),
                )
            }
        }
    }
}

/** Who to add to the call: the person's friends, with a search. */
@Composable
internal fun AddPersonSheet(s: ChatStrings, people: List<ChatUser>, onPick: (ChatUser) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(people, query) {
        val q = query.trim()
        people.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }
    PlatformBackHandler(enabled = true) { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.75f).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(DarkSurfaceVariant)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.callAddTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(QMusicIcons.Close, contentDescription = com.qmusic.ui.A11y.close, tint = Color.White) }
            }
            Text(s.callAddNote, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            CallSearchPill(query, { query = it }, s.search, s.english)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.uid }) { user ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(user) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(user, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (user.username.isNotEmpty()) Text("@" + user.username, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                        Icon(QMusicIcons.PersonAdd, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** A ringing call declined with a few words: the ready ones. */
@Composable
private fun QuickReplies(s: ChatStrings, onPick: (String) -> Unit, onClose: () -> Unit) {
    PlatformBackHandler(enabled = true) { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(DarkSurfaceVariant)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().padding(vertical = 12.dp),
        ) {
            Text(s.callDeclineMessage, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            s.callQuickReplies.forEach { text ->
                Text(
                    text,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(text) }.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** Always-dark search input inside call sheets so light theme never produces light-on-dark controls. */
@Composable
private fun CallSearchPill(value: String, onValue: (String) -> Unit, hint: String, english: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = TextSecondary, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onValue("") }, contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.Close, contentDescription = if (english) "Clear" else "Temizle", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A small turning ring: "Reconnecting…". */
@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition()
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        drawArc(
            color = Color.White,
            startAngle = turn,
            sweepAngle = 270f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
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
        if (call.reconnecting) s.callReconnecting
        else if (call.held) (if (s.english) "On hold" else "Bekletiliyor")
        else duration(((now - (call.startedAt ?: now)) / 1000).coerceAtLeast(0))
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

/**
 * The call in the floating window while the app is out of sight: their picture in a video call; otherwise their photo
 * with the ring that follows their voice, their name and the time.
 */
@Composable
private fun CallWindow(service: CallService, call: CallState, s: ChatStrings, modifier: Modifier = Modifier) {
    val picture = call.video && call.remoteVideo && !call.remotePaused
    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
        Box(modifier.fillMaxSize().background(BgRoot), contentAlignment = Alignment.Center) {
            if (picture) {
                CallVideo(service, remote = true, modifier = Modifier.fillMaxSize(), overlay = false)
            } else {
                CallBackdrop(call.peer.photo)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                    val level by androidx.compose.animation.core.animateFloatAsState(
                        if (call.reconnecting || call.held) 0f else call.remoteLevel,
                        animationSpec = tween(120),
                    )
                    Box(contentAlignment = Alignment.Center) {
                        if (level > 0.01f) {
                            Box(
                                Modifier.size(64.dp).scale(1f + (level * 2.2f).coerceAtMost(0.45f)).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f + level * 0.25f)),
                            )
                        }
                        ChatAvatar(callerUser(call.peer), 64.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        callerShown(call.peer),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (call.held) {
                            Icon(QMusicIcons.Pause, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                        } else if (call.muted) {
                            Icon(QMusicIcons.MicOff, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(statusText(call, s), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Full-bleed PiP window for a group call: shows the first active remote video, or local video, or the group card.
 */
@Composable
internal fun GroupCallWindow(service: GroupCallService, call: GroupCallState, s: ChatStrings, modifier: Modifier = Modifier) {
    val remoteVideo = call.members.firstOrNull { it.connected && it.hasVideo && it.cameraOn }
    val showVideo = remoteVideo != null || (call.video && call.cameraOn)
    var now by remember { mutableLongStateOf(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(call.startedAt) {
        while (call.startedAt != null) {
            now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
        Box(modifier.fillMaxSize().background(BgRoot), contentAlignment = Alignment.Center) {
            if (showVideo) {
                GroupCallVideo(service, remoteVideo?.user?.uid, Modifier.fillMaxSize())
            } else {
                CallBackdrop(call.group.photo)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                    ChatAvatar(call.group, 64.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(call.group.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (call.muted) {
                            Icon(QMusicIcons.MicOff, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            call.startedAt?.let { duration(((now - it) / 1000).coerceAtLeast(0)) } ?: "${call.members.size + 1} ${s.people}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The call shrunk into a bubble (the app usable under it), and the call service it shows. */
object CallUi {
    val minimized = kotlinx.coroutines.flow.MutableStateFlow(false)
    var service by androidx.compose.runtime.mutableStateOf<CallService?>(null)
    var strings by androidx.compose.runtime.mutableStateOf<ChatStrings?>(null)
}

/**
 * Over the whole app while a 1-on-1 or group call is shrunk: a small bubble (picture and time, or video), dragged
 * anywhere and settling on the nearer side; a tap brings the call back to full screen.
 */
@Composable
fun CallBubbleOverlay() {
    OneToOneCallBubbleOverlay()
    GroupCallBubbleOverlay()
}

@Composable
private fun OneToOneCallBubbleOverlay() {
    val service = CallUi.service ?: return
    val s = CallUi.strings ?: return
    val state by service.state.collectAsState()
    val call = state ?: return
    val minimized by CallUi.minimized.collectAsState()
    if (!minimized || call.phase == CallPhase.INCOMING || call.phase == CallPhase.ENDED) return
    // Leaving the app with the call in its bubble: the floating window comes as it does from the full call screen.
    val inWindow = CallPictureInPicture(armed = true)
    if (inWindow) {
        CallWindow(service, call, s, Modifier.fillMaxSize())
        return
    }
    val video = call.video && call.remoteVideo && !call.remotePaused && call.phase == CallPhase.ACTIVE
    FloatingCallBubble(video = video, onExpand = { CallUi.minimized.value = false }) {
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

@Composable
private fun GroupCallBubbleOverlay() {
    val service = GroupCallUi.service ?: return
    val s = GroupCallUi.strings ?: CallUi.strings ?: return
    val state by service.state.collectAsState()
    val call = state ?: return
    val minimized by GroupCallUi.minimized.collectAsState()
    if (!minimized) return
    val inWindow = CallPictureInPicture(armed = true)
    if (inWindow) {
        GroupCallWindow(service, call, s, Modifier.fillMaxSize())
        return
    }
    val remoteVideo = call.members.firstOrNull { it.connected && it.hasVideo && it.cameraOn }
    val video = remoteVideo != null || (call.video && call.cameraOn)
    var now by remember { mutableLongStateOf(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(call.startedAt) {
        while (call.startedAt != null) {
            now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val label = call.startedAt?.let { duration(((now - it) / 1000).coerceAtLeast(0)) } ?: "${call.members.size + 1} ${s.people}"
    FloatingCallBubble(video = video, onExpand = { GroupCallUi.minimized.value = false }) {
        if (video) {
            GroupCallVideo(service, remoteVideo?.user?.uid, Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, Color(0xFF1DAA61), CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                    ChatAvatar(call.group, 50.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FloatingCallBubble(
    video: Boolean,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.toPx() }
    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val areaW = with(density) { maxWidth.toPx() }
            val areaH = with(density) { maxHeight.toPx() }
            val w = with(density) { (if (video) 112.dp else 84.dp).toPx() }
            val h = with(density) { (if (video) 160.dp else 104.dp).toPx() }
            var offset by remember { mutableStateOf(Offset(areaW - w - margin, with(density) { 80.dp.toPx() })) }
            val shape = RoundedCornerShape(if (video) 16.dp else 26.dp)
            Box(
                Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .shadow(14.dp, shape)
                    .clip(shape)
                    .background(Color(0xFF15151B))
                    .pointerInput(areaW, areaH) {
                        detectDragGestures(
                            onDragEnd = {
                                // To the nearer side, like the system's own bubbles.
                                val x = if (offset.x + w / 2 < areaW / 2) margin else areaW - w - margin
                                offset = Offset(x, offset.y.coerceIn(margin, areaH - h - margin))
                            },
                        ) { change, drag ->
                            change.consume()
                            offset = Offset(
                                (offset.x + drag.x).coerceIn(0f, areaW - w),
                                (offset.y + drag.y).coerceIn(0f, areaH - h),
                            )
                        }
                    }
                    .clickable(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                content()
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
