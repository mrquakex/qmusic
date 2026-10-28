package com.qmusic.database

import androidx.room.Room
import java.io.File

actual fun createInMemoryDatabase(): QMusicDatabase {
    return getRoomDatabase(Room.inMemoryDatabaseBuilder<QMusicDatabase>())
}

fun createJvmDatabase(
    dbFile: File = File(System.getProperty("user.home"), ".qmusic/qmusic.db"),
): QMusicDatabase {
    dbFile.parentFile?.mkdirs()
    return getRoomDatabase(Room.databaseBuilder<QMusicDatabase>(name = dbFile.absolutePath))
}
