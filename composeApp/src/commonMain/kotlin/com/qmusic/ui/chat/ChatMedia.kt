package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Records a voice message (held down the microphone button). */
interface VoiceRecorder {
    /** How loud it is now (0-1), for the moving bars while recording. */
    val level: StateFlow<Float>

    /** Starts recording; false when the microphone cannot be had. */
    fun start(): Boolean

    /** Stops and gives the recording ready to send (null when too short or it failed). */
    fun stop(): PickedFile?

    /** Stops and throws the recording away. */
    fun cancel()
}

/** Plays voice messages, one at a time; the music pauses meanwhile. */
interface VoicePlayer {
    /** The link of the voice message playing (null when none). */
    val playing: StateFlow<String?>

    /** How far it is (0-1). */
    val progress: StateFlow<Float>

    /** Plays [url] (from the start or where it was paused), or pauses it when it plays. */
    fun toggle(url: String)

    /** Jumps to [fraction] of [url] (when it is the one playing). */
    fun seek(url: String, fraction: Float)
    fun stop()

    /** How fast voice messages play: 1, 1.5 or 2. */
    val speed: StateFlow<Float>

    /** 1× → 1.5× → 2× → 1×. */
    fun cycleSpeed()
}

@Composable
expect fun rememberVoiceRecorder(): VoiceRecorder?

@Composable
expect fun rememberVoicePlayer(): VoicePlayer?

/** Opens the system picker for a video ([video]) or any file; [onPicked] gets it ready to send. */
@Composable
expect fun rememberFilePicker(video: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit

/** Opens a file someone sent: it is downloaded into the phone's Downloads, with a notification that opens it. */
@Composable
expect fun rememberAttachmentOpener(): (ChatAttachment) -> Unit

/** A video message, full size, with the player's controls. */
@Composable
expect fun VideoMessagePlayer(url: String, modifier: Modifier)

/** Crop, turn and draw on a photo before it is sent; [onDone] gets the edited one. */
@Composable
expect fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit)
