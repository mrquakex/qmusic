package com.qmusic.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.qmusic.ui.A11y
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

private val mediaHookScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun isEnglish(context: Context): Boolean =
    context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") == "en"

// ------------------------------------------------------------------ recording

/** AAC in an .m4a (64 kbit/s, mono): small, and every phone plays it. The loudness is sampled for the bars. */
private class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _paused = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = _paused.asStateFlow()
    override val canPause: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var segmentStartedAt = 0L
    private var accumulatedMs = 0L
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
            accumulatedMs = 0L
            segmentStartedAt = System.currentTimeMillis()
            _paused.value = false
            samples.clear()
            sampling = scope.launch {
                while (isActive) {
                    if (!_paused.value) {
                        val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                        // Loudness on a log scale, so quiet speech still moves the bars.
                        val l = if (amp <= 0) 0f else (kotlin.math.log10(amp.toDouble()) / 4.5).toFloat().coerceIn(0f, 1f)
                        _level.value = l
                        samples += (l * 100).toInt()
                    } else {
                        _level.value = 0f
                    }
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

    override fun pause() {
        val r = recorder ?: return
        if (_paused.value || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching { r.pause() }.onSuccess {
            accumulatedMs += (System.currentTimeMillis() - segmentStartedAt).coerceAtLeast(0L)
            _paused.value = true
            _level.value = 0f
        }
    }

    override fun resume() {
        val r = recorder ?: return
        if (!_paused.value || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching { r.resume() }.onSuccess {
            segmentStartedAt = System.currentTimeMillis()
            _paused.value = false
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
        val duration = accumulatedMs + if (_paused.value) 0L else (System.currentTimeMillis() - segmentStartedAt).coerceAtLeast(0L)
        recorder = null
        file = null
        accumulatedMs = 0L
        _paused.value = false
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
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
        _paused.value = false
        accumulatedMs = 0L
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
    }

    override fun discardPreview(file: PickedFile) {
        runCatching {
            if (file.location.startsWith("/")) File(file.location).delete()
        }
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
    private val appContext: Context = context.applicationContext
    private val player = ExoPlayer.Builder(appContext)
        // The music player hears this and pauses for the voice message.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .build()

    private val _playing = MutableStateFlow<String?>(null)
    override val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _active = MutableStateFlow<String?>(null)
    override val active: StateFlow<String?> = _active.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 2)
    override val completed: Flow<String> = _completed.asSharedFlow()
    private val _earpiece = MutableStateFlow(false)
    override val earpiece: StateFlow<Boolean> = _earpiece.asStateFlow()

    private val playedPrefs = appContext.getSharedPreferences("qmusic_voice_played", Context.MODE_PRIVATE)
    private val playedOrder = loadPlayedOrder()
    private val _played = MutableStateFlow(playedOrder.toSet())
    override val played: StateFlow<Set<String>> = _played.asStateFlow()

    private var loaded: String? = null
    private var loadingUrl: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var routeReleaseJob: Job? = null

    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var sensorRegistered = false
    private var nearEar = false
    private var earpieceActive = false

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximitySensor ?: return
            val near = event.values.firstOrNull()?.let { it < minOf(sensor.maximumRange, 5f) } ?: false
            if (near == nearEar) return
            nearEar = near
            applyRoutingForProximity()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = if (isPlaying) loaded else null
                if (isPlaying) {
                    routeReleaseJob?.cancel()
                    loaded?.let { markPlayed(it) }
                    startProximityWatch()
                    applyRoutingForProximity()
                } else if (player.playbackState != Player.STATE_ENDED) {
                    stopProximityWatch()
                    restoreNormalRouting()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val finished = loaded
                    loaded = null
                    _active.value = null
                    _playing.value = null
                    _progress.value = 0f
                    player.seekTo(0)
                    player.pause()
                    // Keep earpiece mode briefly in case the next consecutive voice message starts right away.
                    routeReleaseJob?.cancel()
                    routeReleaseJob = scope.launch {
                        delay(250)
                        if (loaded == null && !player.playWhenReady) {
                            stopProximityWatch()
                            restoreNormalRouting()
                        }
                    }
                    if (finished != null) _completed.tryEmit(finished)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                stop()
            }
        })
        scope.launch {
            while (isActive) {
                if (player.isPlaying && player.duration > 0) _progress.value = (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                delay(50)
            }
        }
    }

    private fun loadPlayedOrder(): ArrayDeque<String> {
        val raw = playedPrefs.getString("urls_json", null) ?: return ArrayDeque()
        return runCatching {
            val arr = JSONArray(raw)
            ArrayDeque<String>(arr.length()).apply {
                for (i in 0 until arr.length()) addLast(arr.getString(i))
            }
        }.getOrElse { ArrayDeque() }
    }

    private fun markPlayed(url: String) {
        if (url in _played.value) return
        playedOrder.addLast(url)
        while (playedOrder.size > MAX_PLAYED_HISTORY) playedOrder.removeFirst()
        _played.value = playedOrder.toSet()
        val arr = JSONArray()
        playedOrder.forEach { arr.put(it) }
        playedPrefs.edit().putString("urls_json", arr.toString()).apply()
    }

    private fun hasExternalAudio(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }.getOrDefault(false)
    }

    private fun isCallActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || (mode == AudioManager.MODE_IN_COMMUNICATION && !earpieceActive)
    }

    private fun startProximityWatch() {
        val sm = sensorManager ?: return
        val sensor = proximitySensor ?: return
        if (sensorRegistered) return
        sensorRegistered = runCatching { sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
    }

    private fun stopProximityWatch() {
        if (!sensorRegistered) return
        sensorRegistered = false
        nearEar = false
        runCatching { sensorManager?.unregisterListener(proximityListener) }
    }

    private fun applyRoutingForProximity() {
        val shouldUseEarpiece = nearEar && (player.isPlaying || player.playWhenReady) && !hasExternalAudio() && !isCallActive()
        if (shouldUseEarpiece == earpieceActive) return
        val am = audioManager ?: return
        if (shouldUseEarpiece) {
            earpieceActive = true
            _earpiece.value = true
            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val earpieceDevice = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpieceDevice != null) am.setCommunicationDevice(earpieceDevice)
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
            }
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            acquireProximityWakeLock()
        } else {
            restoreNormalRouting()
        }
    }

    private fun restoreNormalRouting() {
        releaseProximityWakeLock()
        if (!earpieceActive) return
        earpieceActive = false
        _earpiece.value = false
        val am = audioManager
        if (am != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
                if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL
            }
        }
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        val pm = powerManager ?: return
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "nuvelo:voice").also {
                runCatching { it.acquire(10 * 60 * 1000L) }
            }
        }
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let { wl ->
            if (wl.isHeld) runCatching { wl.release() }
        }
        proximityWakeLock = null
    }

    private fun resolveLocalVoiceUri(url: String): Uri? {
        val local = FirebaseChatBackend.peek()?.localCopies?.get(url) ?: return null
        return when {
            local.startsWith("content:") || local.startsWith("file:") -> {
                val uri = Uri.parse(local)
                val readable = runCatching { appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.close(); true }.getOrDefault(false)
                if (readable) uri else null
            }
            else -> File(local).takeIf { it.exists() && it.length() > 0 }?.let { Uri.fromFile(it) }
        }
    }

    override fun toggle(url: String) {
        if (loaded == url) {
            if (loadingUrl == url) {
                stop()
                return
            }
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        routeReleaseJob?.cancel()
        loadingUrl = null
        loaded = url
        _active.value = url
        _progress.value = 0f

        // Sent voice messages play immediately from the local copy on the phone without re-downloading.
        val localUri = resolveLocalVoiceUri(url)
        if (localUri != null) {
            player.setMediaItem(MediaItem.fromUri(localUri))
            player.setPlaybackSpeed(_speed.value)
            player.prepare()
            player.play()
            return
        }
        if (url.startsWith("/") || url.startsWith("content:") || url.startsWith("file:")) {
            val uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.setPlaybackSpeed(_speed.value)
            player.prepare()
            player.play()
            return
        }
        // An encrypted voice message: opened on the phone first, played from there.
        if (E2eMedia.isSealed(url)) {
            loadingUrl = url
            scope.launch {
                val file = withContext(Dispatchers.IO) { E2eMedia.openedFile(appContext, url, "voice.m4a") }
                if (loaded != url || loadingUrl != url) return@launch
                loadingUrl = null
                if (file == null) {
                    loaded = null
                    _active.value = null
                    _playing.value = null
                    return@launch
                }
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.setPlaybackSpeed(_speed.value)
                player.prepare()
                player.play()
            }
            return
        }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.setPlaybackSpeed(_speed.value)
        player.prepare()
        player.play()
    }

    override fun seek(url: String, fraction: Float) {
        val dur = player.duration
        if (loaded != url || dur <= 0) return
        val coerced = fraction.coerceIn(0f, 1f)
        player.seekTo((dur * coerced).toLong())
        _progress.value = coerced
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
        routeReleaseJob?.cancel()
        loadingUrl = null
        stopProximityWatch()
        restoreNormalRouting()
        player.stop()
        loaded = null
        _active.value = null
        _playing.value = null
        _progress.value = 0f
    }

    fun release() {
        routeReleaseJob?.cancel()
        stopProximityWatch()
        restoreNormalRouting()
        player.release()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val MAX_PLAYED_HISTORY = 500
    }
}

@Composable
actual fun rememberVoicePlayer(): VoicePlayer? {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val player = remember { AndroidVoicePlayer(appContext) }
    val scope = rememberCoroutineScope()
    val pendingCallback = remember { arrayOfNulls<((PickedFile?) -> Unit)>(1) }
    val videoTarget = remember { arrayOfNulls<Uri>(1) }
    val videoFileTarget = remember { arrayOfNulls<File>(1) }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { captured ->
        val uri = videoTarget[0]
        val file = videoFileTarget[0]
        val cb = pendingCallback[0]
        videoTarget[0] = null
        videoFileTarget[0] = null
        pendingCallback[0] = null
        if (!captured || uri == null || cb == null) {
            runCatching { file?.delete() }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { describe(appContext, uri, true) }.getOrNull() }
            if (picked != null && picked.size > 0L) cb(picked) else runCatching { file?.delete() }
        }
    }

    fun openVideoCamera(onPicked: (PickedFile?) -> Unit) {
        val dir = File(appContext.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val file = File(dir, "video_${System.currentTimeMillis()}.mp4")
        val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".updates", file)
        videoTarget[0] = uri
        videoFileTarget[0] = file
        pendingCallback[0] = onPicked
        runCatching { captureLauncher.launch(uri) }.onFailure {
            runCatching { file.delete() }
            videoTarget[0] = null
            videoFileTarget[0] = null
            pendingCallback[0] = null
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pendingCallback[0]
        if (granted && cb != null) {
            openVideoCamera(cb)
        } else {
            pendingCallback[0] = null
        }
    }

    DisposableEffect(player) {
        val captureFn: ((PickedFile?) -> Unit) -> Unit = { onPicked ->
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openVideoCamera(onPicked)
            } else {
                pendingCallback[0] = onPicked
                permLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        ChatMediaHooks.captureVideo = captureFn
        onDispose {
            if (ChatMediaHooks.captureVideo === captureFn) ChatMediaHooks.captureVideo = null
            player.release()
        }
    }
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
        val thumb = frame?.let { f ->
            try {
                preview(f)
            } finally {
                runCatching { f.recycle() }
            }
        }.orEmpty()
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
    if (small !== frame) runCatching { small.recycle() }
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

// ------------------------------------------------------------------ opening, saving, sharing files & media

private fun safeName(raw: String, fallback: String = "file"): String =
    raw.ifBlank { fallback }.replace(Regex("[\\\\/:*?\"<>|]"), "_")

private fun imageFormatOf(bytes: ByteArray): Pair<String, String> {
    val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
    val webp = bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
    return when {
        png -> "png" to "image/png"
        webp -> "webp" to "image/webp"
        else -> "jpg" to "image/jpeg"
    }
}

@Composable
actual fun rememberAttachmentOpener(): (ChatAttachment) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return returnLabel@{ attachment ->
        // An encrypted file: opened on the phone and saved to Downloads/Nuvelo itself (the system downloader would
        // only get sealed bytes).
        if (E2eMedia.isSealed(attachment.url)) {
            val en = isEnglish(context)
            val name = safeName(attachment.name)
            android.widget.Toast.makeText(context, if (en) "Downloading: $name" else "İndiriliyor: $name", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val opened = E2eMedia.openedFile(context, attachment.url, name) ?: return@withContext false
                    saveToDownloads(context, opened, name, attachment.mime)
                }
                android.widget.Toast.makeText(
                    context,
                    if (ok) (if (en) "Downloaded: $name" else "İndirildi: $name") else (if (en) "Could not download" else "İndirilemedi"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            return@returnLabel
        }
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val name = safeName(attachment.name.ifBlank { attachment.url.substringAfterLast('/') })
            val request = android.app.DownloadManager.Request(Uri.parse(attachment.url))
                .setTitle(name)
                .setMimeType(attachment.mime.ifBlank { null })
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Qeyf/$name")
            manager.enqueue(request)
            val en = isEnglish(context)
            android.widget.Toast.makeText(context, if (en) "Downloading: $name" else "İndiriliyor: $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
actual fun rememberImageSaver(): (bytes: ByteArray, name: String) -> Unit {
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(appContext) {
        val shareFn: (ByteArray, String) -> Unit = { bytes, name ->
            mediaHookScope.launch { shareImageBytes(appContext, bytes, name) }
        }
        val saveAllFn: (List<Pair<ByteArray, String>>, (Int, Int) -> Unit) -> Unit = { items, onDone ->
            mediaHookScope.launch {
                val en = isEnglish(appContext)
                val saved = withContext(Dispatchers.IO) { items.count { (b, n) -> saveToPictures(appContext, b, n) } }
                val msg = when {
                    saved == 0 -> if (en) "Could not save" else "Kaydedilemedi"
                    saved == items.size -> if (en) "Saved $saved photos to gallery" else "$saved fotoğraf galeriye kaydedildi"
                    else -> if (en) "Saved $saved / ${items.size} to gallery" else "$saved / ${items.size} galeriye kaydedildi"
                }
                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                onDone(saved, items.size)
            }
        }
        ChatMediaHooks.shareImage = shareFn
        ChatMediaHooks.saveAllImages = saveAllFn
        onDispose {
            if (ChatMediaHooks.shareImage === shareFn) ChatMediaHooks.shareImage = null
            if (ChatMediaHooks.saveAllImages === saveAllFn) ChatMediaHooks.saveAllImages = null
        }
    }
    return { bytes, name ->
        val en = isEnglish(appContext)
        mediaHookScope.launch {
            val ok = withContext(Dispatchers.IO) { saveToPictures(appContext, bytes, name) }
            android.widget.Toast.makeText(
                appContext,
                if (ok) (if (en) "Saved to gallery" else "Galeriye kaydedildi") else (if (en) "Could not save" else "Kaydedilemedi"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private suspend fun shareImageBytes(context: Context, bytes: ByteArray, name: String) {
    val prepared = withContext(Dispatchers.IO) {
        runCatching {
            val (ext, mime) = imageFormatOf(bytes)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith("share_") }?.forEach { it.delete() }
            val file = File(dir, "share_${safeName(name)}.$ext").apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
            uri to mime
        }.getOrNull()
    } ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType(prepared.second)
        .putExtra(Intent.EXTRA_STREAM, prepared.first)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Puts a photo in Pictures/Nuvelo, where the gallery shows it (MediaStore from Android 10, the folder itself before). */
private fun saveToPictures(context: Context, bytes: ByteArray, name: String): Boolean = runCatching {
    val (ext, mime) = imageFormatOf(bytes)
    val file = "${safeName(name)}.$ext"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Nuvelo")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open")
    } else {
        @Suppress("DEPRECATION")
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Nuvelo").apply { mkdirs() }
        val out = File(dir, file)
        out.writeBytes(bytes)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mime), null)
    }
    true
}.getOrDefault(false)

/** Copies a video to Movies/Nuvelo (or returns null if unavailable) and deletes temporary downloaded file when needed. */
private fun resolveVideoTemp(context: Context, url: String, name: String): Pair<File, Boolean>? {
    if (E2eMedia.isSealed(url)) {
        val opened = E2eMedia.openedFile(context, url, name.ifBlank { "video.mp4" }) ?: return null
        return opened to false
    }
    if (url.startsWith("/")) {
        val local = File(url).takeIf { it.exists() && it.length() > 0 } ?: return null
        return local to false
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, "share_v_${System.currentTimeMillis()}.mp4")
    val ok = runCatching {
        if (url.startsWith("content:") || url.startsWith("file:")) {
            context.contentResolver.openInputStream(Uri.parse(url))?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("open")
        } else {
            MediaUpload.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "http ${r.code}" }
                val body = r.body ?: error("empty")
                body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        out.exists() && out.length() > 0
    }.getOrDefault(false)
    if (!ok) {
        out.delete()
        return null
    }
    return out to true
}

private fun saveVideoToMovies(context: Context, file: File, rawName: String): Boolean = runCatching {
    val clean = safeName(rawName.ifBlank { "Nuvelo_${System.currentTimeMillis()}.mp4" }).let {
        if (it.endsWith(".mp4", ignoreCase = true)) it else "$it.mp4"
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, clean)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/Nuvelo")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("open")
    } else {
        @Suppress("DEPRECATION")
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Nuvelo").apply { mkdirs() }
        val out = File(dir, clean)
        file.copyTo(out, overwrite = true)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf("video/mp4"), null)
    }
    true
}.getOrDefault(false)

/** Puts [file] in Downloads/Nuvelo (MediaStore from Android 10, the folder itself before). */
private fun saveToDownloads(context: Context, file: File, name: String, mime: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Nuvelo")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
        context.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: error("open")
    } else {
        @Suppress("DEPRECATION")
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Nuvelo").apply { mkdirs() }
        file.copyTo(File(dir, name), overwrite = true)
    }
    true
}.getOrDefault(false)

// ------------------------------------------------------------------ video messages

@Composable
actual fun VideoMessagePlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewerMode = LocalVideoViewerMode.current

    DisposableEffect(appContext) {
        val saveVideoFn: (String, String, (Boolean) -> Unit) -> Unit = { vUrl, vName, onDone ->
            mediaHookScope.launch {
                val en = isEnglish(appContext)
                val ok = withContext(Dispatchers.IO) {
                    val resolved = resolveVideoTemp(appContext, vUrl, vName) ?: return@withContext false
                    try {
                        saveVideoToMovies(appContext, resolved.first, vName)
                    } finally {
                        if (resolved.second) runCatching { resolved.first.delete() }
                    }
                }
                android.widget.Toast.makeText(
                    appContext,
                    if (ok) (if (en) "Saved to gallery" else "Galeriye kaydedildi") else (if (en) "Could not save" else "Kaydedilemedi"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onDone(ok)
            }
        }
        val shareVideoFn: (String, String, (Boolean) -> Unit) -> Unit = { vUrl, vName, onDone ->
            mediaHookScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("share_v_") }?.forEach { it.delete() }
                    val resolved = resolveVideoTemp(appContext, vUrl, vName) ?: return@withContext null
                    val shareFile = if (resolved.second) resolved.first else {
                        val copy = File(dir, "share_v_${safeName(vName, "video.mp4")}")
                        runCatching { resolved.first.copyTo(copy, overwrite = true) }.getOrNull() ?: return@withContext null
                        copy
                    }
                    runCatching { FileProvider.getUriForFile(appContext, appContext.packageName + ".updates", shareFile) }.getOrNull()
                }
                if (uri != null) {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                onDone(uri != null)
            }
        }
        ChatMediaHooks.saveVideo = saveVideoFn
        ChatMediaHooks.shareVideo = shareVideoFn
        onDispose {
            if (ChatMediaHooks.saveVideo === saveVideoFn) ChatMediaHooks.saveVideo = null
            if (ChatMediaHooks.shareVideo === shareVideoFn) ChatMediaHooks.shareVideo = null
        }
    }

    // An encrypted video: opened on the phone first (a moment), then played from there.
    var opened by remember(url) { mutableStateOf<String?>(if (E2eMedia.isSealed(url)) null else url) }
    var decryptRetry by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, decryptRetry) {
        if (opened == null || opened == "") {
            opened = null
            opened = withContext(Dispatchers.IO) { E2eMedia.openedFile(appContext, url, "video.mp4")?.absolutePath } ?: ""
        }
    }
    val source = opened
    if (source == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    if (source.isEmpty()) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            IconButton(onClick = { decryptRetry++ }) {
                Icon(QMusicIcons.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        return
    }
    val player = remember(source) {
        ExoPlayer.Builder(appContext).build().apply {
            val uri = when {
                source.startsWith("/") -> Uri.fromFile(File(source))
                else -> Uri.parse(source)
            }
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isBuffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var hasError by remember(player) { mutableStateOf(false) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var muted by remember(player) { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(1f) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var scrubbingFraction by remember(player) { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        if (player.duration > 0) durationMs = player.duration
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playingNow: Boolean) {
                isPlaying = playingNow
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    hasError = false
                    durationMs = player.duration.takeIf { it > 0 } ?: 0L
                }
                if (state == Player.STATE_ENDED) {
                    controlsVisible = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isBuffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, viewerMode) {
        if (!viewerMode) return@LaunchedEffect
        while (true) {
            if (player.duration > 0) durationMs = player.duration
            if (scrubbingFraction == null) positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(100)
        }
    }

    LaunchedEffect(viewerMode, controlsVisible, isPlaying) {
        if (viewerMode && controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .then(
                if (viewerMode) {
                    Modifier.pointerInput(player) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = !viewerMode
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                if (view.useController != !viewerMode) view.useController = !viewerMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (viewerMode) {
            when {
                hasError -> {
                    IconButton(
                        onClick = {
                            hasError = false
                            isBuffering = true
                            player.prepare()
                            player.play()
                        },
                        modifier = Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    ) {
                        Icon(QMusicIcons.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                isBuffering -> {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.align(Alignment.Center).size(38.dp))
                }
                controlsVisible || !isPlaying -> {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f))
                            .clickable {
                                if (player.playbackState == Player.STATE_ENDED) {
                                    player.seekTo(0)
                                    player.play()
                                } else if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    player.play()
                                }
                                controlsVisible = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                            contentDescription = if (isPlaying) A11y.pause else A11y.play,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            if (controlsVisible) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val shownFraction = scrubbingFraction ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    val displayPosMs = if (scrubbingFraction != null && durationMs > 0L) (durationMs * shownFraction).toLong() else positionMs
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .pointerInput(durationMs) {
                                detectTapGestures { pos ->
                                    if (durationMs > 0L) {
                                        val f = (pos.x / size.width).coerceIn(0f, 1f)
                                        player.seekTo((durationMs * f).toLong())
                                        positionMs = (durationMs * f).toLong()
                                    }
                                    controlsVisible = true
                                }
                            }
                            .pointerInput(durationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { pos ->
                                        if (durationMs > 0L) scrubbingFraction = (pos.x / size.width).coerceIn(0f, 1f)
                                    },
                                    onDragEnd = {
                                        scrubbingFraction?.let { f ->
                                            if (durationMs > 0L) {
                                                val target = (durationMs * f).toLong()
                                                player.seekTo(target)
                                                positionMs = target
                                            }
                                        }
                                        scrubbingFraction = null
                                    },
                                    onDragCancel = { scrubbingFraction = null },
                                    onHorizontalDrag = { change, amount ->
                                        if (durationMs > 0L) {
                                            val next = ((scrubbingFraction ?: shownFraction) + amount / size.width).coerceIn(0f, 1f)
                                            scrubbingFraction = next
                                            change.consume()
                                        }
                                    },
                                )
                            },
                    ) {
                        val cy = size.height / 2f
                        val stroke = 3.dp.toPx()
                        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                        val x = (size.width * shownFraction).coerceIn(0f, size.width)
                        drawLine(Color.White, Offset(0f, cy), Offset(x, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                        drawCircle(Color.White, radius = if (scrubbingFraction != null) 7.dp.toPx() else 5.dp.toPx(), center = Offset(x, cy))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            mmss(displayPosMs) + if (durationMs > 0L) " / ${mmss(durationMs)}" else "",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            (if (speed == 1f) "1" else if (speed == 1.5f) "1.5" else "2") + "×",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable {
                                    val next = when (speed) {
                                        1f -> 1.5f
                                        1.5f -> 2f
                                        else -> 1f
                                    }
                                    speed = next
                                    player.setPlaybackSpeed(next)
                                    controlsVisible = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        IconButton(
                            onClick = {
                                muted = !muted
                                player.volume = if (muted) 0f else 1f
                                controlsVisible = true
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (muted) QMusicIcons.VolumeMute else QMusicIcons.Volume,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
