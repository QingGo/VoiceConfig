package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.core.model.AppAlias.AliasSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppAliasMapperTest {

    @Test
    fun `app alias round trip`() {
        val alias = AppAlias(
            id = 3,
            alias = "内部工具",
            packageName = "com.example.internal",
            activityName = "com.example.internal.MainActivity",
            source = AliasSource.USER,
        )

        val entity = AppAliasMapper.toEntity(alias)
        assertEquals(3L, entity.id)
        assertEquals("内部工具", entity.alias)
        assertEquals(AliasSource.USER, entity.source)

        val restored = AppAliasMapper.toDomain(entity)
        assertEquals(alias, restored)
    }
}
