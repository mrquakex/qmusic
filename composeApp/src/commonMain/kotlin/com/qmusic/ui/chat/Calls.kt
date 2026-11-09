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

enum class CallEnd { HUNG_UP, DECLINED, NO_ANSWER, BUSY, FAILED, CANCELLED }

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
)

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
}

/** The answer button of the ringing notification was tapped: the call screen answers as soon as it is up. */
object CallLaunch {
    val acceptRequested = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Draws the other person's picture ([remote]) or our own camera. */
@Composable
expect fun CallVideo(service: CallService, remote: Boolean, modifier: Modifier)

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
