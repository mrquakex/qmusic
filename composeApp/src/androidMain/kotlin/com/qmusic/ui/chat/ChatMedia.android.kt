package com.qmusic.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// ------------------------------------------------------------------ recording

/** AAC in an .m4a (64 kbit/s, mono): small, and every phone plays it. The loudness is sampled for the bars. */
private class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val samples = ArrayList<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sampling: Job? = null

    override fun start(): Boolean {
        cancel()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        // Recordings of earlier days are not needed any more (they were sent, or thrown away).
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val target = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            // The plain microphone: some tablets give only silence from the voice recognition source.
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = System.currentTimeMillis()
            samples.clear()
            sampling = scope.launch {
                while (isActive) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    // Loudness on a log scale, so quiet speech still moves the bars.
                    val l = if (amp <= 0) 0f else (kotlin.math.log10(amp.toDouble()) / 4.5).toFloat().coerceIn(0f, 1f)
                    _level.value = l
                    samples += (l * 100).toInt()
                    delay(80)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            false
        }
    }

    override var lastSilent: Boolean = false
        private set

    override fun stop(): PickedFile? {
        lastSilent = false
        sampling?.cancel()
        _level.value = 0f
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val duration = System.currentTimeMillis() - startedAt
        if (!ok || f == null || !f.exists() || duration < MIN_MS) {
            f?.delete()
            return null
        }
        // Not a sound the whole time: the microphone gave nothing (blocked, or another app has it). Sending it would
        // be a silent message, so it is not sent and the person is told.
        if (samples.size >= 5 && samples.all { it == 0 }) {
            f.delete()
            lastSilent = true
            return null
        }
        return PickedFile(
            kind = AttachmentKind.VOICE,
            location = f.absolutePath,
            name = f.name,
            mime = "audio/mp4",
            size = f.length(),
            durationMs = duration,
            wave = bars(samples, BARS),
        )
    }

    override fun cancel() {
        sampling?.cancel()
        _level.value = 0f
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        const val MIN_MS = 700L
        const val BARS = 40
    }
}

/** [samples] squeezed (or stretched) to [count] bars, each the loudest of its part. */
private fun bars(samples: List<Int>, count: Int): List<Int> {
    if (samples.isEmpty()) return List(count) { 8 }
    return List(count) { i ->
        val from = i * samples.size / count
        val to = ((i + 1) * samples.size / count).coerceAtLeast(from + 1).coerceAtMost(samples.size)
        (samples.subList(from.coerceAtMost(samples.size - 1), to).maxOrNull() ?: 0).coerceIn(4, 100)
    }
}

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder? {
    val context = LocalContext.current
    val recorder = remember { AndroidVoiceRecorder(context.applicationContext) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    return recorder
}

// ------------------------------------------------------------------ playing voice messages

private class AndroidVoicePlayer(context: Context) : VoicePlayer {
    private val player = ExoPlayer.Builder(context)
        // The music player hears this and pauses for the voice message.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .build()
    private val _playing = MutableStateFlow<String?>(null)
    override val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()
    private var loaded: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = if (isPlaying) loaded else null
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    _progress.value = 0f
                    _playing.value = null
                    player.seekTo(0)
                    player.pause()
                }
            }
        })
        scope.launch {
            while (isActive) {
                if (player.isPlaying && player.duration > 0) _progress.value = (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                delay(50)
            }
        }
    }

    override fun toggle(url: String) {
        if (loaded == url) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        loaded = url
        _progress.value = 0f
        player.setMediaItem(MediaItem.fromUri(if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)))
        player.prepare()
        player.play()
    }

    override fun seek(url: String, fraction: Float) {
        if (loaded != url || player.duration <= 0) return
        player.seekTo((player.duration * fraction).toLong())
        _progress.value = fraction
    }

    private val _speed = MutableStateFlow(1f)
    override val speed: StateFlow<Float> = _speed.asStateFlow()

    override fun cycleSpeed() {
        val next = when (_speed.value) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _speed.value = next
        player.setPlaybackSpeed(next)
    }

    override fun stop() {
        player.stop()
        loaded = null
        _playing.value = null
        _progress.value = 0f
    }

    fun release() {
        player.release()
        scope.coroutineContext[Job]?.cancel()
    }
}

@Composable
actual fun rememberVoicePlayer(): VoicePlayer? {
    val context = LocalContext.current
    val player = remember { AndroidVoicePlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

// ------------------------------------------------------------------ picking videos and files

@Composable
actual fun rememberFilePicker(video: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            scope.launch {
                onPicked(withContext(Dispatchers.Default) { runCatching { describe(context, uri, video) }.getOrNull() })
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { handle(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { handle(it) }
    return {
        if (video) {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else {
            filePicker.launch(arrayOf("*/*"))
        }
    }
}

/** Name, type and size of a picked file; for a video also its length, size and a small preview. */
private fun describe(context: Context, uri: Uri, video: Boolean): PickedFile {
    val resolver = context.contentResolver
    var name = "file"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            size = c.getLong(1)
        }
    }
    val mime = resolver.getType(uri) ?: if (video) "video/mp4" else "application/octet-stream"
    if (!video && !mime.startsWith("video/")) {
        return PickedFile(AttachmentKind.FILE, uri.toString(), name, mime, size)
    }
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) w = h.also { h = w }
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val thumb = frame?.let { preview(it) }.orEmpty()
        return PickedFile(AttachmentKind.VIDEO, uri.toString(), name, if (mime.startsWith("video/")) mime else "video/mp4", size, duration, w, h, thumb)
    } finally {
        runCatching { retriever.release() }
    }
}

/** A small JPEG of the first frame (~10 KB), shown at once in the message until the video is opened. */
private fun preview(frame: Bitmap): String {
    val side = 320
    val scale = side.toFloat() / maxOf(frame.width, frame.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true) else frame
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 60, out)
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

// ------------------------------------------------------------------ opening files

@Composable
actual fun rememberAttachmentOpener(): (ChatAttachment) -> Unit {
    val context = LocalContext.current
    return { attachment ->
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val name = attachment.name.ifBlank { attachment.url.substringAfterLast('/') }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val request = android.app.DownloadManager.Request(Uri.parse(attachment.url))
                .setTitle(name)
                .setMimeType(attachment.mime.ifBlank { null })
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Qeyf/$name")
            manager.enqueue(request)
            val tr = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
            android.widget.Toast.makeText(context, if (tr) "İndiriliyor: $name" else "Downloading: $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ------------------------------------------------------------------ video messages

@Composable
actual fun VideoMessagePlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier,
    )
}
