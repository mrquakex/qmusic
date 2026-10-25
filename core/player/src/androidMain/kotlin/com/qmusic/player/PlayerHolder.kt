package com.qmusic.player

import android.content.Context
import com.qmusic.engine.YouTube
import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.SpotifyIds
import com.qmusic.player.source.StreamUrlStore
import com.qmusic.player.adapter.CrossfadeExoPlayerAdapter
import com.qmusic.player.offline.OfflineDownloader

/**
 * Process-wide owner of the single [CrossfadeExoPlayerAdapter].
 *
 * The UI talks to the adapter directly (it is the [PlayerController]) while
 * [com.qmusic.player.service.SimpleMediaService] exposes the same instance through a MediaSession,
 * so notification / lock-screen controls and in-app controls always drive the same player.
 */
object PlayerHolder {
    @Volatile
    private var instance: CrossfadeExoPlayerAdapter? = null

    /** Shared YouTube engine used to turn a video id into a playable (deciphered) audio URL. */
    val youTube: YouTube by lazy { YouTube() }

    @Volatile
    private var offlineDownloader: OfflineDownloader? = null

    /** Process-wide downloader; the player consults it so downloaded songs play without the network. */
    @Volatile
    private var urlStore: StreamUrlStore? = null

    private fun store(context: Context): StreamUrlStore =
        urlStore ?: synchronized(this) { urlStore ?: StreamUrlStore(context.applicationContext).also { urlStore = it } }

    fun downloads(context: Context): OfflineDownloader =
        offlineDownloader ?: synchronized(this) {
            store(context)
            offlineDownloader ?: OfflineDownloader(
                context = context.applicationContext,
                // Saved songs use the quality chosen for downloads (the best one unless changed in Settings).
                resolveUrl = { id -> resolveAudioUrl(id, downloadQuality(context)) },
            ).also { offlineDownloader = it }
        }

    fun get(context: Context): CrossfadeExoPlayerAdapter =
        instance ?: synchronized(this) {
            instance ?: store(context).let { CrossfadeExoPlayerAdapter(
                context = context.applicationContext,
                streamUrlResolver = { id -> resolveAudioUrl(id, audioQuality) },
                loudnessResolver = { id -> youtubeIdFor(id)?.let { youTube.loudnessDb(it).getOrNull() } },
                onStaleUrl = { id -> urlStore?.remove(id) },
                offline = downloads(context),
                videoResolver = ::resolveVideo,
            ) }.also { instance = it }
        }

    fun peek(): CrossfadeExoPlayerAdapter? = instance

    private fun downloadQuality(context: Context): AudioQuality =
        context.applicationContext.getSharedPreferences(com.qmusic.player.cache.CacheProvider.SETTINGS_FILE, Context.MODE_PRIVATE)
            .getString("download_quality", null)
            ?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
            ?: AudioQuality.HIGH

    /** The music video of [videoId] (720p at most, to keep playback smooth on mobile data), or null. */
    private suspend fun resolveVideo(videoId: String) =
        youtubeIdFor(videoId)?.let { youTube.resolveVideoSource(it).getOrNull() }

    /**
     * Finds the YouTube recording of a song that came from Spotify (set by the app, which keeps what it found);
     * null while there is no Spotify account.
     */
    @Volatile
    var spotifyMatcher: (suspend (SongItem) -> String?)? = null

    /** The YouTube video to stream for [id]: itself, or for a Spotify song the recording found for it. */
    suspend fun youtubeIdFor(id: String): String? {
        if (!SpotifyIds.isSpotify(id)) return id
        val song = SongDirectory.get(id) ?: instance?.queue?.value?.firstOrNull { it.id == id } ?: return null
        return spotifyMatcher?.invoke(song)
    }

    /** The quality streamed right now; set from the settings and the kind of connection. */
    @Volatile
    var audioQuality: AudioQuality = AudioQuality.HIGH

    /**
     * An address a Chromecast can play: the best AAC (audio/mp4) stream, which every cast device decodes; other
     * formats only when there is no AAC one.
     */
    suspend fun resolveCastUrl(videoId: String): Pair<String, String>? {
        val streams = youTube.resolveStreamUrls(youtubeIdFor(videoId) ?: return null).getOrNull()
            ?.filter { !it.isDrm && it.mimeType.startsWith("audio") }
            .orEmpty()
        val pick = streams.filter { it.mimeType.startsWith("audio/mp4") }.maxByOrNull(AudioStream::bitrate)
            ?: streams.maxByOrNull(AudioStream::bitrate)
            ?: return null
        return pick.url to pick.mimeType.substringBefore(';')
    }

    /** The address of the best audio stream of [videoId] (a ringtone is cut from the best sound there is). */
    suspend fun resolveBestAudioUrl(videoId: String): String? = resolveAudioUrl(videoId, AudioQuality.HIGH)

    /** Picks the non-DRM audio stream of [videoId] that matches [quality], or null if none could be resolved. */
    private suspend fun resolveAudioUrl(videoId: String, quality: AudioQuality): String? {
        val key = "${quality.name}:$videoId"
        urlStore?.get(key)?.let { return it }
        val streams = youTube.resolveStreamUrls(youtubeIdFor(videoId) ?: return null)
            .getOrNull()
            ?.filter { !it.isDrm && it.mimeType.startsWith("audio") }
            .orEmpty()
        val pick = when (quality) {
            AudioQuality.HIGH -> streams.maxByOrNull(AudioStream::bitrate)
            AudioQuality.LOW -> streams.minByOrNull(AudioStream::bitrate)
            AudioQuality.NORMAL -> streams.minByOrNull { kotlin.math.abs(it.bitrate - NORMAL_BITRATE) }
        }
        return pick?.url?.also { urlStore?.put(key, it) }
    }

    private const val NORMAL_BITRATE = 128_000
}
