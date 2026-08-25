package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voiceconfig.data.local.entity.AgentRunRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRunRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AgentRunRecordEntity)

    @Query("SELECT * FROM agent_run_records ORDER BY startedAtEpochMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AgentRunRecordEntity>>

    @Query("SELECT * FROM agent_run_records ORDER BY startedAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentRunRecordEntity>

    @Query("SELECT * FROM agent_run_records WHERE runId = :runId")
    suspend fun getByRunId(runId: String): AgentRunRecordEntity?

    @Query("DELETE FROM agent_run_records")
    suspend fun deleteAll()
}
