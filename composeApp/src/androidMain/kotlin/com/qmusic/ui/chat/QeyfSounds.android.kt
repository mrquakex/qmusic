package com.qmusic.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays Qeyf's sounds (res/raw/snd_*.ogg) and keeps the notification channels on the chosen ones. A channel's sound
 * cannot change once it exists, so each chosen sound has its own channel and the old ones are removed.
 */
object QeyfSounds {
    private const val PREFS = "qmusic_settings"
    private const val KEY_IN_CHAT = "sound_in_chat"
    private const val KEY_SMALL = "sound_small"
    // "2": the channels made before kept their sound as a resource NUMBER, which changes from one build to the next:
    // after an update they pointed at nothing and rang silently. Android never lets an app change a channel's sound,
    // so the channels are made anew (by name now) and the old ones are removed.
    private const val MESSAGE_PREFIX = "qeyf_msg2_"
    // "3": the ringing channel is silent now; the app rings itself ([IncomingRinger]), since some phones (a Samsung on
    // Android 16) showed the call's notification without ever playing its sound.
    private const val RING_PREFIX = "qeyf_ring3_"
    private const val OLD_MESSAGE_PREFIX = "qeyf_msg_"
    private const val OLD_RING_PREFIX = "qeyf_ring_"
    private const val OLD_RING2_PREFIX = "qeyf_ring2_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(kind: SoundKind) = "sound_" + kind.name.lowercase()
    private fun tr(context: Context) = prefs(context).getString("language", "tr") != "en"

    fun choice(context: Context, kind: SoundKind): String {
        migrate(context)
        return prefs(context).getString(key(kind), null)?.takeIf { id ->
            // The ringtone may also be a song's (made in the music side).
            (kind == SoundKind.RING && CallTones.isSong(id)) || ChatSoundCatalog.options.getValue(kind).any { it.id == id }
        } ?: ChatSoundCatalog.defaults.getValue(kind)
    }

    /**
     * Once: the old send and receive sounds (the keyboard, the old message sound) kept as a choice give way to the new
     * recorded ones, so everyone hears the new sounds; one picked again later stays.
     */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("sounds_v2", false)) return
        p.edit().apply {
            if (p.getString(key(SoundKind.SEND), null) == "enter") remove(key(SoundKind.SEND))
            if (p.getString(key(SoundKind.RECEIVE), null) == "yeni") remove(key(SoundKind.RECEIVE))
            putBoolean("sounds_v2", true)
        }.apply()
    }

    /** Whether the phone still has the song ringtone [id] (it can be deleted in the music side or elsewhere). */
    private fun songExists(context: Context, id: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(CallTones.uriOf(id)), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * What rings for a call from [uid]: their own ringtone if they have one, else the one for everyone; a song that is
     * gone is replaced by Qeyf's ringtone.
     */
    fun ringFor(context: Context, uid: String?): String {
        val id = CallTonesAndroid.ringFor(context, uid) ?: choice(context, SoundKind.RING)
        return if (CallTones.isSong(id) && !songExists(context, id)) ChatSoundCatalog.defaults.getValue(SoundKind.RING) else id
    }

    /** A channel's name part for ringtone [id] (a song's address is long: it is hashed). */
    private fun ringKey(id: String) = if (CallTones.isSong(id)) "s" + Integer.toHexString(CallTones.uriOf(id).hashCode()) else id

    fun setChoice(context: Context, kind: SoundKind, id: String) = prefs(context).edit().putString(key(kind), id).apply()
    fun inChat(context: Context) = prefs(context).getBoolean(KEY_IN_CHAT, true)
    fun small(context: Context) = prefs(context).getBoolean(KEY_SMALL, true)
    fun setInChat(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_IN_CHAT, on).apply()
    fun setSmall(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SMALL, on).apply()

    private fun resId(context: Context, file: String): Int = context.resources.getIdentifier(file, "raw", context.packageName)
    private fun fileOf(kind: SoundKind, id: String) = "snd_${kind.file}_$id"

    /** The file of [kind] as an address a notification channel or a player takes. */
    fun uri(context: Context, kind: SoundKind, id: String = choice(context, kind)): Uri? =
        if (CallTones.isSong(id)) {
            Uri.parse(CallTones.uriOf(id))
        } else {
            // By the file's name, not its number: the number changes from build to build, the name does not.
            fileOf(kind, id).takeIf { resId(context, it) != 0 }?.let { Uri.parse("android.resource://${context.packageName}/raw/$it") }
        }

    // ------------------------------------------------------------------ the notification channels

    /** The messages channel, with the chosen notification sound (made now if it is new). */
    fun messageChannelId(context: Context): String {
        val id = MESSAGE_PREFIX + choice(context, SoundKind.NOTIFY)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            val tr = tr(context)
            manager.createNotificationChannel(
                NotificationChannel(id, if (tr) "Mesajlar" else "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(uri(context, SoundKind.NOTIFY), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 120, 80, 120)
                },
            )
        }
        manager.notificationChannels.filter { (it.id.startsWith(MESSAGE_PREFIX) && it.id != id) || it.id.startsWith(OLD_MESSAGE_PREFIX) || it.id == "qmusic_messages" }
            .forEach { runCatching { manager.deleteNotificationChannel(it.id) } }
        return id
    }

    /**
     * The ringing channel for a call from [uid] (null: a group call), with its ringtone: the person's own, or the one for
     * everyone. Each ringtone has its own channel (a channel's sound is fixed); the ones no longer used are removed.
     */
    fun ringChannelId(context: Context, uid: String? = null): String {
        val sound = ringFor(context, uid)
        val id = RING_PREFIX + ringKey(sound)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            val tr = tr(context)
            manager.createNotificationChannel(
                NotificationChannel(id, if (tr) "Gelen aramalar" else "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    // No sound or vibration of its own: the app rings and vibrates itself (one ringing, not two).
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val used = (CallTonesAndroid.allPersonal(context) + choice(context, SoundKind.RING)).map { RING_PREFIX + ringKey(it) }.toSet() + id
        manager.notificationChannels.filter { (it.id.startsWith(RING_PREFIX) && it.id !in used) || it.id.startsWith(OLD_RING_PREFIX) || it.id.startsWith(OLD_RING2_PREFIX) || it.id == "qmusic_calls_ring" }
            .forEach { runCatching { manager.deleteNotificationChannel(it.id) } }
        return id
    }

    // ------------------------------------------------------------------ short sounds

    private var pool: SoundPool? = null
    private var callPool: SoundPool? = null
    private val loaded = HashMap<String, Int>()
    private val callLoaded = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    private fun poolFor(context: Context, call: Boolean): Pair<SoundPool, HashMap<String, Int>> {
        if (call) {
            callPool?.let { return it to callLoaded }
        } else {
            pool?.let { return it to loaded }
        }
        // Inside a call the little sounds go where the call's sound goes (the earpiece, the headset).
        val usage = if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        p.setOnLoadCompleteListener { sp, sampleId, status ->
            if (status == 0) {
                synchronized(ready) { ready += sampleId }
                // Asked before it had loaded: played as soon as it has.
                pendingPlay.remove(sampleId)?.let { sp.play(sampleId, 1f, 1f, 1, 0, 1f) }
            }
        }
        if (call) callPool = p else pool = p
        return p to (if (call) callLoaded else loaded)
    }

    private val pendingPlay = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun playFile(context: Context, file: String, call: Boolean, volume: Float = 1f, rate: Float = 1f) {
        val app = context.applicationContext
        val res = resId(app, file).takeIf { it != 0 } ?: return
        val (p, map) = poolFor(app, call)
        val sample = map.getOrPut(file) { p.load(app, res, 1) }
        if (synchronized(ready) { sample in ready }) p.play(sample, volume, volume, 1, 0, rate) else pendingPlay[sample] = true
    }



    /** Silent and vibrate mode keep the app's own little sounds quiet, as the phone's own do. */
    private fun ringerAllows(context: Context): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun playChat(context: Context, kind: SoundKind) {
        if (!inChat(context) || !ringerAllows(context)) return
        playFile(context, fileOf(kind, choice(context, kind)), call = false)
    }

    /** [call]: during a call (its route); otherwise like the chat sounds. */
    fun playSmall(context: Context, sound: SmallSound, call: Boolean = false) {
        if (!small(context)) return
        if (!call && !ringerAllows(context)) return
        playFile(context, "snd_small_${sound.file}", call)
    }

    // ------------------------------------------------------------------ ringing, ringback, previews

    private fun player(context: Context, kind: SoundKind, id: String, usage: Int, loop: Boolean): MediaPlayer? {
        val uri = uri(context, kind, id) ?: return null
        val type = if (CallTones.isSong(id)) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION
        return playerOf(context, uri, usage, type, loop)
    }

    private fun playerOf(context: Context, uri: Uri, usage: Int, type: Int, loop: Boolean): MediaPlayer? {
        val player = MediaPlayer()
        return runCatching {
            player.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(type).build())
                setDataSource(context.applicationContext, uri)
                isLooping = loop
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.onFailure { runCatching { player.release() } }.getOrNull()
    }

    /** The ringtone for a call from [uid], again and again, at the ring volume (silent when the phone is on silent). */
    fun startRingtone(context: Context, uid: String? = null): MediaPlayer? =
        player(context, SoundKind.RING, ringFor(context, uid), AudioAttributes.USAGE_NOTIFICATION_RINGTONE, loop = true)
            ?: player(context, SoundKind.RING, ChatSoundCatalog.defaults.getValue(SoundKind.RING), AudioAttributes.USAGE_NOTIFICATION_RINGTONE, loop = true)

    /**
     * What the caller hears while it rings at the other end, in the call's sound route: [song] (what the called person
     * chose for their callers) when there is one, else the chosen ringback.
     */
    fun startRingback(context: Context, song: java.io.File? = null): MediaPlayer? {
        if (song != null) {
            playerOf(context, Uri.fromFile(song), AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, AudioAttributes.CONTENT_TYPE_MUSIC, loop = true)?.let { return it }
        }
        return player(context, SoundKind.RINGBACK, choice(context, SoundKind.RINGBACK), AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, loop = true)
    }

    fun stopPreview() {
        stop(preview)
        preview = null
    }

    fun stop(player: MediaPlayer?) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
    }

    private var preview: MediaPlayer? = null

    fun preview(context: Context, kind: SoundKind, id: String) {
        stop(preview)
        preview = player(context, kind, id, AudioAttributes.USAGE_MEDIA, loop = false)?.also { p -> p.setOnCompletionListener { stop(it); if (preview === it) preview = null } }
    }
}

private class AndroidChatSounds(private val context: Context) : ChatSounds {
    private val _choices = MutableStateFlow(SoundKind.entries.associateWith { QeyfSounds.choice(context, it) })
    override val choices: StateFlow<Map<SoundKind, String>> = _choices.asStateFlow()
    private val _inChat = MutableStateFlow(QeyfSounds.inChat(context))
    override val inChat: StateFlow<Boolean> = _inChat.asStateFlow()
    private val _small = MutableStateFlow(QeyfSounds.small(context))
    override val small: StateFlow<Boolean> = _small.asStateFlow()

    override fun choose(kind: SoundKind, id: String) {
        QeyfSounds.setChoice(context, kind, id)
        _choices.value = _choices.value + (kind to id)
        // The channel with the new sound is made right away, so the next notification already uses it.
        when (kind) {
            SoundKind.NOTIFY -> QeyfSounds.messageChannelId(context)
            SoundKind.RING -> QeyfSounds.ringChannelId(context)
            else -> Unit
        }
    }

    override fun setInChat(on: Boolean) {
        QeyfSounds.setInChat(context, on)
        _inChat.value = on
    }

    override fun setSmall(on: Boolean) {
        QeyfSounds.setSmall(context, on)
        _small.value = on
    }

    override fun preview(kind: SoundKind, id: String) = QeyfSounds.preview(context, kind, id)
    override fun play(kind: SoundKind) = QeyfSounds.playChat(context, kind)
    override fun playSmall(sound: SmallSound) = QeyfSounds.playSmall(context, sound)

}

@Composable
actual fun rememberChatSounds(): ChatSounds? {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidChatSounds(context) }
}

/**
 * The ringing of an incoming call, one at a time, whoever starts it: the call service (the app open or not) or the
 * call's notification (woken by the push). The app plays the ringtone itself, as WhatsApp does: some phones show a
 * call's notification without ever playing the notification's sound. It keeps to the phone's mode: silent is silent,
 * vibrate only vibrates, and Do not disturb keeps it quiet. It stops by itself after [forMs] at the latest.
 */
object IncomingRinger {
    private var player: MediaPlayer? = null
    private var key: String? = null
    private var vibrating: android.os.Vibrator? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    /** Rings for [key] (a call's id); the same call again goes on ringing as it was. */
    @Synchronized
    fun start(context: Context, key: String, uid: String?, forMs: Long = 50_000L) {
        if (this.key == key) return
        stopNow()
        this.key = key
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = app.getSystemService(NotificationManager::class.java)
        val quiet = nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val mode = audio.ringerMode
        if (!quiet && mode == AudioManager.RINGER_MODE_NORMAL) player = QeyfSounds.startRingtone(app, uid)
        if (!quiet && mode != AudioManager.RINGER_MODE_SILENT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            runCatching {
                v?.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
            }
            vibrating = v
        }
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, forMs)
        CallHooks.trace?.invoke(
            "ringer: ${if (player != null) "sound" else "NO sound"}, ${if (vibrating != null) "vibration" else "no vibration"}" +
                (if (quiet) " (Do not disturb)" else ""),
        )
    }

    /** The call was answered, declined, given up or taken over: the ringing stops. */
    @Synchronized
    fun stop() {
        stopNow()
    }

    private fun stopNow() {
        main.removeCallbacks(autoStop)
        QeyfSounds.stop(player)
        player = null
        runCatching { vibrating?.cancel() }
        vibrating = null
        key = null
    }
}
