package com.qmusic.database

import android.content.Context
import androidx.room.Room

actual fun createInMemoryDatabase(): QMusicDatabase {
    throw UnsupportedOperationException("InMemoryDatabase on Android requires a Context. Use createAndroidInMemoryDatabase(context).")
}

fun createAndroidInMemoryDatabase(context: Context): QMusicDatabase {
    return getRoomDatabase(Room.inMemoryDatabaseBuilder(context, QMusicDatabase::class.java))
}

fun createAndroidDatabase(context: Context, name: String = "qmusic.db"): QMusicDatabase {
    val dbFile = context.getDatabasePath(name)
    return getRoomDatabase(Room.databaseBuilder(context, QMusicDatabase::class.java, dbFile.absolutePath))
}
