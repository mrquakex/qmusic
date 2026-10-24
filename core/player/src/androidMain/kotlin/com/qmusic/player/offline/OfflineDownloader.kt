package com.qmusic.player.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.qmusic.engine.models.SongItem
import com.qmusic.player.DownloadController
import com.qmusic.player.cache.CacheProvider
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores whole songs in the no-eviction download cache, keyed by video id (not by URL: stream URLs expire),
 * so [com.qmusic.player.adapter.CrossfadeExoPlayerAdapter] can play them with no network at all.
 *
 * How a song is fetched, and why:
 *  - in [CHUNK_BYTES] pieces, several at once, the way yt-dlp and NewPipe do: one long request can stall or be
 *    slowed by the server, while short ones each start fast and a stalled one is just retried;
 *  - every piece has a read timeout and [CHUNK_ATTEMPTS] tries, and what is already cached is never fetched again,
 *    so a retry (or a restart after the app was killed) resumes where it stopped;
 *  - only [MAX_SONGS_AT_ONCE] songs run together and the rest wait, so "download everything" on a library of
 *    hundreds of songs neither floods the connection nor opens hundreds of sockets;
 *  - a foreground service keeps the process alive and shows progress while anything is downloading.
 */
class OfflineDownloader(
    private val context: Context,
    private val resolveUrl: suspend (String) -> String?,
) : DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songGate = Semaphore(MAX_SONGS_AT_ONCE)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cache: Cache get() = CacheProvider.getDownloadCache(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    override val downloadedIds: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _bytes = MutableStateFlow(0L)
    override val downloadedBytes: StateFlow<Long> = _bytes.asStateFlow()

    init {
        scope.launch {
            _downloaded.value = scanCompleteDownloads()
            _bytes.value = runCatching { cache.cacheSpace }.getOrDefault(0L)
        }
    }

    fun isDownloaded(videoId: String): Boolean = videoId in _downloaded.value

    override fun download(song: SongItem) {
        val id = song.id
        if (isDownloaded(id) || jobs.containsKey(id)) return
        com.qmusic.player.SongDirectory.remember(listOf(song))
        // Visible as "waiting" straight away, before it is its turn.
        setProgress(id, 0f)
        ensureServiceRunning()
        jobs[id] = scope.launch {
            val key = cacheKey(id)
            try {
                songGate.withPermit {
                    withTimeout(SONG_TIMEOUT_MS) { fetch(id, key) }
                }
                if (isComplete(key)) _downloaded.value = _downloaded.value + id else error("Incomplete download of $id")
            } catch (cancelled: CancellationException) {
                // What was fetched stays cached, so a later attempt resumes instead of starting over.
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Download of $id failed: ${failure.message}")
                // A half-written song would play as a truncated one; it is only listed once complete, and
                // the cached parts are kept so the next try resumes instead of starting over.
            } finally {
                _progress.value = _progress.value - id
                jobs.remove(id)
                refreshBytes()
            }
        }
    }

    /** Fetches every missing piece of the song into the cache. */
    private suspend fun fetch(id: String, key: String) {
        val url = resolveUrl(id) ?: error("No stream for $id")
        val total = lengthOf(url)
        val uri = Uri.parse(url)
        val pieces = (0 until total step CHUNK_BYTES.toLong()).toList()
        val done = AtomicLong(cachedBytes(key, total))
        setProgress(id, done.get().toFloat() / total)

        coroutineScope {
            val pieceGate = Semaphore(CHUNKS_PER_SONG)
            pieces.map { start ->
                async {
                    pieceGate.withPermit {
                        val length = minOf(CHUNK_BYTES.toLong(), total - start)
                        fetchPiece(uri, key, start, length) { fresh ->
                            setProgress(id, done.addAndGet(fresh).toFloat() / total)
                        }
                    }
                }
            }.awaitAll()
        }
        // The pieces were requested by range, so the total length has to be recorded for the "complete" check.
        cache.applyContentMetadataMutations(
            key,
            ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, total) },
        )
    }

    private suspend fun fetchPiece(uri: Uri, key: String, start: Long, length: Long, onFresh: (Long) -> Unit) {
        var lastFailure: Throwable? = null
        repeat(CHUNK_ATTEMPTS) { attempt ->
            try {
                val dataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(15_000)
                            .setReadTimeoutMs(15_000),
                    )
                    .createDataSource()
                val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(start).setLength(length).build()
                runInterruptible {
                    CacheWriter(dataSource, spec, null) { _, _, newBytes -> if (newBytes > 0) onFresh(newBytes) }.cache()
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                lastFailure = failure
                delay(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastFailure ?: IOException("Piece at $start failed")
    }

    /** The size of the file: YouTube puts it in the URL (`clen`); otherwise ask the server for the first byte. */
    private fun lengthOf(url: String): Long {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        http.newCall(request).execute().use { response ->
            val range = response.header("Content-Range") // bytes 0-0/3109713
            return range?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("Unknown length")
        }
    }

    private fun cachedBytes(key: String, total: Long): Long = runCatching { cache.getCachedBytes(key, 0, total) }.getOrDefault(0L)

    override fun downloadAll(songs: List<SongItem>) {
        songs.forEach(::download)
    }

    override fun remove(songId: String) {
        jobs.remove(songId)?.cancel()
        scope.launch {
            runCatching { cache.removeResource(cacheKey(songId)) }
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
            _downloaded.value.forEach { id -> runCatching { cache.removeResource(cacheKey(id)) } }
            _downloaded.value = emptySet()
            _progress.value = emptyMap()
            refreshBytes()
        }
    }

    override fun streamCacheBytes(): Long = runCatching { CacheProvider.getPlayerCache(context).cacheSpace }.getOrDefault(0L)

    override fun clearStreamCache() {
        scope.launch {
            val streamed = CacheProvider.getPlayerCache(context)
            streamed.keys.toList().forEach { key -> runCatching { streamed.removeResource(key) } }
        }
    }

    /** Keeps the process alive and shows a progress notification while songs are downloading. */
    private fun ensureServiceRunning() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    private fun refreshBytes() {
        _bytes.value = runCatching { cache.cacheSpace }.getOrDefault(0L)
    }

    private fun setProgress(id: String, value: Float) {
        _progress.value = _progress.value + (id to value.coerceIn(0f, 1f))
    }

    private fun scanCompleteDownloads(): Set<String> =
        cache.keys.filter { it.startsWith(KEY_PREFIX) && isComplete(it) }.mapTo(HashSet()) { it.removePrefix(KEY_PREFIX) }

    private fun isComplete(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && length > 0 && cache.isCached(key, 0, length)
    }

    companion object {
        private const val TAG = "QMDownload"
        private const val KEY_PREFIX = "dl:"
        private const val MAX_SONGS_AT_ONCE = 2
        private const val CHUNKS_PER_SONG = 4
        private const val CHUNK_BYTES = 1_048_576
        private const val CHUNK_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 700L
        private const val SONG_TIMEOUT_MS = 6 * 60_000L

        fun cacheKey(videoId: String) = "$KEY_PREFIX$videoId"

        /** A placeholder address: the cache answers for it, so nothing is ever requested from this host. */
        fun offlineUri(videoId: String) = "https://offline.qmusic/$videoId"
    }
}
