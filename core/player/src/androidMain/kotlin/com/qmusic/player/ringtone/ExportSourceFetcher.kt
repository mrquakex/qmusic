package com.qmusic.player.ringtone

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerHolder
import com.qmusic.player.cache.CacheProvider
import com.qmusic.player.offline.OfflineDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Brings a song's sound into one file on the device, to cut a ringtone from. A song that is downloaded is read from
 * its download; any other is fetched in short pieces (long single requests are what YouTube slows down), each retried.
 */
object ExportSourceFetcher {

    /** The longest song that is taken: a ringtone from a podcast or a mix is not what this is for. */
    const val MAX_BYTES = 64L * 1024 * 1024

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Writes [song]'s sound to [target]; [onProgress] gets 0..1. Throws when it cannot. */
    suspend fun fetch(context: Context, song: SongItem, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val downloaded = PlayerHolder.downloads(context).isDownloaded(song.id)
            try {
                if (downloaded) copyDownload(context, song.id, target, onProgress) else fromNetwork(context, song, target, onProgress)
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }

    private suspend fun copyDownload(context: Context, id: String, target: File, onProgress: (Float) -> Unit) {
        val factory = CacheDataSource.Factory()
            .setCache(CacheProvider.getDownloadCache(context))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val source = factory.createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(OfflineDownloader.offlineUri(id)))
            .setKey(OfflineDownloader.cacheKey(id))
            .build()
        try {
            val length = source.open(spec)
            if (length != C.LENGTH_UNSET.toLong() && length > MAX_BYTES) throw IOException("Too long")
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(BUFFER)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                    done += n
                    if (length > 0) onProgress((done.toFloat() / length).coerceIn(0f, 1f))
                }
            }
        } finally {
            runCatching { source.close() }
        }
    }

    private suspend fun fromNetwork(context: Context, song: SongItem, target: File, onProgress: (Float) -> Unit) {
        val url = PlayerHolder.resolveBestAudioUrl(song.id) ?: throw IOException("No stream for ${song.id}")
        val total = lengthOf(url)
        if (total > MAX_BYTES) throw IOException("Too long")
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        FileOutputStream(target).use { out ->
            var position = 0L
            while (position < total) {
                currentCoroutineContext().ensureActive()
                val length = minOf(PIECE, total - position)
                position += piece(factory, Uri.parse(url), position, length, out)
                onProgress((position.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /** One piece of the file, tried again a few times: a stalled piece is just asked for once more. */
    private suspend fun piece(factory: DataSource.Factory, uri: Uri, position: Long, length: Long, out: FileOutputStream): Long {
        var failure: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            val source = factory.createDataSource()
            val data = java.io.ByteArrayOutputStream()
            try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    data.write(buffer, 0, n)
                }
                // Written only once the piece is whole, so a retry never leaves half a piece in the file.
                data.writeTo(out)
                return data.size().toLong().also { if (it <= 0) throw IOException("Empty piece at $position") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                failure = error
                delay(BACKOFF_MS * (attempt + 1))
            } finally {
                runCatching { source.close() }
            }
        }
        throw failure ?: IOException("Piece at $position failed")
    }

    /** The size of the file: YouTube puts it in the address (`clen`); otherwise the server is asked for the first byte. */
    private fun lengthOf(url: String): Long {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        http.newCall(request).execute().use { response ->
            return response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("Unknown length")
        }
    }

    private const val PIECE = 1_048_576L
    private const val BUFFER = 32 * 1024
    private const val ATTEMPTS = 4
    private const val BACKOFF_MS = 600L
}
