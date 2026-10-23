package com.qmusic.player.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Provides dedicated Media3 SimpleCache instances:
 * - [PLAYER_CACHE]: LRU eviction for streaming playback cache.
 * - [DOWNLOAD_CACHE]: No-eviction persistent cache for offline downloaded songs.
 */
object CacheProvider {

    private const val PLAYER_CACHE_DIR = "player_cache"
    private const val DOWNLOAD_CACHE_DIR = "download_cache"
    private const val DEFAULT_PLAYER_CACHE_SIZE = 512L * 1024 * 1024 // 512 MiB

    @Volatile
    private var playerCache: SimpleCache? = null

    @Volatile
    private var downloadCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    /**
     * Returns the LRU cache used for streaming playback.
     */
    @Synchronized
    fun getPlayerCache(
        context: Context,
        maxSizeBytes: Long = DEFAULT_PLAYER_CACHE_SIZE,
    ): SimpleCache {
        return playerCache ?: run {
            val cacheDir = File(context.applicationContext.cacheDir, PLAYER_CACHE_DIR)
            val evictor = LeastRecentlyUsedCacheEvictor(maxSizeBytes)
            SimpleCache(cacheDir, evictor, getDatabaseProvider(context)).also {
                playerCache = it
            }
        }
    }

    /**
     * Returns the persistent cache used for downloaded audio files without automatic eviction.
     */
    @Synchronized
    fun getDownloadCache(context: Context): SimpleCache {
        return downloadCache ?: run {
            val cacheDir = File(context.applicationContext.filesDir, DOWNLOAD_CACHE_DIR)
            val evictor = NoOpCacheEvictor()
            SimpleCache(cacheDir, evictor, getDatabaseProvider(context)).also {
                downloadCache = it
            }
        }
    }

    /**
     * Releases active cache instances.
     */
    @Synchronized
    fun release() {
        try {
            playerCache?.release()
        } catch (_: Exception) {}
        playerCache = null

        try {
            downloadCache?.release()
        } catch (_: Exception) {}
        downloadCache = null

        databaseProvider = null
    }
}
