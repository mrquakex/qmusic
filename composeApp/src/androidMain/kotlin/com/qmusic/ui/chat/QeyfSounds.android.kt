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
    private const val MESSAGE_PREFIX = "qeyf_msg_"
    private const val RING_PREFIX = "qeyf_ring_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(kind: SoundKind) = "sound_" + kind.name.lowercase()
    private fun tr(context: Context) = prefs(context).getString("language", "tr") != "en"

    fun choice(context: Context, kind: SoundKind): String =
        prefs(context).getString(key(kind), null)?.takeIf { id -> ChatSoundCatalog.options.getValue(kind).any { it.id == id } }
            ?: ChatSoundCatalog.defaults.getValue(kind)

    fun setChoice(context: Context, kind: SoundKind, id: String) = prefs(context).edit().putString(key(kind), id).apply()
    fun inChat(context: Context) = prefs(context).getBoolean(KEY_IN_CHAT, true)
    fun small(context: Context) = prefs(context).getBoolean(KEY_SMALL, true)
    fun setInChat(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_IN_CHAT, on).apply()
    fun setSmall(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SMALL, on).apply()

    private fun resId(context: Context, file: String): Int = context.resources.getIdentifier(file, "raw", context.packageName)
    private fun fileOf(kind: SoundKind, id: String) = "snd_${kind.file}_$id"

    /** The file of [kind] as an address a notification channel or a player takes. */
    fun uri(context: Context, kind: SoundKind, id: String = choice(context, kind)): Uri? =
        resId(context, fileOf(kind, id)).takeIf { it != 0 }?.let { Uri.parse("android.resource://${context.packageName}/$it") }

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
        manager.notificationChannels.filter { (it.id.startsWith(MESSAGE_PREFIX) && it.id != id) || it.id == "qmusic_messages" }
            .forEach { runCatching { manager.deleteNotificationChannel(it.id) } }
        return id
    }

    /** The ringing channel (one-to-one and group calls), with the chosen ringtone. */
    fun ringChannelId(context: Context): String {
        val id = RING_PREFIX + choice(context, SoundKind.RING)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            val tr = tr(context)
            manager.createNotificationChannel(
                NotificationChannel(id, if (tr) "Gelen aramalar" else "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(uri(context, SoundKind.RING), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 800)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        manager.notificationChannels.filter { (it.id.startsWith(RING_PREFIX) && it.id != id) || it.id == "qmusic_calls_ring" }
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

    private fun playFile(context: Context, file: String, call: Boolean) {
        val app = context.applicationContext
        val res = resId(app, file).takeIf { it != 0 } ?: return
        val (p, map) = poolFor(app, call)
        val sample = map.getOrPut(file) { p.load(app, res, 1) }
        if (synchronized(ready) { sample in ready }) p.play(sample, 1f, 1f, 1, 0, 1f) else pendingPlay[sample] = true
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
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context.applicationContext, uri)
                isLooping = loop
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.getOrNull()
    }

    /** The ringtone, again and again, at the ring volume (silent when the phone is on silent). */
    fun startRingtone(context: Context): MediaPlayer? = player(context, SoundKind.RING, choice(context, SoundKind.RING), AudioAttributes.USAGE_NOTIFICATION_RINGTONE, loop = true)

    /** What the caller hears while it rings at the other end, in the call's sound route. */
    fun startRingback(context: Context): MediaPlayer? =
        player(context, SoundKind.RINGBACK, choice(context, SoundKind.RINGBACK), AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, loop = true)

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
