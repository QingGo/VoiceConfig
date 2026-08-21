package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSessionDao {
    @Query("SELECT * FROM agent_sessions ORDER BY updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): AgentSessionEntity?

    @Insert
    suspend fun insert(session: AgentSessionEntity): Long

    @Query("UPDATE agent_sessions SET title = :title, updatedAtEpochMillis = :updatedAt, messageCount = :messageCount WHERE id = :sessionId")
    suspend fun updateSummary(sessionId: Long, title: String, updatedAt: Long, messageCount: Int)

    @Query("UPDATE agent_sessions SET title = :title WHERE id = :sessionId")
    suspend fun rename(sessionId: Long, title: String)

    @Query("DELETE FROM agent_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)
}
