package com.qmusic.ringtone

import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerController
import com.qmusic.player.ringtone.ClipRange
import com.qmusic.player.ringtone.RingtoneMath
import com.qmusic.settings.AppSettings
import com.qmusic.settings.SettingKeys
import com.qmusic.ui.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where the ringtone being made is. */
enum class RingtonePhase { LOADING, READY, SAVING, SAVED, FAILED }

/** The ringtone being made, as the editor shows it. */
data class RingtoneDraft(
    val song: SongItem,
    val phase: RingtonePhase = RingtonePhase.LOADING,
    /** Which part of loading is running, and how far (0..1). */
    val step: RingtoneStep = RingtoneStep.FETCH,
    val progress: Float = 0f,
    val durationMs: Long = 0L,
    /** The song drawn as bars (0..1). */
    val bars: List<Float> = emptyList(),
    val range: ClipRange = ClipRange(0L, 0L),
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
    val louder: Boolean = true,
    val name: String = "",
    val previewing: Boolean = false,
    /** The saved ringtone, once there is one, and what it was set as. */
    val made: MadeRingtone? = null,
    val applied: Set<RingtoneTarget> = emptySet(),
    val contactName: String? = null,
    val error: String? = null,
)

/**
 * Making ringtones: the editor's state and what its buttons do, and the list of ringtones made so far. The phone's
 * part (audio, saving, settings) is [maker]'s.
 */
class RingtoneStudio(
    private val maker: RingtoneMaker,
    private val player: PlayerController,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val strings: () -> AppStrings,
    private val message: (String) -> Unit,
    private val isOnline: () -> Boolean,
) {
    val available: Boolean get() = maker.available

    private val _draft = MutableStateFlow<RingtoneDraft?>(null)
    val draft: StateFlow<RingtoneDraft?> = _draft.asStateFlow()

    /** Where the preview is in the song (ms), or null while it is not playing. */
    val previewPosition: StateFlow<Long?> = maker.previewPosition

    /** The saved ringtone being played in the list. */
    val playingSaved: StateFlow<String?> = maker.playingSaved

    private var energy: FloatArray = FloatArray(0)
    private var job: Job? = null
    private var pausedMusic = false

    // ------------------------------------------------------------------ opening and closing

    /** Opens the editor for [song]: gets it onto the device and finds the best part to start with. */
    fun open(song: SongItem) {
        job?.cancel()
        maker.stopPreview()
        _draft.value = RingtoneDraft(song = song, name = song.title.take(MAX_NAME))
        job = scope.launch {
            val s = strings()
            val seconds = song.duration
            if (seconds != null && seconds > MAX_SONG_SECONDS) {
                fail(s.ringtoneErrorTooLong)
                return@launch
            }
            maker.load(song) { step, progress ->
                _draft.update { it?.takeIf { d -> d.phase == RingtonePhase.LOADING }?.copy(step = step, progress = progress) ?: it }
            }.onSuccess { source ->
                energy = source.energy
                val length = RingtoneMath.defaultLength(source.durationMs)
                val start = RingtoneMath.suggestStart(energy, length).coerceAtMost((source.durationMs - length).coerceAtLeast(0L))
                _draft.update {
                    it?.copy(
                        phase = RingtonePhase.READY,
                        durationMs = source.durationMs,
                        bars = RingtoneMath.bars(energy, BAR_COUNT).toList(),
                        range = ClipRange(start, start + length),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail(
                    when {
                        error.message?.contains("Too long") == true -> s.ringtoneErrorTooLong
                        !isOnline() -> s.ringtoneErrorOffline
                        else -> s.ringtoneErrorGeneric
                    },
                )
            }
        }
    }

    /** The editor was left: preview stopped, the song's file dropped, the music that was playing carries on. */
    fun close() {
        val songId = _draft.value?.song?.id
        job?.cancel()
        job = null
        maker.stopPreview()
        songId?.let(maker::release)
        _draft.value = null
        energy = FloatArray(0)
        if (pausedMusic) {
            pausedMusic = false
            player.resume()
        }
    }

    private fun fail(text: String) {
        _draft.update { it?.copy(phase = RingtonePhase.FAILED, error = text) }
    }

    fun retry() {
        _draft.value?.song?.let(::open)
    }

    // ------------------------------------------------------------------ editing

    private fun edit(change: (RingtoneDraft) -> RingtoneDraft) {
        val before = _draft.value ?: return
        if (before.phase != RingtonePhase.READY) return
        val after = change(before)
        _draft.value = after
        if (after.previewing) maker.updatePreview(after.range.startMs, after.range.endMs, after.fadeIn, after.fadeOut)
    }

    fun setStart(ms: Long) = edit { it.copy(range = RingtoneMath.moveStart(it.range, ms, it.durationMs)) }

    fun setEnd(ms: Long) = edit { it.copy(range = RingtoneMath.moveEnd(it.range, ms, it.durationMs)) }

    fun moveClip(startMs: Long) = edit { it.copy(range = RingtoneMath.moveClip(it.range, startMs, it.durationMs)) }

    /** The start or the end nudged by [deltaMs] (the small − and + buttons). */
    fun nudgeStart(deltaMs: Long) = edit { it.copy(range = RingtoneMath.moveStart(it.range, it.range.startMs + deltaMs, it.durationMs)) }

    fun nudgeEnd(deltaMs: Long) = edit { it.copy(range = RingtoneMath.moveEnd(it.range, it.range.endMs + deltaMs, it.durationMs)) }

    /** Goes back to the part with the most going on in it. */
    fun suggest() = edit {
        val length = it.range.lengthMs.coerceAtLeast(RingtoneMath.MIN_LENGTH_MS)
        val start = RingtoneMath.suggestStart(energy, length)
        it.copy(range = RingtoneMath.moveClip(ClipRange(start, start + length), start, it.durationMs))
    }

    fun setFadeIn(on: Boolean) = edit { it.copy(fadeIn = on) }

    fun setFadeOut(on: Boolean) = edit { it.copy(fadeOut = on) }

    fun setLouder(on: Boolean) = edit { it.copy(louder = on) }

    fun setName(text: String) {
        _draft.update { it?.copy(name = text.take(MAX_NAME)) }
    }

    // ------------------------------------------------------------------ preview

    fun togglePreview() {
        val d = _draft.value ?: return
        if (d.phase != RingtonePhase.READY) return
        if (d.previewing) {
            maker.stopPreview()
            _draft.update { it?.copy(previewing = false) }
            return
        }
        // The music in the app steps aside while the clip plays.
        if (player.isPlaying.value) {
            player.pause()
            pausedMusic = true
        }
        maker.startPreview(d.song.id, d.range.startMs, d.range.endMs, d.fadeIn, d.fadeOut)
        _draft.update { it?.copy(previewing = true) }
    }

    // ------------------------------------------------------------------ saving and setting

    fun save() {
        val d = _draft.value ?: return
        if (d.phase != RingtonePhase.READY) return
        maker.stopPreview()
        _draft.value = d.copy(phase = RingtonePhase.SAVING, previewing = false, progress = 0f)
        job = scope.launch {
            val request = RingtoneRequest(
                name = d.name.trim().ifBlank { d.song.title }.take(MAX_NAME),
                artist = d.song.artists.joinToString(", ") { it.name },
                startMs = d.range.startMs,
                endMs = d.range.endMs,
                fadeIn = d.fadeIn,
                fadeOut = d.fadeOut,
                louder = d.louder,
            )
            maker.save(d.song, request) { p -> _draft.update { it?.copy(progress = p) } }
                .onSuccess { made ->
                    remember(made)
                    _draft.update { it?.copy(phase = RingtonePhase.SAVED, made = made, applied = emptySet(), contactName = null) }
                    refreshInUse()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _draft.update { it?.copy(phase = RingtonePhase.READY) }
                    message(strings().ringtoneSaveFailed)
                }
        }
    }

    /** From the finished screen back to the editor, to make another one (another part, other fades) from the song. */
    fun editAgain() {
        _draft.update { it?.copy(phase = RingtonePhase.READY, made = null, applied = emptySet(), contactName = null) }
    }

    fun apply(target: RingtoneTarget) {
        val made = _draft.value?.made ?: return
        scope.launch {
            if (setAs(made, target)) {
                _draft.update { it?.copy(applied = it.applied + target) }
            }
        }
    }

    fun applyToContact() {
        val made = _draft.value?.made ?: return
        scope.launch {
            assignToContact(made)?.let { name -> _draft.update { it?.copy(contactName = name) } }
        }
    }

    /** Sets [made] as [target] and says how it went; true when the phone has it now. */
    private suspend fun setAs(made: MadeRingtone, target: RingtoneTarget): Boolean {
        val s = strings()
        when (maker.apply(made, target)) {
            ApplyResult.Done -> {
                message(
                    when (target) {
                        RingtoneTarget.PHONE -> s.ringtonePhoneSet
                        RingtoneTarget.NOTIFICATION -> s.ringtoneNotificationSet
                        RingtoneTarget.ALARM -> s.ringtoneAlarmSet
                    },
                )
                refreshInUse()
                return true
            }

            ApplyResult.NeedsPermission -> message(s.ringtoneNeedsPermission)
            is ApplyResult.Failed -> message(s.ringtoneNotAccepted)
        }
        return false
    }

    /** Asks for a contact and gives them [made]; their name when it worked. */
    private suspend fun assignToContact(made: MadeRingtone): String? {
        val s = strings()
        return when (val result = maker.applyToContact(made)) {
            is ContactResult.Done -> {
                message(s.ringtoneContactSet(result.contactName))
                result.contactName.ifBlank { "·" }
            }

            ContactResult.Cancelled -> null
            ContactResult.NeedsPermission -> {
                message(s.ringtoneContactsPermission)
                null
            }

            is ContactResult.Failed -> {
                message(s.ringtoneNotAccepted)
                null
            }
        }
    }

    // ------------------------------------------------------------------ the list: "My ringtones"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MadeRingtone.serializer())

    private fun parse(text: String): List<MadeRingtone> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    /** The ringtones made so far, newest first. */
    val saved: StateFlow<List<MadeRingtone>> = settings.string(SettingKeys.RINGTONES, "")
        .map(::parse)
        .stateIn(scope, SharingStarted.Eagerly, parse(settings.string(SettingKeys.RINGTONES, "").value))

    private val _inUse = MutableStateFlow<Map<String, Set<RingtoneTarget>>>(emptyMap())

    /** For each saved ringtone (by address), which of the phone's sounds it is set as. */
    val inUse: StateFlow<Map<String, Set<RingtoneTarget>>> = _inUse.asStateFlow()

    /** The list as it is stored now (the [saved] flow follows a moment later, so changes are made on this). */
    private fun current(): List<MadeRingtone> = parse(settings.string(SettingKeys.RINGTONES, "").value)

    private fun write(list: List<MadeRingtone>) {
        settings.setString(SettingKeys.RINGTONES, if (list.isEmpty()) "" else json.encodeToString(serializer, list))
    }

    private fun remember(made: MadeRingtone) {
        write(listOf(made) + current().filter { it.uri != made.uri })
    }

    private fun refreshInUse() {
        _inUse.value = current().associate { it.uri to maker.inUse(it) }
    }

    /** Opening the list: ringtones the phone no longer has are dropped, and what each is set as is read again. */
    fun refreshList() {
        scope.launch {
            val all = current()
            val alive = all.filter { maker.exists(it) }
            if (alive.size != all.size) write(alive)
            refreshInUse()
        }
    }

    fun togglePlay(made: MadeRingtone) {
        if (playingSaved.value == made.uri) {
            maker.stopPreview()
            return
        }
        if (player.isPlaying.value) {
            player.pause()
            pausedMusic = true
        }
        maker.playSaved(made)
    }

    fun stopPlaying() {
        maker.stopPreview()
    }

    fun applySaved(made: MadeRingtone, target: RingtoneTarget) {
        scope.launch { setAs(made, target) }
    }

    fun applySavedToContact(made: MadeRingtone) {
        scope.launch { assignToContact(made) }
    }

    fun delete(made: MadeRingtone) {
        scope.launch {
            maker.stopPreview()
            val removed = maker.delete(made)
            write(current().filter { it.uri != made.uri })
            refreshInUse()
            message(if (removed) strings().ringtoneDeleted else strings().ringtoneDeleteKept)
        }
    }

    /** The list was left: a ringtone being played stops, and the music that was playing carries on. */
    fun leaveList() {
        maker.stopPreview()
        if (pausedMusic && _draft.value == null) {
            pausedMusic = false
            player.resume()
        }
    }

    companion object {
        const val BAR_COUNT = 120
        const val MAX_NAME = 60

        /** A song longer than this is not for a ringtone (and would be a very large file to read). */
        const val MAX_SONG_SECONDS = 20 * 60
    }
}
