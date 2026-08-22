package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voiceconfig.data.local.entity.AgentStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentStepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(step: AgentStepEntity): Long

    @Query("SELECT * FROM agent_steps WHERE sessionId = :sessionId ORDER BY createdAtEpochMillis ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AgentStepEntity>>

    @Query("SELECT * FROM agent_steps WHERE sessionId = :sessionId ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AgentStepEntity>

    @Query("DELETE FROM agent_steps WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
