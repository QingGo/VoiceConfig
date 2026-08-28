package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAtEpochMillis ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AgentMessageEntity>

    @Insert
    suspend fun insert(message: AgentMessageEntity): Long

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAll()
}
