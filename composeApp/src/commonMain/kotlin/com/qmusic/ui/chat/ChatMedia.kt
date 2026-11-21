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

    /** The last recording heard nothing at all (the microphone was blocked or taken): it was not kept. */
    val lastSilent: Boolean get() = false
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

/** Saves a photo's bytes where the person keeps pictures (the gallery on a phone, a download in a browser). */
@Composable
expect fun rememberImageSaver(): (bytes: ByteArray, name: String) -> Unit

/** A video message, full size, with the player's controls. */
@Composable
expect fun VideoMessagePlayer(url: String, modifier: Modifier)

/** Crop, turn and draw on a photo before it is sent; [onDone] gets the edited one. */
@Composable
expect fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit)

/**
 * Asks for the location permission when needed, then finds where the phone is now; [onPicked] gets it, or null with
 * [onError] told why ("permission", "off", "none").
 */
@Composable
expect fun rememberLocationPicker(onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit

/** Opens the phone's contacts to pick one (a name and a number). */
@Composable
expect fun rememberContactPicker(onPicked: (ChatContact) -> Unit): () -> Unit

/** Opens the phone's "new contact" screen filled with [ChatContact]'s name and number. */
@Composable
expect fun rememberContactSaver(): (ChatContact) -> Unit

/** The phone's own lock (fingerprint, face, or the screen lock code) in front of the messages. */
interface AppLock {
    /** This phone has a fingerprint, face or screen lock set up. */
    val available: Boolean
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)

    /** Shows the phone's own prompt; [onResult] hears whether the person proved it is them. */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberAppLock(): AppLock?

/**
 * Whether the messages are open now. They lock again when the app has been out of sight for a minute (a quick trip to
 * the photo picker or the camera does not lock them).
 */
object ChatLock {
    val unlocked = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var hiddenAt = 0L

    fun onHidden(now: Long) {
        hiddenAt = now
    }

    fun onShown(now: Long) {
        if (hiddenAt > 0 && now - hiddenAt > RELOCK_AFTER_MS) unlocked.value = false
        hiddenAt = 0
    }

    private const val RELOCK_AFTER_MS = 60_000L
}

/**
 * The ids of the locked conversations, kept by the app on the phone too: a notification for one of them shows
 * neither who wrote nor what (the app sets [onChanged] to save them).
 */
/**
 * The secret space's conversations (ids and names), kept by the app so a notification or a call for one shows nothing
 * of it, even with the app closed.
 */
object SecretChats {
    var ids: Set<String> = emptySet()
        private set
    var onChanged: ((Set<String>) -> Unit)? = null

    fun update(next: Set<String>) {
        if (next == ids) return
        ids = next
        onChanged?.invoke(next)
    }

    fun restore(saved: Set<String>) {
        ids = saved
    }

    /** Whether a call from a secret contact or group shows who calls (a setting; on by default). */
    var showCallerName: Boolean = true
}

/**
 * What was being written in a conversation and not sent: kept when it is left (and when the app closes), back in the
 * box when it is opened again, and shown as "Draft: ..." in the list, as on WhatsApp. Only on this phone.
 */
/** A message written now, to go at [at] (sent by the phone itself then). */
data class ScheduledMessage(val id: String, val chatId: String, val peer: ChatUser, val text: String, val at: Long)

/** The messages waiting for their time; the phone's part fills the hooks (alarms, keeping them). */
object ScheduledMessages {
    val all = kotlinx.coroutines.flow.MutableStateFlow<List<ScheduledMessage>>(emptyList())
    var schedule: (ScheduledMessage) -> Unit = {}
    var cancel: (String) -> Unit = {}
    var sendNow: (String) -> Unit = {}

    /** Whether this phone can send later (set by the phone's part; not on the desktop). */
    var supported: Boolean = false
}

object ChatDrafts {
    val all = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    var onChanged: ((Map<String, String>) -> Unit)? = null

    fun get(chatId: String): String = all.value[chatId].orEmpty()

    fun set(chatId: String, text: String) {
        val clean = text.take(4000)
        // Nothing of a secret conversation is left on the phone in the clear.
        val next = if (clean.isBlank() || chatId in SecretChats.ids) all.value - chatId else all.value + (chatId to clean)
        if (next == all.value) return
        all.value = next
        onChanged?.invoke(next)
    }

    fun restore(saved: Map<String, String>) {
        all.value = saved
    }
}

object LockedChats {
    var ids: Set<String> = emptySet()
        private set
    var onChanged: ((Set<String>) -> Unit)? = null

    fun update(next: Set<String>) {
        if (next == ids) return
        ids = next
        onChanged?.invoke(next)
    }

    /** Read back at start (from what the app saved). */
    fun restore(saved: Set<String>) {
        ids = saved
    }
}
