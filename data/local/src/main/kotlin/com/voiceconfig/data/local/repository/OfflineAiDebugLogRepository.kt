package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.AiDebugLogDao
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import kotlinx.coroutines.flow.Flow

class OfflineAiDebugLogRepository(
    private val aiDebugLogDao: AiDebugLogDao,
) : AiDebugLogRepository {
    override fun observeRecent(limit: Int): Flow<List<AiDebugLogEntity>> =
        aiDebugLogDao.observeRecent(limit)

    override suspend fun add(log: AiDebugLogEntity): Long =
        aiDebugLogDao.insert(log)

    override suspend fun recent(limit: Int): List<AiDebugLogEntity> =
        aiDebugLogDao.recent(limit)

    override suspend fun trim(keep: Int) {
        aiDebugLogDao.trim(keep)
    }
}
