package com.qmusic.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow

/** A ringtone made from a song in the music side (Zil sesi yap): where the phone keeps it, and what it is. */
data class MadeTone(val uri: String, val title: String, val artist: String, val thumbnail: String?)

/** The clip people who call this account hear while it rings (its title, and which made ringtone it is). */
data class CallerTone(val uri: String, val title: String)

/**
 * Songs as the sounds of Qeyf's calls. A ringtone made from a song in the music side can:
 * - ring for Qeyf calls (for everyone, or for one person: their own ringtone),
 * - be what people who call this account hear while it rings ("arayanlar duysun"), instead of the beeps.
 *
 * A song's sound is named "u:" + its address on the phone, next to the built-in sounds' names. The phone's part
 * (channels, players, the upload) fills the state and sets the functions; the music side and the messages use them.
 */
object CallTones {
    private const val PREFIX = "u:"

    fun songTone(uri: String) = PREFIX + uri
    fun isSong(id: String) = id.startsWith(PREFIX)
    fun uriOf(id: String) = id.removePrefix(PREFIX)

    /** The app's language (set by the app). */
    var english: Boolean = false

    /** The ringtones made from songs, newest first (kept by the music side). */
    val made = MutableStateFlow<List<MadeTone>>(emptyList())

    /** What rings for Qeyf calls: a built-in sound's name or a song's ("u:..."). */
    val ring = MutableStateFlow("")

    /** People with a ringtone of their own (their id to the sound's name). */
    val perPerson = MutableStateFlow<Map<String, String>>(emptyMap())

    /** What callers hear while it rings here, or null for Qeyf's beeps. */
    val callerTone = MutableStateFlow<CallerTone?>(null)

    /** Whether the song the person called chose plays while it rings (else Qeyf's beeps). */
    val hearOthers = MutableStateFlow(true)

    /** The caller tone is being sent to the server. */
    val publishing = MutableStateFlow(false)

    var setRing: ((String) -> Unit)? = null
    var setRingFor: ((uid: String, id: String?) -> Unit)? = null

    /** Publishes [tone] (null: none); false when it could not (not signed in, no connection). */
    var setCallerTone: (suspend (MadeTone?) -> Boolean)? = null
    var setHearOthers: ((Boolean) -> Unit)? = null
    var preview: ((String) -> Unit)? = null
    var stopPreview: (() -> Unit)? = null

    /** Songs found by a search (the music side's search). */
    var searchSongs: (suspend (String) -> List<SharedItem>)? = null

    /** Opens the music side's ringtone maker on [song] (from the messages; it comes back once the ringtone is set). */
    var makeFromSong: ((SharedItem) -> Unit)? = null

    /** The ringtone maker was opened from the messages: back there once a Qeyf use is chosen. */
    var openedFromChat: Boolean = false

    /** Whose ringtone is being made from the messages (a person's id), or null for everyone's. */
    var makingFor: String? = null
    var backToChat: (() -> Unit)? = null

    /** The made ringtone [id] stands for, or null (a built-in sound, or one deleted since). */
    fun madeOf(id: String): MadeTone? = if (isSong(id)) made.value.firstOrNull { it.uri == uriOf(id) } else null

    /** What [id] is called: the song's ringtone name, or the built-in ringtone's. */
    fun name(id: String): String =
        madeOf(id)?.title ?: if (isSong(id)) words().deletedTone else ChatSoundCatalog.option(SoundKind.RING, id).name(english)

    /** Uses [made] for Qeyf's calls (from the ringtone maker); back to the messages if it was opened from there. */
    fun ringWith(made: MadeTone) {
        val id = songTone(made.uri)
        val person = makingFor
        makingFor = null
        if (person != null) setRingFor?.invoke(person, id) else setRing?.invoke(id)
        returnToChat()
    }

    fun returnToChat() {
        if (openedFromChat) {
            openedFromChat = false
            backToChat?.invoke()
        }
    }

    fun words() = CallToneWords(english)
}

/** The words of song ringtones, in the app's language. */
class CallToneWords(english: Boolean) {
    val qeyfCalls = if (english) "Ring for Nuvelo calls" else "Nuvelo aramalarında çalsın"
    val qeyfCallsSet = if (english) "Nuvelo calls ring with it now" else "Nuvelo aramaları artık bununla çalacak"
    val callers = if (english) "Play it to people who call me" else "Beni arayanlar duysun"
    val callersNote = if (english) "Instead of beeps, while it rings" else "Çalarken dıt dıt yerine bu çalar"
    val callersSet = if (english) "People who call you will hear it" else "Seni arayanlar artık bunu duyacak"
    val callersFailed = if (english) "Could not set it: sign in to messages and check the connection" else "Ayarlanamadı: mesajlara giriş yap ve interneti kontrol et"
    val inUseQeyf = "Nuvelo"
    val inUseCallers = if (english) "Callers" else "Arayanlar"
    val fromSongs = if (english) "Your ringtones from songs" else "Şarkılardan zil seslerin"
    val makeNew = if (english) "Make one from a song" else "Şarkıdan yeni zil sesi yap"
    val makeHint = if (english) "Pick a song, choose the part, and it is set" else "Şarkıyı seç, bölümü belirle, ayarlansın"
    val qeyfSounds = if (english) "Nuvelo sounds" else "Nuvelo sesleri"
    val default = if (english) "Default" else "Varsayılan"
    val defaultNote = if (english) "The ringtone set for everyone" else "Herkes için seçili zil sesi"
    val personRing = if (english) "Ringtone" else "Zil sesi"
    val personRingTitle = if (english) "Their ringtone" else "Bu kişiye özel zil sesi"
    val callerTitle = if (english) "What callers hear" else "Arayanlar ne duysun"
    val callerNone = if (english) "Nuvelo beeps" else "Nuvelo sesi (dıt dıt)"
    val callerNoteRow = if (english) "A song plays to people calling you while it rings" else "Seni arayana çalarken bir şarkı çalar"
    val hearOthers = if (english) "Their song when I call" else "Aradığım kişinin şarkısı"
    val hearOthersNote = if (english) "If the person you call chose a song, you hear it while it rings" else "Aradığın kişi bir şarkı seçtiyse çalarken onu duyarsın"
    val publishing = if (english) "Sending..." else "Gönderiliyor..."
    val deletedTone = if (english) "Deleted ringtone" else "Silinmiş zil sesi"
    val pickSong = if (english) "Which song?" else "Hangi şarkı?"
}
