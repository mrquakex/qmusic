package com.qmusic.database

import androidx.room.Room

actual fun createInMemoryDatabase(): QMusicDatabase {
    return getRoomDatabase(Room.inMemoryDatabaseBuilder<QMusicDatabase>())
}
