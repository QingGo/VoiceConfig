package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.data.local.dao.ExecutionLogDao
import com.voiceconfig.data.local.mapper.ExecutionLogMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineExecutionLogRepository(
    private val executionLogDao: ExecutionLogDao,
) : ExecutionLogRepository {
    override fun observeByTask(taskId: Long): Flow<List<ExecutionLog>> =
        executionLogDao.observeByTask(taskId).map { entities -> entities.map(ExecutionLogMapper::toDomain) }

    override fun observeByAgentSession(sessionId: Long): Flow<List<ExecutionLog>> =
        executionLogDao.observeByAgentSession(sessionId).map { entities -> entities.map(ExecutionLogMapper::toDomain) }

    override fun observeRecent(limit: Int): Flow<List<ExecutionLog>> =
        executionLogDao.observeRecent(limit).map { entities -> entities.map(ExecutionLogMapper::toDomain) }

    override suspend fun add(log: ExecutionLog): Long =
        executionLogDao.insert(ExecutionLogMapper.toEntity(log))

    override suspend fun deleteByTask(taskId: Long) {
        executionLogDao.deleteByTask(taskId)
    }
}
