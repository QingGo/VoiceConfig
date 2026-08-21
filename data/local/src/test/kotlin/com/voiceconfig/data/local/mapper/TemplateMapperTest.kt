package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.Template
import com.voiceconfig.data.local.entity.TemplateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateMapperTest {

    @Test
    fun `template round trip`() {
        val template = Template(
            id = 7,
            name = "测试模板",
            description = "描述",
            category = "自定义",
            configJson = "每天早上8点25分打开企业微信",
            usageCount = 3,
        )

        val entity = TemplateMapper.toEntity(template)
        assertEquals(7L, entity.id)
        assertEquals("测试模板", entity.name)
        assertEquals("每天早上8点25分打开企业微信", entity.configJson)

        val restored = TemplateMapper.toDomain(entity)
        assertEquals(template, restored)
    }
}
