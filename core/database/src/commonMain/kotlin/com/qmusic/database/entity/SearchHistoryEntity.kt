package com.qmusic.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One past search query; searching the same text again just refreshes [searchedAt]. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    val query: String,
    val searchedAt: Long,
)
