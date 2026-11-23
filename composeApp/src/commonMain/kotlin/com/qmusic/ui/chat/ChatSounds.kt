package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Qeyf's own sounds (made for it, see tools/sounds): the files are res/raw/snd_<file>_<id>.ogg.
 * The person picks one of each kind in Profile and privacy → Sounds.
 */
enum class SoundKind(val file: String) {
    /** Our message went, in an open conversation. */
    SEND("send"),

    /** A message came into the conversation open on screen. */
    RECEIVE("recv"),

    /** The notification of a message (the app not on screen). */
    NOTIFY("notify"),

    /** A call (one-to-one or group) ringing here. */
    RING("ring"),

    /** What the caller hears while the other phone rings. */
    RINGBACK("ringback"),
}

/** The little sounds of the app; one switch turns them all off. */
enum class SmallSound(val file: String) {
    REC_START("recstart"),
    REC_SENT("recstop"),
    CONNECTED("connected"),
    ENDED("ended"),
}

data class SoundOption(val id: String, val tr: String, val en: String) {
    fun name(english: Boolean) = if (english) en else tr
}

object ChatSoundCatalog {
    val options: Map<SoundKind, List<SoundOption>> = mapOf(
        SoundKind.SEND to listOf(
            SoundOption("pop", "Pop", "Pop"),
            SoundOption("anahtar", "Anahtar", "Switch"),
            SoundOption("kabarcik", "Kabarcık", "Bubble"),
            SoundOption("firlat", "Fırlat", "Whoosh"),
            SoundOption("damla", "Damla", "Drop"),
            SoundOption("tik", "Tık", "Tick"),
            SoundOption("kus", "Kuş", "Chirp"),
        ),
        SoundKind.RECEIVE to listOf(
            SoundOption("bildirim", "Nuvelo mesajı", "Nuvelo message"),
            SoundOption("yeni", "Klasik", "Classic"),
            SoundOption("cit", "Çıt", "Click"),
            SoundOption("ikinota", "İki nota", "Two notes"),
            SoundOption("pit", "Pıt", "Pip"),
            SoundOption("cam", "Cam", "Glass"),
            SoundOption("dusus", "Düşüş", "Fall"),
            SoundOption("yumusak", "Yumuşak", "Soft"),
        ),
        SoundKind.NOTIFY to listOf(
            SoundOption("yeni", "Nuvelo mesajı", "Nuvelo message"),
            SoundOption("soru", "Soru", "Question"),
            SoundOption("qeyf", "Nuvelo", "Nuvelo"),
            SoundOption("cay", "Çay kaşığı", "Tea spoon"),
            SoundOption("marimba", "Marimba", "Marimba"),
            SoundOption("yukselen", "Yükselen", "Rising"),
            SoundOption("kristal", "Kristal", "Crystal"),
        ),
        SoundKind.RING to listOf(
            SoundOption("plucky", "Nuvelo zili", "Nuvelo ring"),
            SoundOption("hicaz", "Hicaz", "Hijaz"),
            SoundOption("ritim", "Nuvelo ritmi", "Nuvelo rhythm"),
            SoundOption("eskizil", "Eski zil", "Old bell"),
            SoundOption("gece", "Gece", "Night"),
            SoundOption("darbuka", "Darbuka", "Darbuka"),
        ),
        SoundKind.RINGBACK to listOf(
            SoundOption("klasik", "Klasik", "Classic"),
            SoundOption("yumusakbek", "Yumuşak", "Soft"),
            SoundOption("qeyfbek", "Nuvelo", "Nuvelo"),
        ),
    )

    /**
     * What the person chose: the small sounds from Kenney's CC0 "Interface Sounds" (tools/sounds/import_kenney.py), the
     * ringtone the 16-bar loop cut from their own track (cut_loop.py); since 2026-11-23 sending and receiving are recorded
     * sounds from Freesound (CC0, tools/sounds/fetch_candidates.py): "SimplePop1" and "UI Toast Notification".
     */
    val defaults: Map<SoundKind, String> = mapOf(
        SoundKind.SEND to "pop",
        SoundKind.RECEIVE to "bildirim",
        SoundKind.NOTIFY to "yeni",
        SoundKind.RING to "plucky",
        SoundKind.RINGBACK to "klasik",
    )

    fun option(kind: SoundKind, id: String): SoundOption =
        options.getValue(kind).firstOrNull { it.id == id } ?: options.getValue(kind).first { it.id == defaults.getValue(kind) }
}

interface ChatSounds {
    val choices: StateFlow<Map<SoundKind, String>>

    /** The sending and arriving sounds inside an open conversation. */
    val inChat: StateFlow<Boolean>

    /** Recording, call connected, call ended. */
    val small: StateFlow<Boolean>

    fun choose(kind: SoundKind, id: String)
    fun setInChat(on: Boolean)
    fun setSmall(on: Boolean)

    /** Plays [id] of [kind] once, to hear it while choosing. */
    fun preview(kind: SoundKind, id: String)

    /** The send / arrive sound of an open conversation (silent when the phone is on silent or vibrate). */
    fun play(kind: SoundKind)
    fun playSmall(sound: SmallSound)
}

@Composable
expect fun rememberChatSounds(): ChatSounds?
