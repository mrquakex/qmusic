package com.qmusic.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qmusic.database.entity.HistoryEntity
import com.qmusic.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY playedAt DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN (
            SELECT songId, MAX(playedAt) as latestPlayedAt
            FROM history
            GROUP BY songId
        ) h ON songs.id = h.songId
        ORDER BY h.latestPlayedAt DESC
    """)
    fun getRecentHistorySongs(): Flow<List<SongEntity>>

    @Query("DELETE FROM history")
    suspend fun clearHistory(): Int
}
