package com.qmusic.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artists: String,
    val duration: Int? = null,
    val thumbnailUrl: String? = null,
    val isFavorite: Boolean = false,
    val isDownloaded: Boolean = false,
    val downloadPath: String? = null
)
