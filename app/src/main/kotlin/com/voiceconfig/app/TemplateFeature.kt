package com.voiceconfig.app

import com.voiceconfig.core.model.Template
import com.voiceconfig.data.local.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateFeature @Inject constructor(
    private val repository: TemplateRepository,
) {
    val templates: Flow<List<Template>> = repository.observeTemplates()

    suspend fun add(template: Template) {
        repository.add(template)
    }

    suspend fun delete(id: Long) {
        repository.delete(id)
    }

    suspend fun incrementUsage(id: Long) {
        repository.incrementUsage(id)
    }
}
