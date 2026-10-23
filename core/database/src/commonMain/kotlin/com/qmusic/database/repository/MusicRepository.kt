package com.qmusic.database.repository

import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    fun getFavorites(): Flow<List<SongItem>>
    fun getHistory(): Flow<List<SongItem>>
    suspend fun toggleFavorite(song: SongItem): Unit
    suspend fun recordHistory(song: SongItem): Unit
    fun getPlaylists(): Flow<List<PlaylistEntity>>
    suspend fun createPlaylist(name: String): Long
    suspend fun addSongToPlaylist(playlistId: Long, song: SongItem): Unit
    fun getPlaylistSongs(playlistId: Long): Flow<List<SongItem>>
    fun getDownloadedSongs(): Flow<List<SongItem>>
    suspend fun setDownloaded(song: SongItem, downloaded: Boolean): Unit
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Unit
    suspend fun renamePlaylist(playlistId: Long, name: String): Unit
    suspend fun deletePlaylist(playlistId: Long): Unit
    fun getSearchHistory(limit: Int = 20): Flow<List<String>>
    suspend fun recordSearch(query: String): Unit
    suspend fun deleteSearch(query: String): Unit
    suspend fun clearSearchHistory(): Unit
}
