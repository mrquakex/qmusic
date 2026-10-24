package com.qmusic.ui.viewmodel

import com.qmusic.engine.YouTube
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.SpotifyIds
import com.qmusic.settings.AppSettings
import com.qmusic.settings.SettingKeys
import com.qmusic.spotify.SpotifyImporter
import com.qmusic.spotify.model.SpotifyTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spotify gives the songs, YouTube the sound: for a Spotify track this finds the same recording (title, artists
 * and length compared) and remembers it, so a song is only looked up once, ever.
 */
class SpotifyLinker(
    private val youTube: YouTube,
    private val importer: SpotifyImporter,
    private val settings: AppSettings,
) {
    private val lock = Mutex()
    private val matches: MutableMap<String, String> by lazy {
        settings.string(SettingKeys.SPOTIFY_MATCHES, "").value.split(';')
            .mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0] to it[1] } }
            .toMap(HashMap())
    }

    /** The YouTube video to play for [song]: itself unless it is a Spotify track. */
    suspend fun youtubeId(song: SongItem): String? {
        val spotifyId = SpotifyIds.trackId(song.id) ?: return song.id
        lock.withLock { matches[spotifyId] }?.let { return it }
        val track = SpotifyTrack(
            id = spotifyId,
            title = song.title,
            artists = song.artists.map { it.name },
            album = song.album?.name,
            durationMs = song.duration?.let { it * 1000L },
        )
        val found = importer.matchTrackToYouTube(track, youTube).getOrNull()?.id ?: return null
        lock.withLock {
            matches[spotifyId] = found
            settings.setString(SettingKeys.SPOTIFY_MATCHES, matches.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        return found
    }
}
