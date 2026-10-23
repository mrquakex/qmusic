package com.qmusic.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Chromecast: when a cast session starts, the queue from the playing song on is sent to the cast device and the
 * phone goes quiet; the app's controls then drive the cast device. When the session ends, playback comes back to
 * the phone at the same song and moment. Devices without Google Play services simply never offer casting.
 */
@UnstableApi
object CastBridge : SessionAvailabilityListener {
    private var cast: CastPlayer? = null
    private val scope = MainScope()
    private var loading: Job? = null

    fun init(context: Context) {
        if (cast != null) return
        cast = runCatching { CastPlayer(CastContext.getSharedInstance(context.applicationContext)) }
            .onFailure { Log.i(TAG, "Casting is not available: ${it.message}") }
            .getOrNull()
            ?.also { it.setSessionAvailabilityListener(this) }
        PlayerHolder.peek()?.onRemotePlayAt = { index -> castFrom(index, 0L) }
    }

    override fun onCastSessionAvailable() {
        val player = PlayerHolder.peek() ?: return
        if (player.queueIndex.value < 0) return
        castFrom(player.queueIndex.value, player.playbackPosition.value)
    }

    override fun onCastSessionUnavailable() {
        val player = PlayerHolder.peek() ?: return
        val remote = cast ?: return
        loading?.cancel()
        val index = player.queueIndex.value
        val position = remote.currentPosition
        player.attachRemote(null)
        if (index >= 0) player.resumeLocally(index, position)
    }

    /** Sends the queue from [index] on to the cast device, starting at [positionMs]. */
    private fun castFrom(index: Int, positionMs: Long) {
        val player = PlayerHolder.peek() ?: return
        val remote = cast ?: return
        val songs = player.queue.value.drop(index).take(CAST_QUEUE)
        if (songs.isEmpty()) return
        loading?.cancel()
        loading = scope.launch {
            val first = item(songs.first()) ?: return@launch
            remote.setMediaItem(first, positionMs)
            remote.prepare()
            remote.playWhenReady = true
            player.attachRemote(remote, index)
            // The rest follow one by one, so the first song does not wait for all of them.
            songs.drop(1).forEach { song -> item(song)?.let(remote::addMediaItem) }
        }
    }

    private suspend fun item(song: SongItem): MediaItem? {
        val (url, mimeType) = PlayerHolder.resolveCastUrl(song.id) ?: return null
        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeType)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artists.joinToString { it.name })
                    .setArtworkUri((song.thumbnail ?: song.thumbnails.lastOrNull()?.url)?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    private const val TAG = "QMCast"
    private const val CAST_QUEUE = 25
}
