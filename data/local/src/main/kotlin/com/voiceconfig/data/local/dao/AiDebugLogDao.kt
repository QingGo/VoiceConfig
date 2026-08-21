package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiDebugLogDao {
    @Insert
    suspend fun insert(log: AiDebugLogEntity): Long

    @Query("SELECT * FROM ai_debug_logs ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AiDebugLogEntity>>

    @Query("SELECT * FROM ai_debug_logs ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AiDebugLogEntity>

    @Query("DELETE FROM ai_debug_logs WHERE id NOT IN (SELECT id FROM ai_debug_logs ORDER BY createdAtEpochMillis DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
