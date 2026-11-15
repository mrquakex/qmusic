package com.qmusic.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerHolder
import com.qmusic.ui.VideoCast
import com.qmusic.ui.VideoCastState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chromecast, for the music and for QVideo. When a TV connects: the video open on the watch page goes to it, or
 * else the music queue from the playing song on (the phone goes quiet; the app's controls drive the TV). When it
 * disconnects, playback comes back to the phone at the same moment.
 *
 * Everything reaches the TV through the phone ([CastProxy]): YouTube gave its addresses to the phone, and turns other
 * devices away; a video's picture and sound go as a DASH description, in HD. Devices without Google Play services
 * simply never offer casting.
 */
@UnstableApi
object CastBridge : SessionAvailabilityListener {
    private var cast: CastPlayer? = null
    private var context: Context? = null
    private val scope = MainScope()
    private var loading: Job? = null
    private var ticker: Job? = null

    /** What the TV plays now: music, a video, or nothing yet. */
    private enum class Mode { NONE, MUSIC, VIDEO }

    private var mode = Mode.NONE

    fun init(context: Context) {
        if (cast != null) return
        this.context = context.applicationContext
        cast = runCatching { CastPlayer(CastContext.getSharedInstance(context.applicationContext)) }
            .onFailure { Log.i(TAG, "Casting is not available: ${it.message}") }
            .getOrNull()
            ?.also { remote ->
                remote.setSessionAvailabilityListener(this)
                remote.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "the TV could not play it: ${error.errorCodeName} ${error.message}")
                        say(if (english()) "The TV could not play it" else "TV bunu oynatamadı")
                    }
                })
            }
        VideoCast.cast = { id, title, thumb, start -> castVideo(id, title, thumb, start) }
        VideoCast.playPause = { cast?.let { if (it.isPlaying) it.pause() else it.play() } }
        VideoCast.seekTo = { ms -> cast?.seekTo(ms) }
        VideoCast.stop = { stopVideo() }
        hookPlayer()
    }

    /** The music player picks a song while casting: it plays on the TV (set again whenever the player is new). */
    private fun hookPlayer() {
        PlayerHolder.peek()?.onRemotePlayAt = { index -> castFrom(index, 0L) }
    }

    override fun onCastSessionAvailable() {
        VideoCast.connected.value = true
        hookPlayer()
        // A video open on the watch page goes to the TV; otherwise the music.
        VideoCast.watching.value?.let { (id, title, thumb) ->
            castVideo(id, title, thumb, VideoCast.phonePosition?.invoke() ?: 0L)
            return
        }
        val player = PlayerHolder.peek() ?: return
        val remote = cast ?: return
        mode = Mode.MUSIC
        if (player.queueIndex.value < 0) {
            // Nothing playing yet: the next song chosen goes to the TV.
            player.attachRemote(remote, 0)
            return
        }
        castFrom(player.queueIndex.value, player.playbackPosition.value)
    }

    override fun onCastSessionUnavailable() {
        VideoCast.connected.value = false
        loading?.cancel()
        ticker?.cancel()
        when (mode) {
            Mode.MUSIC -> {
                val player = PlayerHolder.peek()
                if (player != null) {
                    val index = player.queueIndex.value
                    // The moment shown last (read from the TV while it played): the TV's own may already be gone.
                    val position = player.playbackPosition.value
                    player.attachRemote(null)
                    if (index >= 0) player.resumeLocally(index, position)
                }
            }
            Mode.VIDEO -> VideoCast.state.value?.let { VideoCast.backAt.value = it.videoId to it.positionMs }
            Mode.NONE -> Unit
        }
        VideoCast.state.value = null
        VideoCast.onPhone.value = null
        mode = Mode.NONE
        CastProxy.stop()
    }

    // ------------------------------------------------------------------ music

    /** Sends the queue from [index] on to the TV, starting at [positionMs]. */
    private fun castFrom(index: Int, positionMs: Long) {
        val player = PlayerHolder.peek() ?: return
        val remote = cast ?: return
        val songs = player.queue.value.drop(index).take(CAST_QUEUE)
        if (songs.isEmpty()) return
        mode = Mode.MUSIC
        VideoCast.state.value = null
        loading?.cancel()
        loading = scope.launch {
            // The first song that can be had starts it (one that cannot is skipped, not left silent).
            var first: MediaItem? = null
            var skipped = 0
            for (song in songs) {
                first = item(song)
                if (first != null) break
                skipped++
            }
            if (first == null) {
                say(if (english()) "This song could not be sent to the TV" else "Bu şarkı TV'ye gönderilemedi")
                return@launch
            }
            remote.setMediaItem(first, positionMs.takeIf { skipped == 0 } ?: 0L)
            remote.prepare()
            remote.playWhenReady = true
            player.attachRemote(remote, index + skipped)
            // The rest follow one by one, so the first song does not wait for all of them.
            songs.drop(skipped + 1).forEach { song -> item(song)?.let(remote::addMediaItem) }
        }
    }

    private suspend fun item(song: SongItem): MediaItem? {
        val (url, mimeType) = PlayerHolder.resolveCastUrl(song.id) ?: return null
        val local = CastProxy.stream(url) ?: url
        return MediaItem.Builder()
            .setUri(local)
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

    // ------------------------------------------------------------------ videos

    private fun castVideo(videoId: String, title: String, thumbnail: String?, startMs: Long) {
        val remote = cast ?: return
        if (!VideoCast.connected.value) return
        loading?.cancel()
        VideoCast.onPhone.value = null
        VideoCast.backAt.value = null
        loading = scope.launch {
            // The music stops being cast (and playing) while a video is on the TV.
            PlayerHolder.peek()?.let { p ->
                if (p.isCasting) p.attachRemote(null)
                p.pause()
            }
            mode = Mode.VIDEO
            val streams = PlayerHolder.youTube.castStreams(videoId).getOrNull()
            val manifest = streams?.let { CastProxy.dash(it) }
            if (manifest == null) {
                // It stays on the phone, from where it was.
                mode = Mode.NONE
                VideoCast.onPhone.value = videoId
                VideoCast.backAt.value = videoId to startMs
                say(if (english()) "This video could not be sent to the TV" else "Bu video TV'ye gönderilemedi")
                return@launch
            }
            val item = MediaItem.Builder()
                .setUri(manifest)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setMediaId(videoId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title.ifBlank { streams.title })
                        .setArtworkUri(thumbnail?.let(Uri::parse))
                        .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                        .build(),
                )
                .build()
            remote.setMediaItem(item, startMs)
            remote.prepare()
            remote.playWhenReady = true
            VideoCast.state.value = VideoCastState(videoId, title, startMs, streams.durationSec * 1000, true)
            watchVideo(videoId, title, streams.durationSec * 1000)
        }
    }

    /** Reads the TV's place in the video for the watch page (its bar, and where to go on from on the phone). */
    private fun watchVideo(videoId: String, title: String, durationMs: Long) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && mode == Mode.VIDEO) {
                val r = cast ?: break
                VideoCast.state.value = VideoCastState(videoId, title, r.currentPosition, if (r.duration > 0) r.duration else durationMs, r.isPlaying)
                delay(500)
            }
        }
    }

    private fun stopVideo() {
        val state = VideoCast.state.value ?: return
        ticker?.cancel()
        VideoCast.backAt.value = state.videoId to state.positionMs
        VideoCast.onPhone.value = state.videoId
        cast?.stop()
        cast?.clearMediaItems()
        VideoCast.state.value = null
        mode = Mode.NONE
    }

    // ------------------------------------------------------------------ small things

    private fun english(): Boolean =
        context?.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)?.getString("language", "tr") == "en"

    private fun say(text: String) {
        VideoCast.message.value = text
        context?.let { c -> scope.launch { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show() } }
    }

    private const val TAG = "QMCast"
    private const val CAST_QUEUE = 25
}
