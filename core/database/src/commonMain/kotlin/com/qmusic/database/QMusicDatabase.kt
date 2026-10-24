package com.qmusic.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qmusic.database.dao.HistoryDao
import com.qmusic.database.dao.PlaylistDao
import com.qmusic.database.dao.SearchHistoryDao
import com.qmusic.database.dao.SongDao
import com.qmusic.database.entity.HistoryEntity
import com.qmusic.database.entity.PlaylistEntity
import com.qmusic.database.entity.PlaylistSongCrossRef
import com.qmusic.database.entity.SearchHistoryEntity
import com.qmusic.database.entity.SongEntity
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        HistoryEntity::class,
        PlaylistSongCrossRef::class,
        SearchHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
@ConstructedBy(QMusicDatabaseConstructor::class)
abstract class QMusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object QMusicDatabaseConstructor : RoomDatabaseConstructor<QMusicDatabase> {
    override fun initialize(): QMusicDatabase
}

/** v1 -> v2: adds search history without touching favorites, playlists or play history. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))",
        )
    }
}

/** v2 -> v3: playlists keep the order of their songs (existing songs keep the order they were added in). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `playlist_song_cross_ref` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE `playlist_song_cross_ref` SET `position` = rowid")
    }
}

fun getRoomDatabase(builder: RoomDatabase.Builder<QMusicDatabase>): QMusicDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

expect fun createInMemoryDatabase(): QMusicDatabase
