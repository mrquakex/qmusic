package com.qmusic.ringtone

import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Where in making a ringtone the work is: getting the song onto the device, then reading its sound. */
enum class RingtoneStep { FETCH, ANALYZE }

/** A song's sound, read and ready to be cut: how long it is and how loud it is along its length. */
class RingtoneSource(
    val songId: String,
    val durationMs: Long,
    /** One value (0..1) for each [com.qmusic.player.ringtone.RingtoneMath.WINDOW_MS]. */
    val energy: FloatArray,
)

/** What the editor asks for. */
data class RingtoneRequest(
    val name: String,
    val artist: String,
    val startMs: Long,
    val endMs: Long,
    val fadeIn: Boolean,
    val fadeOut: Boolean,
    val louder: Boolean,
)

/** A ringtone this app made and saved on the phone. */
@Serializable
data class MadeRingtone(
    /** Where the phone keeps it (a content address). */
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val createdAt: Long,
    val songId: String,
    val thumbnail: String? = null,
)

/** Which of the phone's sounds a ringtone can be set as. */
enum class RingtoneTarget { PHONE, NOTIFICATION, ALARM }

sealed interface ApplyResult {
    data object Done : ApplyResult

    /** The user did not allow QMusic to change the phone's sounds. */
    data object NeedsPermission : ApplyResult

    /** The phone took the request but kept its old sound, or refused it. */
    data class Failed(val reason: String? = null) : ApplyResult
}

sealed interface ContactResult {
    data class Done(val contactName: String) : ContactResult
    data object Cancelled : ContactResult
    data object NeedsPermission : ContactResult
    data class Failed(val reason: String? = null) : ContactResult
}

/**
 * What the phone does for ringtones: gets a song's sound, cuts and saves the clip, plays previews, and changes the
 * phone's sounds. The app's own logic ([RingtoneStudio]) sits on top of it.
 */
interface RingtoneMaker {
    /** False where ringtones cannot be made (the menu entry is hidden). */
    val available: Boolean

    /** Where the preview is in the song (ms) while it plays; null when it does not. */
    val previewPosition: StateFlow<Long?>

    /** The saved ringtone being played (its address), or null. */
    val playingSaved: StateFlow<String?>

    /** Gets [song] onto the device and reads its sound. */
    suspend fun load(song: SongItem, onProgress: (RingtoneStep, Float) -> Unit): Result<RingtoneSource>

    /** Drops what [load] kept for the song. */
    fun release(songId: String)

    /** Cuts [request] out of the loaded song and saves it on the phone. */
    suspend fun save(song: SongItem, request: RingtoneRequest, onProgress: (Float) -> Unit): Result<MadeRingtone>

    /** Plays the chosen part of the loaded song in a loop, with its fades. */
    fun startPreview(songId: String, startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean)

    /** The part or the fades changed while the preview plays. */
    fun updatePreview(startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean)

    /** Plays a saved ringtone once. */
    fun playSaved(ringtone: MadeRingtone)

    fun stopPreview()

    suspend fun apply(ringtone: MadeRingtone, target: RingtoneTarget): ApplyResult

    /** Asks which contact, then makes the ringtone theirs. */
    suspend fun applyToContact(ringtone: MadeRingtone): ContactResult

    /** The sounds that are set to [ringtone] right now. */
    fun inUse(ringtone: MadeRingtone): Set<RingtoneTarget>

    /** Whether the phone still has the ringtone (it may have been deleted elsewhere). */
    suspend fun exists(ringtone: MadeRingtone): Boolean

    /** Removes the ringtone from the phone; false when the phone kept the file. */
    suspend fun delete(ringtone: MadeRingtone): Boolean
}

/** Used where there are no ringtones (tests, desktop). */
object NoRingtones : RingtoneMaker {
    override val available: Boolean = false
    override val previewPosition: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
    override val playingSaved: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override suspend fun load(song: SongItem, onProgress: (RingtoneStep, Float) -> Unit): Result<RingtoneSource> =
        Result.failure(UnsupportedOperationException())

    override fun release(songId: String) = Unit

    override suspend fun save(song: SongItem, request: RingtoneRequest, onProgress: (Float) -> Unit): Result<MadeRingtone> =
        Result.failure(UnsupportedOperationException())

    override fun startPreview(songId: String, startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) = Unit
    override fun updatePreview(startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) = Unit
    override fun playSaved(ringtone: MadeRingtone) = Unit
    override fun stopPreview() = Unit
    override suspend fun apply(ringtone: MadeRingtone, target: RingtoneTarget): ApplyResult = ApplyResult.Failed()
    override suspend fun applyToContact(ringtone: MadeRingtone): ContactResult = ContactResult.Failed()
    override fun inUse(ringtone: MadeRingtone): Set<RingtoneTarget> = emptySet()
    override suspend fun exists(ringtone: MadeRingtone): Boolean = false
    override suspend fun delete(ringtone: MadeRingtone): Boolean = false
}
