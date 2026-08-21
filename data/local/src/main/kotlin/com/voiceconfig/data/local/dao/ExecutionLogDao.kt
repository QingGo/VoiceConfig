package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.ExecutionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs WHERE taskId = :taskId ORDER BY scheduledAtEpochMillis DESC")
    fun observeByTask(taskId: Long): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE agentSessionId = :sessionId ORDER BY scheduledAtEpochMillis DESC")
    fun observeByAgentSession(sessionId: Long): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs ORDER BY scheduledAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExecutionLogEntity>>

    @Insert
    suspend fun insert(log: ExecutionLogEntity): Long

    @Query("DELETE FROM execution_logs WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}
