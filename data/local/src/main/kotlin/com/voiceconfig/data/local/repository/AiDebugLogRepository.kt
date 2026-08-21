package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.entity.AiDebugLogEntity
import kotlinx.coroutines.flow.Flow

interface AiDebugLogRepository {
    fun observeRecent(limit: Int): Flow<List<AiDebugLogEntity>>
    suspend fun add(log: AiDebugLogEntity): Long
    suspend fun recent(limit: Int): List<AiDebugLogEntity>
    suspend fun trim(keep: Int)
}
