package com.qmusic.ui.videos

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.qmusic.engine.video.VideoQuality
import com.qmusic.player.source.ChunkedDataSource
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * The connections, cache and read-ahead of the video player. One HTTP/2 client keeps the connection to YouTube's
 * servers open between pieces; what was played (and what was read ahead for the next Shorts) stays in a cache of its
 * own, so a clip that loops, a seek back or a video opened again plays without the network.
 */
@UnstableApi
internal object VideoStreaming {
    /** One piece: big enough that asking costs little, small enough that the first frame is not waiting on it. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024
    private const val CACHE_BYTES = 300L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var cache: SimpleCache? = null

    @Synchronized
    private fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.applicationContext.cacheDir, "video_cache"),
        LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
        StandaloneDatabaseProvider(context.applicationContext),
    ).also { cache = it }

    /** Network only, split into pieces: for live streams and anything not worth keeping. */
    fun network(): DataSource.Factory =
        ChunkedDataSource.Factory(OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT), CHUNK_BYTES)

    /**
     * A YouTube file's address changes with every lookup, but the file does not: it is kept under the video's own id,
     * its format and its size.
     */
    private val cacheKeys = CacheKeyFactory { spec ->
        val uri = spec.uri
        val id = uri.getQueryParameter("id")
        val itag = uri.getQueryParameter("itag")
        val clen = uri.getQueryParameter("clen")
        if (uri.host?.endsWith("googlevideo.com") == true && id != null && itag != null) "gv:$id:$itag:${clen.orEmpty()}"
        else spec.key ?: uri.toString()
    }

    fun cached(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(network())
        .setCacheKeyFactory(cacheKeys)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val preloader = Executors.newFixedThreadPool(2) { r -> Thread(r, "video-preload").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val preloaded = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Reads the first seconds of [quality] into the cache, so that when it is opened the picture is there at once
     * (a Short swiped to). Only the start: a clip that is skipped costs a megabyte or two.
     */
    fun preload(context: Context, quality: VideoQuality, pictureBytes: Long = 1_500_000, soundBytes: Long = 300_000) {
        if (quality.isLive) return
        val factory = cached(context)
        listOfNotNull(quality.videoUrl to pictureBytes, quality.audioUrl?.let { it to soundBytes }).forEach { (url, bytes) ->
            if (!preloaded.add(url)) return@forEach
            synchronized(preloaded) { while (preloaded.size > 64) preloaded.remove(preloaded.first()) }
            preloader.execute {
                runCatching {
                    val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(bytes).build()
                    CacheWriter(factory.createDataSource(), spec, null, null).cache()
                }.onFailure { preloaded.remove(url) }
            }
        }
    }
}
