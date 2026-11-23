package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

enum class CallPhase {
    /** We called; their phone has not answered yet. */
    CALLING,

    /** They are calling us. */
    INCOMING,

    /** Answered; the two phones are finding a way to each other. */
    CONNECTING,
    ACTIVE,

    /** Over (shown for a moment with [CallState.endReason]). */
    ENDED,
}

/** [UNREACHABLE]: the other phone could not be told (no push address, switched off, no connection for long). */
enum class CallEnd { HUNG_UP, DECLINED, NO_ANSWER, BUSY, FAILED, CANCELLED, UNREACHABLE }

data class CallState(
    val id: String,
    val peer: ChatUser,
    val video: Boolean,
    val phase: CallPhase,
    /** When the two phones connected (for the timer); null before. */
    val startedAt: Long? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = true,
    val frontCamera: Boolean = true,
    /** The other person's picture is coming (a video call where their camera is on). */
    val remoteVideo: Boolean = false,
    val endReason: CallEnd? = null,
    /** The connection got weak (the picture or sound may stutter for a moment). */
    val weak: Boolean = false,
    /** We are calling and the other phone rings now ("Ringing…" instead of "Calling…"). */
    val reached: Boolean = false,
    /** Where the sound can go now (earpiece, speaker, a headset, Bluetooth), and where it goes. */
    val routes: List<AudioRoute> = emptyList(),
    val route: String? = null,
    /** How loud the other person is right now (0-1): the ring around their picture moves with it. */
    val remoteLevel: Float = 0f,
    /** The connection, 0 (bad) to 3 (good), from its delay and lost sound; -1 before it is known. */
    val quality: Int = -1,
    /** The connection dropped (a tunnel, Wi-Fi to mobile): the call is finding its way back ("Reconnecting…"). */
    val reconnecting: Boolean = false,
    /** Our picture was stopped by itself because the line is too weak for it; it comes back when the line does. */
    val videoPaused: Boolean = false,
    /** Their picture stopped for their weak line (their photo shows, with a note). */
    val remotePaused: Boolean = false,
    /** The other side shows their screen, not their camera (shown whole, not cropped). */
    val remoteScreen: Boolean = false,
    /** We show our screen. */
    val sharingScreen: Boolean = false,
    /** Counts the times showing our screen did not start (the phone refused it): each new one is told on screen once. */
    val shareFailures: Int = 0,
    /** Our voice cleaned of the noise around (on by default). */
    val noiseSuppression: Boolean = true,
    /** The last emoji sent in the call (by either side); a new [CallReaction.id] floats up once. */
    val reaction: CallReaction? = null,
)

/** An emoji sent during a call: it floats up over the call on both phones. */
data class CallReaction(val id: Long, val emoji: String, val mine: Boolean)

/** The emojis a call offers. */
val CALL_REACTIONS = listOf("❤️", "😂", "😮", "😢", "👏", "👍")

enum class AudioRouteKind { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

/** A place the call's sound can go: [name] is the device's own name for a headset or Bluetooth. */
data class AudioRoute(val id: String, val kind: AudioRouteKind, val name: String = "")

/** One-to-one voice and video calls. On Android: WebRTC, with signalling through the messages database. */
interface CallService {
    val state: StateFlow<CallState?>

    fun call(peer: ChatUser, video: Boolean)
    fun accept()
    fun decline()
    fun hangUp()
    fun toggleMute()
    fun toggleSpeaker()
    fun toggleCamera()
    fun switchCamera()

    /** Sends the call's sound to [id] (one of [CallState.routes]). */
    fun setAudioRoute(id: String) {}

    /** Sends an emoji that floats up on both phones. */
    fun react(emoji: String) {}

    /** Turns the cleaning of the noise around our voice on or off. */
    fun toggleNoiseSuppression() {}

    /** Whether a third person can be added here (the call then becomes a group call). */
    val canAddPeople: Boolean get() = false

    /** Adds [user]: both phones move into a group call, and [user]'s phone rings. */
    fun addPerson(user: ChatUser) {}

    /** Stops showing our screen (the camera comes back if it was on). */
    fun stopScreenShare() {}
}

/**
 * The screen sharing of a call where the phone can do it: returns what starts it (asking the person first), or null
 * where it cannot.
 */
@Composable
expect fun rememberScreenShare(service: CallService): (() -> Unit)?

/** The answer button of the ringing notification was tapped: the call screen answers as soon as it is up. */
object CallLaunch {
    /**
     * When Answer was tapped (0 when it was not). It counts only for a call ringing within a few seconds of the tap: a
     * tap that found no call (it had ended) must not answer the next one by itself.
     */
    val acceptRequestedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** How long a tap on Answer waits for its call to show up. */
    const val ACCEPT_WINDOW_MS = 15_000L

    /** "Call back" was tapped on a missed call: the app dials this person (voice or video). */
    val callBack = kotlinx.coroutines.flow.MutableStateFlow<Pair<ChatUser, Boolean>?>(null)
}

/**
 * Draws the other person's picture ([remote]) or our own camera. [overlay]: drawn over another picture (the small one
 * in the corner).
 */
@Composable
expect fun CallVideo(service: CallService, remote: Boolean, modifier: Modifier, overlay: Boolean = !remote)

/**
 * Asks for the microphone (and the camera for a video call) when they are not granted yet, then runs the action
 * with whether they were given.
 */
@Composable
expect fun rememberCallPermissions(): (video: Boolean, onResult: (Boolean) -> Unit) -> Unit

/** Opens the photo picker (up to ten); [onPicked] gets them ready to send. */
@Composable
expect fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): () -> Unit

/** Opens the camera; [onPicked] gets the photo ready to send (null when none was taken). */
@Composable
expect fun rememberCamera(onPicked: (PickedImage?) -> Unit): () -> Unit

/**
 * A video call shrinks into a floating window when the person leaves the app while it goes on ([armed]); returns
 * whether it is in that window now (the screen then shows only the picture).
 */
@Composable
expect fun CallPictureInPicture(armed: Boolean): Boolean

/** Whether this system blurs pictures (Android 12 and later; on older ones a blur does nothing). */
expect val callBlurWorks: Boolean
