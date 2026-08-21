package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ScheduleSpec
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleModificationParserTest {

    private val parser = ScheduleModificationParser()

    @Test
    fun `change daily time`() {
        val current = ScheduleSpec.daily(LocalTime.of(8, 0))
        val modified = parser.parse("不是8点，改成9点", current)
        assertNotNull(modified)
        assertEquals(LocalTime.of(9, 0), modified!!.time)
        assertEquals(ScheduleSpec.ScheduleType.DAILY, modified.type)
    }

    @Test
    fun `change to half hour`() {
        val current = ScheduleSpec.daily(LocalTime.of(8, 0))
        val modified = parser.parse("改成8点半", current)
        assertNotNull(modified)
        assertEquals(LocalTime.of(8, 30), modified!!.time)
    }

    @Test
    fun `null current returns null`() {
        assertNull(parser.parse("改成9点", null))
    }

    @Test
    fun `non modification returns null`() {
        val current = ScheduleSpec.daily(LocalTime.of(8, 0))
        assertNull(parser.parse("每天早上8点", current))
    }

    @Test
    fun `change once date`() {
        val current = ScheduleSpec.once(java.time.LocalDate.now(), LocalTime.of(8, 0))
        val modified = parser.parse("不是明天，改成后天9点", current)
        assertNotNull(modified)
        assertEquals(java.time.LocalDate.now().plusDays(2), modified!!.date)
        assertEquals(LocalTime.of(9, 0), modified.time)
    }
}
