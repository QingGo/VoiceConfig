package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ScheduleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleBasedNlpParserTest {

    private val parser = RuleBasedNlpParser()

    @Test
    fun `parse daily open wecom`() {
        val draft = parser.parse("每天早上8点25分打开企业微信")

        assertNotNull(draft)
        assertEquals(ActionType.OPEN_APP, draft!!.actionType)
        assertEquals("com.tencent.wework", draft.targetPackage)
        assertEquals(ScheduleSpec.ScheduleType.DAILY, draft.schedule?.type)
        assertEquals(8, draft.schedule?.time?.hour)
        assertEquals(25, draft.schedule?.time?.minute)
    }

    @Test
    fun `parse workdays open dingtalk`() {
        val draft = parser.parse("工作日9点打开钉钉")

        assertNotNull(draft)
        assertEquals("com.alibaba.android.rimet", draft!!.targetPackage)
        assertEquals(5, draft.schedule?.daysOfWeek?.size)
    }

    @Test
    fun `parse once tomorrow`() {
        val draft = parser.parse("明天下午3点提醒我开会")

        assertNotNull(draft)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, draft!!.schedule?.type)
        assertEquals(ActionType.NOTIFY, draft.actionType)
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(parser.parse("随便说点啥"))
    }

    @Test
    fun `parse tonight open wecom`() {
        val draft = parser.parse("今晚8点打开企业微信")

        assertNotNull(draft)
        assertEquals(ActionType.OPEN_APP, draft!!.actionType)
        assertEquals("com.tencent.wework", draft.targetPackage)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, draft.schedule?.type)
        assertEquals(20, draft.schedule?.time?.hour)
    }

    @Test
    fun `parse every two days remind drink water`() {
        val draft = parser.parse("每两天提醒我喝水")

        assertNotNull(draft)
        assertEquals(ActionType.NOTIFY, draft!!.actionType)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, draft.schedule?.type)
        assertEquals(2L * 24 * 60, draft.schedule?.intervalMinutes)
    }

    @Test
    fun `parse chinese numeral time open wecom`() {
        val draft = parser.parse("八点五十分打开企业微信")

        assertNotNull(draft)
        assertEquals(ActionType.OPEN_APP, draft!!.actionType)
        assertEquals("com.tencent.wework", draft.targetPackage)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, draft.schedule?.type)
        assertEquals(8, draft.schedule?.time?.hour)
        assertEquals(50, draft.schedule?.time?.minute)
    }
}
