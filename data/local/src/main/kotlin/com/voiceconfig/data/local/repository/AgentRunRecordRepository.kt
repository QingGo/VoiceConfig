package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.entity.AgentRunRecordEntity
import kotlinx.coroutines.flow.Flow

interface AgentRunRecordRepository {
    fun observeRecent(limit: Int): Flow<List<AgentRunRecordEntity>>
    suspend fun save(record: AgentRunRecordEntity)
    suspend fun getRecent(limit: Int): List<AgentRunRecordEntity>
    suspend fun getByRunId(runId: String): AgentRunRecordEntity?
    suspend fun deleteAll()
}
