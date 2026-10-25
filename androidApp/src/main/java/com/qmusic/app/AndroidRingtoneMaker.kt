package com.qmusic.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.qmusic.engine.models.SongItem
import com.qmusic.player.ringtone.ClipRange
import com.qmusic.player.ringtone.ExportSourceFetcher
import com.qmusic.player.ringtone.RingtoneCodec
import com.qmusic.player.ringtone.RingtoneMath
import com.qmusic.ringtone.ApplyResult
import com.qmusic.ringtone.ContactResult
import com.qmusic.ringtone.MadeRingtone
import com.qmusic.ringtone.RingtoneMaker
import com.qmusic.ringtone.RingtoneRequest
import com.qmusic.ringtone.RingtoneSource
import com.qmusic.ringtone.RingtoneStep
import com.qmusic.ringtone.RingtoneTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Ringtones on an Android phone: the song's sound is kept in a cache file while it is edited, the clip is cut to an
 * .m4a and saved into the phone's Ringtones folder (so the system's own sound pickers list it too), and the phone's
 * sounds are changed through the system's ringtone manager (which needs the "change system settings" permission).
 */
class AndroidRingtoneMaker(private val context: Context) : RingtoneMaker {
    override val available: Boolean = true

    private val _previewPosition = MutableStateFlow<Long?>(null)
    override val previewPosition: StateFlow<Long?> = _previewPosition.asStateFlow()

    private val _playingSaved = MutableStateFlow<String?>(null)
    override val playingSaved: StateFlow<String?> = _playingSaved.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sources = ConcurrentHashMap<String, File>()
    private val work: File = File(context.cacheDir, "ringtone")

    /** What each of the phone's sounds was before this app first changed it, to put back when that ringtone is deleted. */
    private val previous = context.getSharedPreferences("qmusic_ringtones", Context.MODE_PRIVATE)

    private fun previousKey(target: RingtoneTarget) = "before_${target.name}"

    init {
        // Leftovers of an earlier run (the app was closed while editing) are not kept.
        runCatching { work.listFiles()?.forEach { it.delete() } }
    }

    // ------------------------------------------------------------------ loading

    override suspend fun load(song: SongItem, onProgress: (RingtoneStep, Float) -> Unit): Result<RingtoneSource> =
        runCatching {
            val file = File(work, "${song.id.filter { it.isLetterOrDigit() }.take(40)}.src")
            ExportSourceFetcher.fetch(context, song, file) { onProgress(RingtoneStep.FETCH, it) }
            sources[song.id] = file
            val shape = try {
                withContext(Dispatchers.Default) {
                    RingtoneCodec.analyze(file, isActive = { isActive }) { onProgress(RingtoneStep.ANALYZE, it) }
                }
            } catch (failure: Throwable) {
                release(song.id)
                throw failure
            }
            check(shape.durationMs >= MIN_SONG_MS) { "Too short" }
            RingtoneSource(song.id, shape.durationMs, shape.energy)
        }

    override fun release(songId: String) {
        sources.remove(songId)?.delete()
    }

    // ------------------------------------------------------------------ saving

    override suspend fun save(song: SongItem, request: RingtoneRequest, onProgress: (Float) -> Unit): Result<MadeRingtone> =
        runCatching {
            val source = sources[song.id] ?: error("The song is not loaded")
            val clip = ClipRange(request.startMs, request.endMs)
            val fade = minOf(FADE_MS, clip.lengthMs / 4)
            val encoded = File(work, "made-${System.nanoTime()}.m4a")
            try {
                withContext(Dispatchers.Default) {
                    RingtoneCodec.export(
                        file = source,
                        range = clip,
                        fadeInMs = if (request.fadeIn) fade else 0L,
                        fadeOutMs = if (request.fadeOut) fade else 0L,
                        louder = request.louder,
                        output = encoded,
                        isActive = { isActive },
                        onProgress = onProgress,
                    )
                }
                val uri = withContext(Dispatchers.IO) { store(encoded, request) }
                MadeRingtone(
                    uri = uri.toString(),
                    title = request.name,
                    artist = request.artist,
                    durationMs = clip.lengthMs,
                    createdAt = System.currentTimeMillis(),
                    songId = song.id,
                    thumbnail = song.thumbnail ?: song.thumbnails.lastOrNull()?.url,
                )
            } finally {
                encoded.delete()
            }
        }

    /** Puts the finished file where the phone keeps ringtones, and gives back the address the phone knows it by. */
    private suspend fun store(file: File, request: RingtoneRequest): Uri {
        val fileName = safeName(request.name) + ".m4a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, request.name)
                put(MediaStore.Audio.Media.ARTIST, request.artist)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/QMusic")
                // Asked for as all three; the phone keeps the ones its Ringtones folder allows (the system's own pickers
                // list it as a ringtone, and it can be set as the other sounds from here all the same).
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.Media.IS_ALARM, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("The phone did not accept the file")
            try {
                resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (failure: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw failure
            }
            return uri
        }
        // Android 8 and 9: the app's own Ringtones folder (no storage permission needed), made known to the media scanner.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_RINGTONES) ?: error("No storage")
        dir.mkdirs()
        var target = File(dir, fileName)
        var n = 1
        while (target.exists()) target = File(dir, safeName(request.name) + " (${n++}).m4a")
        file.copyTo(target)
        return suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mp4")) { _, uri ->
                if (cont.isActive) cont.resume(uri ?: Uri.fromFile(target))
            }
        }
    }

    private fun safeName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "QMusic" }

    // ------------------------------------------------------------------ previews

    private var player: MediaPlayer? = null
    private var loop: Job? = null

    @Volatile
    private var clip = ClipRange(0, 0)

    @Volatile
    private var fadeIn = false

    @Volatile
    private var fadeOut = false

    override fun startPreview(songId: String, startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) {
        stopPreview()
        val file = sources[songId] ?: return
        updatePreview(startMs, endMs, fadeIn, fadeOut)
        val mp = newPlayer() ?: return
        player = mp
        runCatching { mp.setDataSource(file.absolutePath) }.onFailure {
            stopPreview()
            return
        }
        mp.setOnPreparedListener { prepared ->
            prepared.seekTo(clip.startMs, MediaPlayer.SEEK_CLOSEST)
            prepared.start()
            loop = scope.launch {
                while (isActive && player === prepared) {
                    val position = runCatching { prepared.currentPosition.toLong() }.getOrNull() ?: break
                    val range = clip
                    if (position >= range.endMs || position < range.startMs - SEEK_TOLERANCE_MS) {
                        // The end of the part: round again, from its start.
                        runCatching { prepared.seekTo(range.startMs, MediaPlayer.SEEK_CLOSEST) }
                    } else {
                        // The fades, as they will sound in the finished ringtone.
                        val into = (position - range.startMs).coerceAtLeast(0L)
                        val total = range.lengthMs
                        val fade = minOf(FADE_MS, total / 4)
                        val volume = RingtoneMath.fadeGain(into, total, if (this@AndroidRingtoneMaker.fadeIn) fade else 0L, if (this@AndroidRingtoneMaker.fadeOut) fade else 0L)
                        runCatching { prepared.setVolume(volume, volume) }
                        _previewPosition.value = position
                    }
                    delay(POLL_MS)
                }
            }
        }
        mp.setOnCompletionListener { done ->
            // The part reaches the very end of the song: start it again.
            runCatching {
                done.seekTo(clip.startMs, MediaPlayer.SEEK_CLOSEST)
                done.start()
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            stopPreview()
            true
        }
        runCatching { mp.prepareAsync() }.onFailure { stopPreview() }
    }

    override fun updatePreview(startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) {
        clip = ClipRange(startMs, endMs)
        this.fadeIn = fadeIn
        this.fadeOut = fadeOut
    }

    override fun playSaved(ringtone: MadeRingtone) {
        stopPreview()
        val mp = newPlayer() ?: return
        player = mp
        runCatching { mp.setDataSource(context, Uri.parse(ringtone.uri)) }.onFailure {
            stopPreview()
            return
        }
        mp.setOnPreparedListener {
            _playingSaved.value = ringtone.uri
            it.start()
        }
        mp.setOnCompletionListener { stopPreview() }
        mp.setOnErrorListener { _, _, _ ->
            stopPreview()
            true
        }
        runCatching { mp.prepareAsync() }.onFailure { stopPreview() }
    }

    override fun stopPreview() {
        loop?.cancel()
        loop = null
        player?.let { mp ->
            player = null
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        _previewPosition.value = null
        _playingSaved.value = null
    }

    private fun newPlayer(): MediaPlayer? =
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
            }
        }.getOrNull()

    // ------------------------------------------------------------------ setting the phone's sounds

    private fun typeOf(target: RingtoneTarget): Int = when (target) {
        RingtoneTarget.PHONE -> RingtoneManager.TYPE_RINGTONE
        RingtoneTarget.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        RingtoneTarget.ALARM -> RingtoneManager.TYPE_ALARM
    }

    /** The media id at the end of an address, to compare two addresses of the same file written differently. */
    private fun idOf(uri: Uri?): Long? = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }

    override suspend fun apply(ringtone: MadeRingtone, target: RingtoneTarget): ApplyResult {
        if (!Settings.System.canWrite(context)) {
            // Changing the phone's sounds is a special permission: the system's settings page asks for it.
            RingtoneBridge.openWriteSettings?.invoke()
            if (!Settings.System.canWrite(context)) return ApplyResult.NeedsPermission
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(ringtone.uri)
                // The sound that was there is remembered once (not the ringtones made here, one after the other).
                if (!previous.contains(previousKey(target))) {
                    RingtoneManager.getActualDefaultRingtoneUri(context, typeOf(target))
                        ?.takeIf { idOf(it) != idOf(uri) }
                        ?.let { previous.edit().putString(previousKey(target), it.toString()).apply() }
                }
                RingtoneManager.setActualDefaultRingtoneUri(context, typeOf(target), uri)
                // Some phones take the request and quietly keep the old sound: look at what is set now.
                val now = RingtoneManager.getActualDefaultRingtoneUri(context, typeOf(target))
                if (idOf(now) != null && idOf(now) == idOf(uri)) ApplyResult.Done else ApplyResult.Failed("Not kept")
            }.getOrElse { ApplyResult.Failed(it.message) }
        }
    }

    override suspend fun applyToContact(ringtone: MadeRingtone): ContactResult {
        val granted = RingtoneBridge.requestContactsPermission?.invoke() ?: false
        if (!granted) return ContactResult.NeedsPermission
        val picked = RingtoneBridge.pickContact?.invoke() ?: return ContactResult.Cancelled
        return withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val name = resolver.query(picked, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                val values = ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtone.uri) }
                if (resolver.update(picked, values, null, null) > 0) ContactResult.Done(name.orEmpty()) else ContactResult.Failed("No change")
            }.getOrElse { ContactResult.Failed(it.message) }
        }
    }

    override fun inUse(ringtone: MadeRingtone): Set<RingtoneTarget> {
        val id = idOf(Uri.parse(ringtone.uri)) ?: return emptySet()
        return RingtoneTarget.entries.filterTo(HashSet()) { target ->
            runCatching { idOf(RingtoneManager.getActualDefaultRingtoneUri(context, typeOf(target))) == id }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------ what is on the phone

    override suspend fun exists(ringtone: MadeRingtone): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(Uri.parse(ringtone.uri), arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    override suspend fun delete(ringtone: MadeRingtone): Boolean = withContext(Dispatchers.IO) {
        // A sound that is set to this ringtone goes back to what it was, rather than pointing at a file that is gone.
        inUse(ringtone).forEach { target -> restorePrevious(target) }
        runCatching { context.contentResolver.delete(Uri.parse(ringtone.uri), null, null) > 0 }.getOrDefault(false)
    }

    private fun restorePrevious(target: RingtoneTarget) {
        val before = previous.getString(previousKey(target), null) ?: return
        if (!Settings.System.canWrite(context)) return
        runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, typeOf(target), Uri.parse(before)) }
            .onSuccess { previous.edit().remove(previousKey(target)).apply() }
    }

    private companion object {
        const val MIN_SONG_MS = 1_500L
        const val FADE_MS = 1_500L
        const val POLL_MS = 40L
        const val SEEK_TOLERANCE_MS = 400L
    }
}
