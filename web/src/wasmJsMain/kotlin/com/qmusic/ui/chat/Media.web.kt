package com.qmusic.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.nuvelo.web.audioDrop
import com.nuvelo.web.audioNew
import com.nuvelo.web.audioPause
import com.nuvelo.web.audioPlay
import com.nuvelo.web.audioRate
import com.nuvelo.web.audioSeek
import com.nuvelo.web.blobDrop
import com.nuvelo.web.blobToBytes
import com.nuvelo.web.blobUrl
import com.nuvelo.web.locate
import com.nuvelo.web.mediaUrl
import com.nuvelo.web.pickFiles
import com.nuvelo.web.prepareImage
import com.nuvelo.web.qrMatrix
import com.nuvelo.web.recLevel
import com.nuvelo.web.recStartNow
import com.nuvelo.web.recStop
import com.nuvelo.web.saveText
import com.nuvelo.web.text
import com.nuvelo.web.videoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The messages' media in the browser: picking photos, videos and files, recording and playing voice messages,
// showing videos, QR codes, sending a place.

private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> com.nuvelo.web.logJs("bg: " + e) })

private class Picked(val id: Int, val name: String, val type: String, val size: Long)

private suspend fun pick(accept: String, multiple: Boolean, capture: String? = null): List<Picked> =
    Json.parseToJsonElement(pickFiles(accept, multiple, capture).text()).jsonArray.map { e ->
        val o = e.jsonObject
        Picked(o["id"]!!.jsonPrimitive.int, o["name"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.content, o["size"]!!.jsonPrimitive.long)
    }

/** A photo made ready to send as the phone does it (1600 on its long side, about 85 % JPEG; "HD" at 4096). */
private suspend fun prepared(id: Int, maxSide: Int = 1600, quality: Double = 0.84): PickedImage {
    val o = Json.parseToJsonElement(prepareImage(id, maxSide, quality).text()).jsonObject
    val out = o["id"]!!.jsonPrimitive.int
    val bytes = try {
        blobToBytes(out)
    } finally {
        blobDrop(out)
    }
    return PickedImage(
        jpeg = bytes,
        width = o["w"]!!.jsonPrimitive.int,
        height = o["h"]!!.jsonPrimitive.int,
        thumb = o["thumb"]!!.jsonPrimitive.content,
        hd = if (maxSide == 1600) ({ prepared(id, 4096, 0.92) }) else null,
    )
}

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val files = pick("image/*", multiple = true).take(10)
            if (files.isEmpty()) return@launch
            val images = files.mapNotNull { f -> runCatching { prepared(f.id) }.getOrNull() }
            if (images.isNotEmpty()) latest.value(images)
        }
    }
}

@Composable
actual fun rememberCamera(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val f = pick("image/*", multiple = false, capture = "environment").firstOrNull()
            latest.value(f?.let { runCatching { prepared(it.id) }.getOrNull() })
        }
    }
}

@Composable
actual fun rememberRecentPhotos(enabled: Boolean): RecentPhotos = remember { RecentPhotos(emptyList(), true, {}, { null }) }

@Composable
actual fun rememberFilePicker(video: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val f = pick(if (video) "video/*" else "*/*", multiple = false).firstOrNull() ?: return@launch
            latest.value(describe(f, video))
        }
    }
}

private suspend fun describe(f: Picked, video: Boolean): PickedFile {
    val location = "blob:${f.id}"
    if (!video && !f.type.startsWith("video/")) return PickedFile(AttachmentKind.FILE, location, f.name, f.type.ifBlank { "application/octet-stream" }, f.size)
    val o = runCatching { Json.parseToJsonElement(videoInfo(f.id).text()).jsonObject }.getOrNull()
    return PickedFile(
        AttachmentKind.VIDEO, location, f.name, f.type.ifBlank { "video/mp4" }, f.size,
        durationMs = o?.get("ms")?.jsonPrimitive?.long ?: 0L,
        width = o?.get("w")?.jsonPrimitive?.int ?: 0,
        height = o?.get("h")?.jsonPrimitive?.int ?: 0,
        thumb = o?.get("thumb")?.jsonPrimitive?.content.orEmpty(),
    )
}

// ------------------------------------------------------------------ voice messages

/** Records with the browser's recorder; the recording is ready a moment after it stops (sending waits for it). */
private class WebVoiceRecorder : VoiceRecorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private var started = 0L
    private var running = false
    private val samples = ArrayList<Int>()
    private var sampling: Job? = null
    private var starting: Job? = null

    override fun start(): Boolean {
        cancel()
        running = true
        started = System.currentTimeMillis()
        samples.clear()
        starting = mediaScope.launch { runCatching { recStartNow().await<kotlin.js.JsAny?>() }.onFailure { running = false } }
        sampling = mediaScope.launch {
            while (isActive) {
                val l = recLevel() / 100f
                _level.value = l
                samples += (l * 100).toInt()
                delay(80)
            }
        }
        return true
    }

    override var lastSilent: Boolean = false
        private set

    override fun stop(): PickedFile? {
        lastSilent = false
        sampling?.cancel()
        _level.value = 0f
        val duration = System.currentTimeMillis() - started
        if (!running) return null
        running = false
        if (duration < 700) {
            mediaScope.launch { starting?.join(); recStop(false).await<kotlin.js.JsString?>() }
            return null
        }
        if (samples.size >= 8 && samples.all { it == 0 }) {
            lastSilent = true
            mediaScope.launch { starting?.join(); recStop(false).await<kotlin.js.JsString?>() }
            return null
        }
        // The file comes when the recorder has finished: sending waits for it ("rec:<n>").
        val n = ++recordings
        val done = CompletableDeferred<Int?>()
        pendingRecordings[n] = done
        mediaScope.launch {
            starting?.join()
            val r = runCatching { recStop(true).await<kotlin.js.JsString?>()?.toString() }.getOrNull()
            done.complete(r?.let { Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.intOrNull })
        }
        return PickedFile(
            kind = AttachmentKind.VOICE,
            location = "rec:$n",
            name = "voice_$n.m4a",
            mime = recordingType(),
            size = duration * 8,
            durationMs = duration,
            wave = bars(samples, 40),
        )
    }

    override fun cancel() {
        sampling?.cancel()
        _level.value = 0f
        if (running) {
            running = false
            mediaScope.launch { starting?.join(); recStop(false).await<kotlin.js.JsString?>() }
        }
    }

    companion object {
        var recordings = 0
        val pendingRecordings = HashMap<Int, CompletableDeferred<Int?>>()
    }
}

/** The kept file behind a picked file's location: "blob:<n>", or a recording that is still being finished. */
internal suspend fun blobOf(location: String): Int {
    if (location.startsWith("rec:")) {
        val n = location.removePrefix("rec:").toInt()
        return WebVoiceRecorder.pendingRecordings[n]?.await() ?: error("recording failed")
    }
    return blobIdOf(location)
}

@JsFun(
    """() => {
        const types = ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'];
        const t = types.find((x) => window.MediaRecorder && MediaRecorder.isTypeSupported(x)) || 'audio/mp4';
        return t.split(';')[0];
    }""",
)
private external fun recordingType(): String

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
    val recorder = remember { WebVoiceRecorder() }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    return recorder
}

/** Plays voice messages through an audio element: an encrypted one is opened in the browser first. */
private class WebVoicePlayer : VoicePlayer {
    private val _playing = MutableStateFlow<String?>(null)
    override val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    override val speed: StateFlow<Float> = _speed.asStateFlow()
    private var loaded: String? = null
    private var player: Int? = null
    private var durationMs = 0L
    private var isPlaying = false

    override fun toggle(url: String) {
        if (loaded == url && player != null) {
            val p = player!!
            if (isPlaying) audioPause(p) else audioPlay(p)
            return
        }
        stop()
        loaded = url
        _progress.value = 0f
        mediaScope.launch {
            val source = sourceOf(url) ?: return@launch
            if (loaded != url) return@launch
            var id = 0
            id = audioNew(source) { e -> onEvent(id, url, e) }
            player = id
            audioRate(id, _speed.value.toDouble())
            audioPlay(id)
        }
    }

    private fun onEvent(id: Int, url: String, e: String) {
        if (player != id) return
        when {
            e.startsWith("time:") -> if (durationMs > 0) _progress.value = (e.removePrefix("time:").toLong().toFloat() / durationMs).coerceIn(0f, 1f)
            e.startsWith("duration:") -> durationMs = e.removePrefix("duration:").toLong()
            e == "playing" -> {
                isPlaying = true
                _playing.value = url
            }
            e == "paused" -> {
                isPlaying = false
                _playing.value = null
            }
            e == "ended" -> {
                isPlaying = false
                _progress.value = 0f
                _playing.value = null
                audioSeek(id, 0.0)
            }
            e == "error" -> stop()
        }
    }

    override fun seek(url: String, fraction: Float) {
        val p = player ?: return
        if (loaded != url || durationMs <= 0) return
        audioSeek(p, (durationMs * fraction).toDouble())
        _progress.value = fraction
    }

    override fun cycleSpeed() {
        val next = when (_speed.value) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _speed.value = next
        player?.let { audioRate(it, next.toDouble()) }
    }

    override fun stop() {
        player?.let { audioDrop(it) }
        player = null
        loaded = null
        isPlaying = false
        durationMs = 0
        _playing.value = null
        _progress.value = 0f
    }
}

/** Where the browser plays [url] from: our own recording, the file opened (encrypted), or the link itself. */
internal suspend fun sourceOf(url: String, type: String? = null): String? {
    (FirebaseChatBackend.get().localCopies[url])?.let { local ->
        runCatching { return blobUrl(blobOf(local)) }
    }
    val key = E2eMedia.keyOf(url)
    return if (key != null) runCatching { mediaUrl(url, key, type).text() }.getOrNull() else url
}

@Composable
actual fun rememberVoicePlayer(): VoicePlayer? {
    val player = remember { WebVoicePlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

// ------------------------------------------------------------------ opening files, videos

@Composable
actual fun rememberAttachmentOpener(): (ChatAttachment) -> Unit {
    val scope = rememberCoroutineScope()
    return { attachment ->
        scope.launch {
            val name = attachment.name.ifBlank { attachment.url.substringAfterLast('/') }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val source = sourceOf(attachment.url, attachment.mime.ifBlank { null }) ?: return@launch
            downloadLink(source, name)
        }
    }
}

@Composable
actual fun rememberImageSaver(): (bytes: ByteArray, name: String) -> Unit = { bytes, name ->
    val id = com.nuvelo.web.bytesToBlob(bytes, "image/jpeg")
    downloadLink(blobUrl(id), "$name.jpg")
}

@JsFun("(url, name) => { const a = document.createElement('a'); a.href = url; a.download = name; a.target = '_blank'; a.rel = 'noopener'; document.body.appendChild(a); a.click(); setTimeout(() => a.remove(), 1000); }")
private external fun downloadLink(url: String, name: String)

/**
 * A video of the page itself, laid over the drawing where [modifier] puts it (the browser plays it; the drawing cannot
 * hold one).
 */
@Composable
internal fun HtmlVideo(
    src: String,
    modifier: Modifier,
    controls: Boolean,
    autoplay: Boolean,
    loop: Boolean,
    muted: Boolean,
    fill: Boolean,
    paused: Boolean = false,
    onReady: (Long) -> Unit = {},
    onBuffering: (Boolean) -> Unit = {},
    onError: () -> Unit = {},
) {
    val ready = rememberUpdatedState(onReady)
    val buffering = rememberUpdatedState(onBuffering)
    val error = rememberUpdatedState(onError)
    val density = LocalDensity.current.density
    val id = remember(src) {
        videoNew(src, controls, autoplay, loop, muted, fill) { ms -> ready.value(ms.toLong()) }.also { vid ->
            videoEvents(vid, { b -> buffering.value(b) }, { error.value() })
        }
    }
    DisposableEffect(id) { onDispose { videoDrop(id) } }
    LaunchedEffect(id, paused) { videoPaused(id, paused) }
    LaunchedEffect(id, muted) { videoMuted(id, muted) }
    Box(
        modifier.onGloballyPositioned { c ->
            val r = c.boundsInWindow()
            videoPlace(id, (r.left / density).toDouble(), (r.top / density).toDouble(), (r.width / density).toDouble(), (r.height / density).toDouble())
        },
    )
}

@JsFun(
    """(src, controls, autoplay, loop, muted, fill, ready) => {
        const v = document.createElement('video');
        v.src = src; v.controls = controls; v.autoplay = autoplay; v.loop = loop; v.muted = muted; v.playsInline = true;
        v.setAttribute('playsinline', ''); v.preload = 'auto';
        v.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:5;background:#000;object-fit:' + (fill ? 'cover' : 'contain');
        v.onloadedmetadata = () => ready(isFinite(v.duration) ? Math.round(v.duration * 1000) : 0);
        document.body.appendChild(v);
        window.__nuveloVideos = window.__nuveloVideos || new Map();
        const id = (window.__nuveloVideoNext = (window.__nuveloVideoNext || 0) + 1);
        window.__nuveloVideos.set(id, v);
        return id;
    }""",
)
private external fun videoNew(src: String, controls: Boolean, autoplay: Boolean, loop: Boolean, muted: Boolean, fill: Boolean, ready: (Double) -> Unit): Int

@JsFun(
    """(id, onBuffering, onError) => {
        const v = window.__nuveloVideos && window.__nuveloVideos.get(id); if (!v) return;
        v.onwaiting = () => onBuffering(true);
        v.onstalled = () => { if (v.readyState < 3) onBuffering(true); };
        v.onplaying = () => onBuffering(false);
        v.oncanplay = () => onBuffering(false);
        v.onended = () => onBuffering(false);
        v.onerror = () => { onBuffering(false); onError(); };
    }""",
)
private external fun videoEvents(id: Int, onBuffering: (Boolean) -> Unit, onError: () -> Unit)

@JsFun("(id, m) => { const v = window.__nuveloVideos && window.__nuveloVideos.get(id); if (v) v.muted = m; }")
private external fun videoMuted(id: Int, muted: Boolean)

@JsFun(
    """(id, x, y, w, h) => {
        const v = window.__nuveloVideos && window.__nuveloVideos.get(id); if (!v) return;
        const app = document.getElementById('app').getBoundingClientRect();
        v.style.left = (app.left + x) + 'px'; v.style.top = (app.top + y) + 'px'; v.style.width = w + 'px'; v.style.height = h + 'px';
    }""",
)
private external fun videoPlace(id: Int, x: Double, y: Double, w: Double, h: Double)

@JsFun("(id, p) => { const v = window.__nuveloVideos && window.__nuveloVideos.get(id); if (!v) return; if (p) v.pause(); else v.play().catch(() => {}); }")
private external fun videoPaused(id: Int, paused: Boolean)

@JsFun("(id) => { const v = window.__nuveloVideos && window.__nuveloVideos.get(id); if (!v) return; v.pause(); v.removeAttribute('src'); v.load(); v.remove(); window.__nuveloVideos.delete(id); }")
private external fun videoDrop(id: Int)

@Composable
actual fun VideoMessagePlayer(url: String, modifier: Modifier) {
    var source by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) { source = sourceOf(url, "video/mp4") ?: "" }
    val s = source
    if (s == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        return
    }
    HtmlVideo(s, modifier, controls = true, autoplay = true, loop = false, muted = false, fill = false)
}

@Composable
actual fun StatusVideo(
    url: String,
    paused: Boolean,
    modifier: Modifier,
    muted: Boolean,
    onReady: (Long) -> Unit,
    onBuffering: (Boolean) -> Unit,
    onError: () -> Unit,
) {
    HtmlVideo(
        url,
        modifier,
        controls = false,
        autoplay = true,
        loop = false,
        muted = muted,
        fill = false,
        paused = paused,
        onReady = onReady,
        onBuffering = onBuffering,
        onError = onError,
    )
}

/** A status's song: the web has no music player (the status shows without its sound). */
@Composable
actual fun StatusAudio(url: String, playing: Boolean, startMs: Long, loopMs: Long, onReady: (Long) -> Unit, onFailed: () -> Unit) {
    val failed = rememberUpdatedState(onFailed)
    LaunchedEffect(url) { failed.value() }
}

@Composable
actual fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit) {
    // The web sends the photo as it is (no drawing on it).
    LaunchedEffect(image) { onDone(image) }
}

// ------------------------------------------------------------------ places, contacts, QR

@Composable
actual fun rememberLocationPicker(onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    val error = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val r = locate().text()
            val parts = r.split(',')
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) picked.value(ChatLocation(lat, lng)) else error.value(if (r == "denied") "permission" else if (r == "none") "none" else "off")
        }
    }
}

@Composable
actual fun rememberContactPicker(onPicked: (ChatContact) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val r = runCatching { pickContact().text() }.getOrNull().orEmpty()
            if (r.isNotEmpty()) picked.value(ChatContact(r.substringBefore('\n'), r.substringAfter('\n')))
        }
    }
}

/** The browser's contact picker (Android's Chrome has one): "name\nnumber", or "" when none was picked. */
@JsFun(
    """async () => {
        if (!navigator.contacts || !navigator.contacts.select) return '';
        const list = await navigator.contacts.select(['name', 'tel'], { multiple: false });
        const c = list && list[0]; if (!c || !c.tel || !c.tel.length) return '';
        return ((c.name && c.name[0]) || c.tel[0]) + '\n' + c.tel[0];
    }""",
)
private external fun pickContact(): kotlin.js.Promise<kotlin.js.JsString>

@Composable
actual fun rememberContactSaver(): (ChatContact) -> Unit = { c ->
    val name = c.name.ifBlank { c.phone }
    saveText("${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.vcf", "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nTEL;TYPE=CELL:${c.phone}\r\nEND:VCARD\r\n")
}

@Composable
actual fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        val s = qrMatrix(text)
        val n = s.substringBefore(':').toInt()
        n to s.substringAfter(':')
    }
    Canvas(modifier.background(Color.White)) {
        val (n, bits) = matrix
        val quiet = 1
        val cell = size.minDimension / (n + quiet * 2)
        val ox = (size.width - cell * (n + quiet * 2)) / 2
        val oy = (size.height - cell * (n + quiet * 2)) / 2
        for (r in 0 until n) for (c in 0 until n) {
            if (bits[r * n + c] == '1') drawRect(Color.Black, Offset(ox + (c + quiet) * cell, oy + (r + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The web has no camera scanner: codes are shown on the web and scanned by the phone. */
@Composable
actual fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberTextFileShare(): (fileName: String, text: String) -> Unit = { name, text -> saveText(name, text) }

@Composable
actual fun DialogEdgeToEdge() = Unit

@Composable
actual fun rememberAppLock(): AppLock? = null

@Composable
actual fun rememberChatSounds(): ChatSounds? = null

@Composable
actual fun rememberStickerMaker(onStep: (StickerMaking) -> Unit, onMade: (GifItem?) -> Unit): () -> Unit = { onMade(null) }

@Composable
actual fun rememberAnimatedImageLoader(): coil3.ImageLoader = coil3.SingletonImageLoader.get(coil3.compose.LocalPlatformContext.current)

// ------------------------------------------------------------------ calls (see Calls.web.kt)

@Composable
actual fun CallPictureInPicture(armed: Boolean): Boolean = false

actual val callBlurWorks: Boolean = false

@Composable
actual fun GroupCallVideo(service: GroupCallService, uid: String?, modifier: Modifier) = Unit

/** The microphone (and the camera for [video]): the browser asks the person, once per site. */
@Composable
actual fun rememberCallPermissions(): (video: Boolean, onResult: (Boolean) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { video, onResult ->
        scope.launch { onResult(runCatching { askDevices(video).await<kotlin.js.JsBoolean>().toBoolean() }.getOrDefault(false)) }
    }
}

@JsFun("async (video) => { try { const s = await navigator.mediaDevices.getUserMedia({ audio: true, video: !!video }); s.getTracks().forEach((t) => t.stop()); return true; } catch (e) { return false; } }")
private external fun askDevices(video: Boolean): kotlin.js.Promise<kotlin.js.JsBoolean>
