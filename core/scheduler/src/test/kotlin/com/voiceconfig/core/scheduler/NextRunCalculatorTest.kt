package com.voiceconfig.core.scheduler

import com.voiceconfig.core.model.ScheduleSpec
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextRunCalculatorTest {

    private val calculator = NextRunCalculator(
        zoneId = java.time.ZoneId.of("UTC"),
        now = { LocalDateTime.of(2025, 1, 1, 8, 0) },
    )

    @Test
    fun `daily next run is same day if time not passed`() {
        val spec = ScheduleSpec.daily(LocalTime.of(8, 25))
        val next = calculator.nextRunAfter(spec, LocalDateTime.of(2025, 1, 1, 8, 0))
        assertEquals(LocalDateTime.of(2025, 1, 1, 8, 25), next)
    }

    @Test
    fun `daily next run is next day if time passed`() {
        val spec = ScheduleSpec.daily(LocalTime.of(8, 25))
        val next = calculator.nextRunAfter(spec, LocalDateTime.of(2025, 1, 1, 9, 0))
        assertEquals(LocalDateTime.of(2025, 1, 2, 8, 25), next)
    }

    @Test
    fun `weekly next run skips non working days`() {
        val spec = ScheduleSpec.weekly(
            time = LocalTime.of(9, 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        )
        val next = calculator.nextRunAfter(spec, LocalDateTime.of(2025, 1, 4, 9, 0)) // Saturday
        assertEquals(LocalDateTime.of(2025, 1, 6, 9, 0), next) // Monday
    }

    @Test
    fun `interval next run adds minutes`() {
        val spec = ScheduleSpec.interval(30)
        val next = calculator.nextRunAfter(spec, LocalDateTime.of(2025, 1, 1, 8, 0))
        assertEquals(LocalDateTime.of(2025, 1, 1, 8, 30), next)
    }

    @Test
    fun `interval with zero minutes returns null`() {
        val spec = ScheduleSpec.interval(0)
        assertNull(calculator.nextRunAfter(spec, LocalDateTime.of(2025, 1, 1, 8, 0)))
    }
}
