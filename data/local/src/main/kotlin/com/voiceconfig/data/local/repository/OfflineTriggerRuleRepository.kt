package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.data.local.dao.TriggerRuleDao
import com.voiceconfig.data.local.mapper.TriggerRuleMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineTriggerRuleRepository(
    private val dao: TriggerRuleDao,
) : TriggerRuleRepository {

    override fun observeEnabled(): Flow<List<TriggerRule>> =
        dao.observeEnabled().map { list -> list.map(TriggerRuleMapper::toDomain) }

    override fun observeAll(): Flow<List<TriggerRule>> =
        dao.observeAll().map { list -> list.map(TriggerRuleMapper::toDomain) }

    override suspend fun getEnabled(): List<TriggerRule> =
        dao.getEnabled().map(TriggerRuleMapper::toDomain)

    override suspend fun getById(ruleId: Long): TriggerRule? =
        dao.getById(ruleId)?.let(TriggerRuleMapper::toDomain)

    override suspend fun save(rule: TriggerRule): Long =
        if (rule.id == 0L) dao.insert(TriggerRuleMapper.toEntity(rule))
        else {
            dao.update(TriggerRuleMapper.toEntity(rule))
            rule.id
        }

    override suspend fun setEnabled(ruleId: Long, enabled: Boolean) =
        dao.setEnabled(ruleId, enabled)

    override suspend fun delete(ruleId: Long) =
        dao.deleteById(ruleId)
}
