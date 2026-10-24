package com.qmusic.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.qmusic.engine.models.SongItem
import com.qmusic.database.repository.MusicRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A database written by an older version opens in the new one with every playlist and its order kept. */
class MigrationTest {

    @Test
    fun version2PlaylistsKeepTheirSongsInTheOrderTheyWereAdded() = runTest {
        val file = File.createTempFile("qmusic-migration", ".db").apply { delete() }
        try {
            // Today's tables, then the playlist songs put back the way version 2 stored them (no position).
            val current = getRoomDatabase(Room.databaseBuilder<QMusicDatabase>(name = file.absolutePath))
            val repository = MusicRepositoryImpl(current)
            val playlist = repository.createPlaylist("Road")
            listOf("first", "second", "third").forEach { repository.addSongToPlaylist(playlist, SongItem(id = it, title = it)) }
            current.close()

            val connection = BundledSQLiteDriver().open(file.absolutePath)
            connection.execSQL("CREATE TABLE `old_ref` AS SELECT `playlistId`, `songId` FROM `playlist_song_cross_ref` ORDER BY `position`")
            connection.execSQL("DROP TABLE `playlist_song_cross_ref`")
            connection.execSQL(
                "CREATE TABLE `playlist_song_cross_ref` (`playlistId` INTEGER NOT NULL, `songId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `songId`))",
            )
            connection.execSQL("INSERT INTO `playlist_song_cross_ref` SELECT `playlistId`, `songId` FROM `old_ref`")
            connection.execSQL("DROP TABLE `old_ref`")
            connection.execSQL("PRAGMA user_version = 2")
            connection.close()

            // Opening runs the 2 -> 3 migration; Room checks the result against the entities as it opens.
            val migrated = getRoomDatabase(Room.databaseBuilder<QMusicDatabase>(name = file.absolutePath))
            val songs = MusicRepositoryImpl(migrated).getPlaylistSongs(playlist).first()
            assertEquals(listOf("first", "second", "third"), songs.map { it.id })
            migrated.close()
        } finally {
            file.delete()
            File(file.absolutePath + "-wal").delete()
            File(file.absolutePath + "-shm").delete()
        }
    }
}
