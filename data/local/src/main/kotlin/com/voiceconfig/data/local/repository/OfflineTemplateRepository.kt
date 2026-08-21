package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.Template
import com.voiceconfig.data.local.dao.TemplateDao
import com.voiceconfig.data.local.mapper.TemplateMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineTemplateRepository(
    private val templateDao: TemplateDao,
) : TemplateRepository {
    override fun observeTemplates(): Flow<List<Template>> =
        templateDao.observeAll().map { entities -> entities.map(TemplateMapper::toDomain) }

    override suspend fun add(template: Template): Long =
        templateDao.insert(TemplateMapper.toEntity(template))

    override suspend fun incrementUsage(templateId: Long) {
        templateDao.incrementUsage(templateId)
    }

    override suspend fun delete(templateId: Long) {
        templateDao.deleteById(templateId)
    }
}
