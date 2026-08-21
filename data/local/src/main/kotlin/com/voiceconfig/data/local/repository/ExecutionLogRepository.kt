package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.ExecutionLog
import kotlinx.coroutines.flow.Flow

interface ExecutionLogRepository {
    fun observeByTask(taskId: Long): Flow<List<ExecutionLog>>
    fun observeByAgentSession(sessionId: Long): Flow<List<ExecutionLog>>
    fun observeRecent(limit: Int): Flow<List<ExecutionLog>>
    suspend fun add(log: ExecutionLog): Long
    suspend fun deleteByTask(taskId: Long)
}
