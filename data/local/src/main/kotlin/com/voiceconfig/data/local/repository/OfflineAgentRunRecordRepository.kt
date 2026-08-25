package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.AgentRunRecordDao
import com.voiceconfig.data.local.entity.AgentRunRecordEntity
import kotlinx.coroutines.flow.Flow

class OfflineAgentRunRecordRepository(
    private val dao: AgentRunRecordDao,
) : AgentRunRecordRepository {
    override fun observeRecent(limit: Int): Flow<List<AgentRunRecordEntity>> =
        dao.observeRecent(limit)

    override suspend fun save(record: AgentRunRecordEntity) {
        dao.upsert(record)
    }

    override suspend fun getRecent(limit: Int): List<AgentRunRecordEntity> =
        dao.getRecent(limit)

    override suspend fun getByRunId(runId: String): AgentRunRecordEntity? =
        dao.getByRunId(runId)

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
