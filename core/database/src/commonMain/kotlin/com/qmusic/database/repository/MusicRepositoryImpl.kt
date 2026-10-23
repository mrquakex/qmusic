package com.qmusic.database.repository

import com.qmusic.database.QMusicDatabase
import com.qmusic.database.entity.HistoryEntity
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.database.entity.PlaylistSongCrossRef
import com.qmusic.database.entity.SearchHistoryEntity
import com.qmusic.database.entity.SongEntity
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class MusicRepositoryImpl(
    private val database: QMusicDatabase
) : MusicRepository {

    private val songDao = database.songDao()
    private val playlistDao = database.playlistDao()
    private val historyDao = database.historyDao()
    private val searchHistoryDao = database.searchHistoryDao()

    private var lastTimestamp = 0L

    private fun nextTimestamp(): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        lastTimestamp = if (now > lastTimestamp) now else lastTimestamp + 1
        return lastTimestamp
    }

    override fun getFavorites(): Flow<List<SongItem>> {
        return songDao.getFavoriteSongs().map { list ->
            list.map { it.toSongItem() }
        }
    }

    override fun getHistory(): Flow<List<SongItem>> {
        return historyDao.getRecentHistorySongs().map { list ->
            list.map { it.toSongItem() }
        }
    }

    override suspend fun toggleFavorite(song: SongItem): Unit {
        val existing = songDao.getSongById(song.id)
        if (existing == null) {
            songDao.insertOrUpdate(song.toEntity(isFavorite = true))
        } else {
            songDao.updateFavorite(song.id, !existing.isFavorite)
        }
    }

    override suspend fun recordHistory(song: SongItem): Unit {
        val existing = songDao.getSongById(song.id)
        if (existing == null) {
            songDao.insertOrUpdate(song.toEntity(isFavorite = false))
        }
        val timestamp = nextTimestamp()
        historyDao.insertHistory(
            HistoryEntity(
                songId = song.id,
                playedAt = timestamp
            )
        )
    }

    override fun getPlaylists(): Flow<List<PlaylistEntity>> {
        return playlistDao.getAllPlaylists()
    }

    override suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name,
                createdAt = nextTimestamp()
            )
        )
    }

    override suspend fun addSongToPlaylist(playlistId: Long, song: SongItem): Unit {
        val existing = songDao.getSongById(song.id)
        if (existing == null) {
            songDao.insertOrUpdate(song.toEntity(isFavorite = false))
        }
        playlistDao.insertPlaylistSongCrossRef(
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = song.id
            )
        )
    }

    override fun getPlaylistSongs(playlistId: Long): Flow<List<SongItem>> {
        return playlistDao.getSongsForPlaylist(playlistId).map { list ->
            list.map { it.toSongItem() }
        }
    }

    private fun SongItem.toEntity(isFavorite: Boolean): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artists = artists.joinToString(", ") { it.name },
            duration = duration,
            thumbnailUrl = thumbnail,
            isFavorite = isFavorite,
            isDownloaded = false,
            downloadPath = null
        )
    }

    override suspend fun playCounts(withinDays: Int?): List<Pair<SongItem, Int>> {
        val since = withinDays?.let { Clock.System.now().toEpochMilliseconds() - it * 86_400_000L } ?: 0L
        val counts = historyDao.historySince(since).groupingBy { it.songId }.eachCount()
        if (counts.isEmpty()) return emptyList()
        val songs = counts.keys.chunked(SQL_IN_LIMIT).flatMap { songDao.getSongsByIds(it) }.associateBy { it.id }
        return counts.mapNotNull { (id, plays) -> songs[id]?.let { it.toSongItem() to plays } }
            .sortedByDescending { it.second }
    }

    private fun SongEntity.toSongItem(): SongItem {
        val artistList = if (artists.isBlank()) {
            emptyList()
        } else {
            artists.split(", ").filter { it.isNotBlank() }.map { Artist(name = it) }
        }
        return SongItem(
            id = id,
            title = title,
            artists = artistList,
            duration = duration,
            thumbnail = thumbnailUrl
        )
    }

    override fun getDownloadedSongs(): Flow<List<SongItem>> =
        songDao.getDownloadedSongs().map { list -> list.map { it.toSongItem() } }

    override suspend fun setDownloaded(song: SongItem, downloaded: Boolean): Unit {
        if (songDao.getSongById(song.id) == null) songDao.insertOrUpdate(song.toEntity(isFavorite = false))
        songDao.updateDownloaded(song.id, downloaded)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Unit {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String): Unit {
        playlistDao.renamePlaylist(playlistId, name.trim())
    }

    override suspend fun deletePlaylist(playlistId: Long): Unit {
        playlistDao.clearPlaylist(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    override fun getSearchHistory(limit: Int): Flow<List<String>> =
        searchHistoryDao.getRecent(limit).map { list -> list.map { it.query } }

    override suspend fun recordSearch(query: String): Unit {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchHistoryDao.insert(SearchHistoryEntity(trimmed, nextTimestamp()))
    }

    override suspend fun deleteSearch(query: String): Unit = searchHistoryDao.delete(query)

    override suspend fun clearSearchHistory(): Unit = searchHistoryDao.clear()
}

/** SQLite allows up to 999 bound values in one statement. */
private const val SQL_IN_LIMIT = 900
