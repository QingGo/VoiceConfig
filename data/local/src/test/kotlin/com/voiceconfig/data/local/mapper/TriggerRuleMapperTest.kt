package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.TriggerAction
import com.voiceconfig.core.model.TriggerCondition
import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.core.model.VerifySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRuleMapperTest {

    @Test
    fun `maps trigger rule to entity and back`() {
        val rule = TriggerRule(
            id = 7,
            name = "到公司打开企业微信",
            condition = TriggerCondition(
                type = TriggerCondition.TriggerType.WIFI,
                wifiSsid = "Company",
            ),
            action = TriggerAction(
                type = ActionType.OPEN_APP,
                targetPackage = "com.tencent.wework",
            ),
            verify = VerifySpec(VerifySpec.VerifyType.FOREGROUND, expectedPackage = "com.tencent.wework"),
            enabled = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val entity = TriggerRuleMapper.toEntity(rule)
        assertEquals("Company", entity.conditionWifiSsid)
        val back = TriggerRuleMapper.toDomain(entity)
        assertEquals(rule.id, back.id)
        assertEquals(rule.condition.wifiSsid, back.condition.wifiSsid)
        assertEquals(rule.action.targetPackage, back.action.targetPackage)
        assertTrue(back.enabled)
    }
}
