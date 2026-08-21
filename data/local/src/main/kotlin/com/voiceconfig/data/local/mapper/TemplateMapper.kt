package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.Template
import com.voiceconfig.data.local.entity.TemplateEntity

object TemplateMapper {
    fun toEntity(template: Template): TemplateEntity = TemplateEntity(
        id = template.id,
        name = template.name,
        description = template.description,
        category = template.category,
        configJson = template.configJson,
        usageCount = template.usageCount,
    )

    fun toDomain(entity: TemplateEntity): Template = Template(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        category = entity.category,
        configJson = entity.configJson,
        usageCount = entity.usageCount,
    )
}
