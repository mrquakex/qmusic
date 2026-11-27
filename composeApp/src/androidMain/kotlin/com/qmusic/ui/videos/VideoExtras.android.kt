package com.qmusic.ui.videos

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// ---------------------------------------------------------------------- SponsorBlock

/**
 * SponsorBlock answers for the first four letters of the video id's SHA-256, so it never learns which video is watched,
 * and the segments of every video that starts that way come back; the one wanted is picked here.
 */
actual suspend fun fetchSponsorSegments(videoId: String, categories: Set<String>): List<SkipSegment> = withContext(Dispatchers.IO) {
    val active = categories.filter { it in ALL_SPONSOR_CATEGORIES }
    if (active.isEmpty()) return@withContext emptyList()
    runCatching {
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)
        val catsJson = active.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val encodedCats = URLEncoder.encode(catsJson, "UTF-8")
        val actions = URLEncoder.encode("[\"skip\"]", "UTF-8")
        val connection = URL("https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$encodedCats&actionTypes=$actions")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("User-Agent", "QMusic")
        try {
            if (connection.responseCode != 200) return@runCatching emptyList()
            val all = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val result = ArrayList<SkipSegment>()
            for (i in 0 until all.length()) {
                val video = all.getJSONObject(i)
                if (video.optString("videoID") != videoId) continue
                val segments = video.optJSONArray("segments") ?: continue
                for (j in 0 until segments.length()) {
                    val segment = segments.getJSONObject(j)
                    if (segment.optString("actionType", "skip") != "skip") continue
                    val range = segment.getJSONArray("segment")
                    val start = (range.getDouble(0) * 1000).toLong()
                    val end = (range.getDouble(1) * 1000).toLong()
                    if (end - start >= 1_000) result += SkipSegment(start, end, segment.optString("category"))
                }
            }
            result.sortedBy { it.startMs }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------- Return YouTube Dislike

private val dislikeCache = ConcurrentHashMap<String, VideoDislikeStats>()

actual suspend fun fetchVideoDislikes(videoId: String): VideoDislikeStats? = withContext(Dispatchers.IO) {
    if (videoId.isBlank()) return@withContext null
    dislikeCache[videoId]?.let { return@withContext it }
    runCatching {
        val encoded = URLEncoder.encode(videoId, "UTF-8")
        val connection = URL("https://returnyoutubedislikeapi.com/votes?videoId=$encoded")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", "Nuvelo")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != 200) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val likes = json.optLong("likes", -1L)
            val dislikes = json.optLong("dislikes", -1L)
            if (likes < 0L || dislikes < 0L) return@runCatching null
            VideoDislikeStats(likes = likes, dislikes = dislikes).also { stats ->
                if (dislikeCache.size > 200) dislikeCache.clear()
                dislikeCache[videoId] = stats
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------- downloads

/** What the app does while videos download (see DownloadKeepAliveService): set where the service is. */
object VideoDownloadHooks {
    /** Number of currently active or queued video downloads. */
    @Volatile
    var activeCount: Int = 0

    /** [count] downloads are running, [percent] through, the first of them called [title]; count 0 = nothing runs. */
    var onActive: ((count: Int, percent: Int, title: String?) -> Unit)? = null
}

@Composable
actual fun rememberVideoDownloads(): VideoDownloads? {
    val context = LocalContext.current
    return VideoDownloadManager.also { it.init(context) }
}

/**
 * Keeps videos on the device: the whole picture and sound are read into a cache of their own that nothing evicts, under
 * the same keys the player uses, so a downloaded video plays from it (also without the network) and costs no data.
 */
@UnstableApi
internal object VideoDownloadManager : VideoDownloads {
    private lateinit var appContext: Context
    private var started = false

    private val _items = MutableStateFlow<List<DownloadedVideo>>(emptyList())
    override val items: StateFlow<List<DownloadedVideo>> = _items.asStateFlow()

    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "video-download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val writers = ConcurrentHashMap<String, MutableList<CacheWriter>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var lastHook = 0L

    private val prefs get() = appContext.getSharedPreferences("qvideo_downloads", Context.MODE_PRIVATE)

    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val list = load()
        // What was on its way when the app stopped carries on from where its cache got to.
        _items.value = list.map { if (it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED) it.copy(state = DownloadState.QUEUED) else it }
        _items.value.filter { it.state == DownloadState.QUEUED }.forEach(::run)
    }

    override fun start(item: VideoItem, quality: VideoQuality, caption: VideoCaption?) {
        if (quality.isLive) return
        val entry = DownloadedVideo(item, quality, DownloadState.QUEUED, caption = caption)
        cancelled.remove(item.id)
        _items.value = _items.value.filter { it.item.id != item.id } + entry
        save()
        run(entry)
    }

    override fun cancel(id: String) = remove(id)

    override fun remove(id: String) {
        val entry = _items.value.firstOrNull { it.item.id == id } ?: return
        cancelled.add(id)
        writers[id]?.toList()?.forEach { runCatching { it.cancel() } }
        _items.value = _items.value.filter { it.item.id != id }
        save()
        executor.execute {
            val cache = VideoStreaming.downloadCache(appContext)
            keysOf(entry.quality).forEach { runCatching { cache.removeResource(it) } }
            runCatching { captionFile(id).delete() }
            hook(force = true)
        }
    }

    private fun captionFile(videoId: String): File =
        File(File(appContext.filesDir, "video_captions").apply { mkdirs() }, "${videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.vtt")

    private fun keysOf(quality: VideoQuality) = listOfNotNull(quality.videoUrl, quality.audioUrl)
        .map { VideoStreaming.cacheKeyOf(Uri.parse(it)) }

    private fun run(entry: DownloadedVideo) {
        val id = entry.item.id
        executor.execute {
            try {
                if (id in cancelled) return@execute
                update(id) { it.copy(state = DownloadState.DOWNLOADING) }
                hook(force = true)
                val urls = listOfNotNull(entry.quality.videoUrl, entry.quality.audioUrl)
                val sizes = LongArray(urls.size)
                val done = LongArray(urls.size)
                val factory = VideoStreaming.downloader(appContext)
                urls.forEachIndexed { i, url ->
                    val writer = CacheWriter(
                        factory.createDataSource(),
                        DataSpec.Builder().setUri(Uri.parse(url)).build(),
                        null,
                    ) { requestLength, bytesCached, _ ->
                        sizes[i] = requestLength.coerceAtLeast(bytesCached)
                        done[i] = bytesCached
                        update(id) { it.copy(bytes = done.sum(), total = sizes.sum()) }
                        hook()
                    }
                    writers.getOrPut(id) { java.util.Collections.synchronizedList(ArrayList()) }.add(writer)
                    writer.cache()
                }
                // Best-effort offline copy of selected subtitles alongside the video.
                val localCaption = entry.caption?.let { cap ->
                    runCatching {
                        val conn = URL(cap.url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 8_000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        try {
                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.readBytes()
                                if (bytes.isNotEmpty()) {
                                    val file = captionFile(id)
                                    file.writeBytes(bytes)
                                    cap.copy(url = Uri.fromFile(file).toString())
                                } else null
                            } else null
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrNull()
                }
                update(id) { it.copy(state = DownloadState.DONE, bytes = done.sum(), total = done.sum(), caption = localCaption ?: it.caption) }
            } catch (e: Throwable) {
                if (id !in cancelled) update(id) { it.copy(state = DownloadState.FAILED) }
            } finally {
                writers.remove(id)
                hook(force = true)
            }
        }
    }

    private fun update(id: String, change: (DownloadedVideo) -> DownloadedVideo) {
        val before = _items.value
        val index = before.indexOfFirst { it.item.id == id }
        if (index < 0) return
        val next = change(before[index])
        _items.value = before.toMutableList().also { it[index] = next }
        if (next.state != before[index].state) save()
    }

    private fun hook(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastHook < 1_000) return
        lastHook = now
        val running = _items.value.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
        val bytes = running.sumOf { it.bytes }
        val total = running.sumOf { it.total }
        val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        VideoDownloadHooks.activeCount = running.size
        VideoDownloadHooks.onActive?.invoke(running.size, percent, running.firstOrNull()?.item?.title)
    }

    // ------------------------------------------------------------------ the list, kept across launches

    private fun save() {
        val array = JSONArray()
        _items.value.forEach { d ->
            array.put(
                JSONObject()
                    .put("id", d.item.id).put("title", d.item.title)
                    .put("channel", d.item.channelName ?: "").put("channelId", d.item.channelId ?: "")
                    .put("thumb", d.item.thumbnail ?: "").put("duration", d.item.duration ?: "")
                    .put("height", d.quality.height).put("fps", d.quality.fps)
                    .put("videoUrl", d.quality.videoUrl).put("audioUrl", d.quality.audioUrl ?: "")
                    .put("state", d.state.name).put("bytes", d.bytes).put("total", d.total)
                    .apply {
                        d.caption?.let { c ->
                            put("capLang", c.language)
                            put("capLabel", c.label)
                            put("capUrl", c.url)
                            put("capAuto", c.auto)
                            put("capMime", c.mimeType)
                        }
                    },
            )
        }
        prefs.edit().putString("list", array.toString()).apply()
    }

    private fun load(): List<DownloadedVideo> = runCatching {
        val array = JSONArray(prefs.getString("list", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val capUrl = o.optString("capUrl").ifBlank { null }
            val caption = capUrl?.let {
                VideoCaption(
                    language = o.optString("capLang"),
                    label = o.optString("capLabel"),
                    url = it,
                    auto = o.optBoolean("capAuto", false),
                    mimeType = o.optString("capMime", "text/vtt"),
                )
            }
            DownloadedVideo(
                item = VideoItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    channelName = o.optString("channel").ifBlank { null },
                    channelId = o.optString("channelId").ifBlank { null },
                    duration = o.optString("duration").ifBlank { null },
                    thumbnail = o.optString("thumb").ifBlank { null },
                ),
                quality = VideoQuality(
                    height = o.getInt("height"),
                    fps = o.getInt("fps"),
                    videoUrl = o.getString("videoUrl"),
                    audioUrl = o.optString("audioUrl").ifBlank { null },
                ),
                state = runCatching { DownloadState.valueOf(o.getString("state")) }.getOrDefault(DownloadState.FAILED),
                bytes = o.optLong("bytes"),
                total = o.optLong("total"),
                caption = caption,
            )
        }
    }.getOrDefault(emptyList())
}
