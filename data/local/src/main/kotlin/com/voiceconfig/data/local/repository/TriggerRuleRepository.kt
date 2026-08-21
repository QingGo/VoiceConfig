package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.TriggerRule
import kotlinx.coroutines.flow.Flow

interface TriggerRuleRepository {
    fun observeEnabled(): Flow<List<TriggerRule>>
    fun observeAll(): Flow<List<TriggerRule>>
    suspend fun getEnabled(): List<TriggerRule>
    suspend fun getById(ruleId: Long): TriggerRule?
    suspend fun save(rule: TriggerRule): Long
    suspend fun setEnabled(ruleId: Long, enabled: Boolean)
    suspend fun delete(ruleId: Long)
}
