package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ScheduleSpec
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimeExpressionParserTest {

    private val parser = TimeExpressionParser()

    @Test
    fun `parse daily`() {
        val spec = parser.parse("每天早上8点25分")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.DAILY, spec!!.type)
        assertEquals(LocalTime.of(8, 25), spec.time)
    }

    @Test
    fun `parse workdays`() {
        val spec = parser.parse("工作日9点")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.WEEKLY, spec!!.type)
        assertEquals(5, spec.daysOfWeek.size)
        assertEquals(LocalTime.of(9, 0), spec.time)
    }

    @Test
    fun `parse tomorrow afternoon`() {
        val spec = parser.parse("明天下午3点")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, spec!!.type)
        assertEquals(LocalTime.of(15, 0), spec.time)
    }

    @Test
    fun `parse interval`() {
        val spec = parser.parse("每2小时")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, spec!!.type)
        assertEquals(120L, spec.intervalMinutes)
    }

    @Test
    fun `invalid returns null`() {
        assertNull(parser.parse("随便说说"))
    }

    @Test
    fun `parse every two hours`() {
        val spec = parser.parse("每隔2小时")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, spec!!.type)
        assertEquals(120L, spec.intervalMinutes)
    }

    @Test
    fun `parse every hour`() {
        val spec = parser.parse("每小时")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, spec!!.type)
        assertEquals(60L, spec.intervalMinutes)
    }

    @Test
    fun `parse half hour`() {
        val spec = parser.parse("每半小时")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, spec!!.type)
        assertEquals(30L, spec.intervalMinutes)
    }

    @Test
    fun `parse every two days`() {
        val spec = parser.parse("每两天")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, spec!!.type)
        assertEquals(2L * 24 * 60, spec.intervalMinutes)
    }

    @Test
    fun `parse tonight once`() {
        val spec = parser.parse("今晚8点")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, spec!!.type)
        assertEquals(LocalTime.of(20, 0), spec.time)
    }

    @Test
    fun `parse tomorrow morning once`() {
        val spec = parser.parse("明早7点")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, spec!!.type)
        assertEquals(LocalTime.of(7, 0), spec.time)
    }

    @Test
    fun `parse three days later once`() {
        val spec = parser.parse("大后天9点")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, spec!!.type)
        assertEquals(LocalTime.of(9, 0), spec.time)
    }

    @Test
    fun `parse chinese numeral time`() {
        val spec = parser.parse("八点五十分")
        assertNotNull(spec)
        assertEquals(LocalTime.of(8, 50), spec!!.time)
    }

    @Test
    fun `parse chinese numeral half hour`() {
        val spec = parser.parse("八点半")
        assertNotNull(spec)
        assertEquals(LocalTime.of(8, 30), spec!!.time)
    }

    @Test
    fun `parse chinese numeral with zero minute`() {
        val spec = parser.parse("九点零五分")
        assertNotNull(spec)
        assertEquals(LocalTime.of(9, 5), spec!!.time)
    }

    @Test
    fun `parse bare time as today once`() {
        val spec = parser.parse("八点五十分")
        assertNotNull(spec)
        assertEquals(ScheduleSpec.ScheduleType.ONCE, spec!!.type)
        assertEquals(LocalTime.of(8, 50), spec.time)
    }
}
