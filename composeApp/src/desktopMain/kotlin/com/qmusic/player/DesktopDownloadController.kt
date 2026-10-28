package com.qmusic.player

import com.qmusic.engine.models.SongItem
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Desktop implementation of [DownloadController] storing downloaded `.m4a` files in `~/.qmusic/downloads`
 * and streaming cache files in `~/.qmusic/cache`.
 */
class DesktopDownloadController(
    baseDir: File = File(System.getProperty("user.home"), ".qmusic"),
    private val resolveUrl: suspend (String) -> String?,
    private val onStaleUrl: (String) -> Unit = {},
) : DownloadController {

    private val downloadsDir = File(baseDir, "downloads").apply { mkdirs() }
    private val cacheDir = File(baseDir, "cache").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songGate = Semaphore(MAX_SONGS_AT_ONCE)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _downloaded = MutableStateFlow<Set<String>>(scanDownloads())
    override val downloadedIds: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _bytes = MutableStateFlow(computeDirBytes(downloadsDir))
    override val downloadedBytes: StateFlow<Long> = _bytes.asStateFlow()

    private fun downloadFile(id: String): File = File(downloadsDir, "${sanitizeFileName(id)}.m4a")
    private fun cacheFile(id: String): File = File(cacheDir, "${sanitizeFileName(id)}.m4a")

    private fun sanitizeFileName(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    fun getDownloadedFile(id: String): File? =
        downloadFile(id).takeIf { it.exists() && it.length() > 1024 }

    /**
     * Returns a local playable `.m4a` file for [id]: from `downloads` if downloaded,
     * from `cache` if already cached, or fetches and defragments the stream into `cache`.
     */
    suspend fun getOrFetchPlayableFile(
        id: String,
        onProgressFraction: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        getDownloadedFile(id)?.let {
            onProgressFraction(1f)
            return@withContext it
        }
        val cached = cacheFile(id)
        if (cached.exists() && cached.length() > 1024) {
            onProgressFraction(1f)
            return@withContext cached
        }
        val mutex = fetchLocks.getOrPut(id) { Mutex() }
        mutex.withLock {
            getDownloadedFile(id)?.let {
                onProgressFraction(1f)
                return@withLock it
            }
            if (cached.exists() && cached.length() > 1024) {
                onProgressFraction(1f)
                return@withLock cached
            }
            fetchStreamToFile(id, cached, onProgressFraction)
            cached
        }
    }

    override fun download(song: SongItem) {
        val id = song.id
        if (id in _downloaded.value || jobs.containsKey(id)) return
        setProgress(id, 0f)
        jobs[id] = scope.launch {
            try {
                songGate.withPermit {
                    val target = downloadFile(id)
                    val cached = cacheFile(id)
                    if (cached.exists() && cached.length() > 1024) {
                        cached.copyTo(target, overwrite = true)
                        setProgress(id, 1f)
                    } else {
                        fetchStreamToFile(id, target) { fraction ->
                            setProgress(id, fraction)
                        }
                    }
                    if (target.exists() && target.length() > 1024) {
                        _downloaded.value = _downloaded.value + id
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep partial state clean
            } finally {
                _progress.value = _progress.value - id
                jobs.remove(id)
                refreshBytes()
            }
        }
    }

    override fun downloadAll(songs: List<SongItem>) {
        songs.forEach(::download)
    }

    override fun remove(songId: String) {
        jobs.remove(songId)?.cancel()
        scope.launch {
            runCatching { downloadFile(songId).delete() }
            _downloaded.value = _downloaded.value - songId
            _progress.value = _progress.value - songId
            refreshBytes()
        }
    }

    override fun cancelAll() {
        jobs.values.toList().forEach { it.cancel() }
    }

    override fun removeAll() {
        cancelAll()
        scope.launch {
            downloadsDir.listFiles()?.forEach { runCatching { it.delete() } }
            _downloaded.value = emptySet()
            _progress.value = emptyMap()
            refreshBytes()
        }
    }

    override fun streamCacheBytes(): Long = computeDirBytes(cacheDir)

    override fun clearStreamCache() {
        scope.launch {
            cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun fetchStreamToFile(
        id: String,
        target: File,
        onProgressFraction: (Float) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                if (attempt > 0) onStaleUrl(id)
                val url = resolveUrl(id) ?: throw IOException("Stream URL could not be resolved for $id")
                val rawBytes = downloadChunked(url, onProgressFraction)
                val playableBytes = DashMp4Defragmenter.defragmentIfNeeded(rawBytes)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(playableBytes)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                onProgressFraction(1f)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IOException("Failed to fetch stream for $id")
    }

    private suspend fun downloadChunked(
        url: String,
        onProgressFraction: (Float) -> Unit,
    ): ByteArray {
        val total = lengthOf(url)
        if (total <= 0L) {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: throw IOException("Empty body")
                onProgressFraction(1f)
                return bytes
            }
        }

        val output = ByteArray(total.toInt())
        val pieces = (0L until total step CHUNK_BYTES.toLong()).toList()
        val downloaded = AtomicLong(0L)

        coroutineScope {
            val gate = Semaphore(CHUNKS_PER_SONG)
            pieces.map { start ->
                async {
                    gate.withPermit {
                        val endInclusive = minOf(start + CHUNK_BYTES - 1L, total - 1L)
                        val chunk = fetchRange(url, start, endInclusive)
                        System.arraycopy(chunk, 0, output, start.toInt(), chunk.size)
                        val done = downloaded.addAndGet(chunk.size.toLong())
                        onProgressFraction((done.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }.awaitAll()
        }
        return output
    }

    private suspend fun fetchRange(url: String, start: Long, endInclusive: Long): ByteArray {
        var lastFailure: Throwable? = null
        repeat(CHUNK_ATTEMPTS) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$start-$endInclusive")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) throw IOException("HTTP ${resp.code}")
                    return resp.body?.bytes() ?: throw IOException("Empty range body")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                lastFailure = e
                delay(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastFailure ?: IOException("Range $start-$endInclusive failed")
    }

    private fun lengthOf(url: String): Long {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        http.newCall(request).execute().use { response ->
            val range = response.header("Content-Range")
            return range?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: -1L
        }
    }

    private fun setProgress(id: String, value: Float) {
        _progress.value = _progress.value + (id to value.coerceIn(0f, 1f))
    }

    private fun refreshBytes() {
        _bytes.value = computeDirBytes(downloadsDir)
    }

    private fun scanDownloads(): Set<String> =
        downloadsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".m4a") && it.length() > 1024 }
            ?.mapTo(HashSet()) { it.name.removeSuffix(".m4a") }
            ?: emptySet()

    private fun computeDirBytes(dir: File): Long =
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private companion object {
        const val MAX_SONGS_AT_ONCE = 2
        const val CHUNKS_PER_SONG = 4
        const val CHUNK_BYTES = 1_048_576
        const val CHUNK_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L
        const val USER_AGENT = "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
    }
}
