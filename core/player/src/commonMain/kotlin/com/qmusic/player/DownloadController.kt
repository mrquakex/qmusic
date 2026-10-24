package com.qmusic.player

import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Saves songs on the device so they play without a connection. */
interface DownloadController {
    /** Ids of the songs that are completely downloaded. */
    val downloadedIds: StateFlow<Set<String>>

    /**
     * Songs being downloaded or waiting for their turn, with the progress (0..1) of each; waiting ones sit at 0.
     * Its size is therefore the number of downloads still to finish.
     */
    val progress: StateFlow<Map<String, Float>>

    /** Space the downloaded songs take on the device, in bytes. */
    val downloadedBytes: StateFlow<Long>

    fun download(song: SongItem)

    /** Queues every song that is not downloaded yet; a few download at a time. */
    fun downloadAll(songs: List<SongItem>)

    fun remove(songId: String)

    /** Stops what is downloading or queued; finished downloads stay. */
    fun cancelAll()

    /** Deletes every downloaded song (and stops what is still downloading). */
    fun removeAll()

    /** Space taken by songs kept from streaming (not the downloads), in bytes. */
    fun streamCacheBytes(): Long = 0L

    /** Empties what streaming kept; downloads stay. */
    fun clearStreamCache() = Unit
}

/** Used where nothing can be stored (desktop previews, tests). */
class NoOpDownloadController : DownloadController {
    override val downloadedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
    override val progress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap<String, Float>()).asStateFlow()
    override val downloadedBytes: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

    override fun download(song: SongItem) = Unit

    override fun downloadAll(songs: List<SongItem>) = Unit

    override fun remove(songId: String) = Unit

    override fun cancelAll() = Unit

    override fun removeAll() = Unit
}
