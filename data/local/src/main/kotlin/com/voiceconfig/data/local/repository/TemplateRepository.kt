package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.Template
import kotlinx.coroutines.flow.Flow

interface TemplateRepository {
    fun observeTemplates(): Flow<List<Template>>
    suspend fun add(template: Template): Long
    suspend fun incrementUsage(templateId: Long)
    suspend fun delete(templateId: Long)
}
