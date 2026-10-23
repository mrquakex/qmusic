package com.qmusic.database

import com.qmusic.database.repository.MusicRepository
import com.qmusic.database.repository.MusicRepositoryImpl
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicRepositoryTest {

    private lateinit var database: QMusicDatabase
    private lateinit var repository: MusicRepository

    private val sampleSong = SongItem(
        id = "test_song_1",
        title = "Test Song 1",
        artists = listOf(Artist(name = "Test Artist")),
        duration = 210,
        thumbnail = "https://example.com/thumb.jpg"
    )

    private val sampleSong2 = SongItem(
        id = "test_song_2",
        title = "Test Song 2",
        artists = listOf(Artist(name = "Another Artist")),
        duration = 180,
        thumbnail = "https://example.com/thumb2.jpg"
    )

    @BeforeTest
    fun setup() {
        database = createInMemoryDatabase()
        repository = MusicRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun toggleFavorite_addsAndRemovesFavorite() = runTest {
        // Initially empty
        val initialFavorites = repository.getFavorites().first()
        assertTrue(initialFavorites.isEmpty())

        // Toggle to favorite
        repository.toggleFavorite(sampleSong)
        val favoritesAfterAdd = repository.getFavorites().first()
        assertEquals(1, favoritesAfterAdd.size)
        assertEquals("test_song_1", favoritesAfterAdd[0].id)
        assertEquals("Test Song 1", favoritesAfterAdd[0].title)

        // Toggle again to unfavorite
        repository.toggleFavorite(sampleSong)
        val favoritesAfterRemove = repository.getFavorites().first()
        assertTrue(favoritesAfterRemove.isEmpty())
    }

    @Test
    fun recordHistory_recordsSongAndUpdatesHistoryList() = runTest {
        val initialHistory = repository.getHistory().first()
        assertTrue(initialHistory.isEmpty())

        repository.recordHistory(sampleSong)
        repository.recordHistory(sampleSong2)

        val history = repository.getHistory().first()
        assertEquals(2, history.size)
        // Most recent should be first
        assertEquals("test_song_2", history[0].id)
        assertEquals("test_song_1", history[1].id)
    }

    @Test
    fun createPlaylist_and_addSongToPlaylist_managesPlaylistsAndRelations() = runTest {
        val initialPlaylists = repository.getPlaylists().first()
        assertTrue(initialPlaylists.isEmpty())

        val playlistId = repository.createPlaylist("My Chill Mix")
        assertTrue(playlistId > 0)

        val playlists = repository.getPlaylists().first()
        assertEquals(1, playlists.size)
        assertEquals("My Chill Mix", playlists[0].name)

        // Add songs to playlist
        repository.addSongToPlaylist(playlistId, sampleSong)
        repository.addSongToPlaylist(playlistId, sampleSong2)

        val playlistSongs = repository.getPlaylistSongs(playlistId).first()
        assertEquals(2, playlistSongs.size)
        assertTrue(playlistSongs.any { it.id == "test_song_1" })
        assertTrue(playlistSongs.any { it.id == "test_song_2" })
    }
}
