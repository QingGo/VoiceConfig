package com.voiceconfig.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 调度规格：支持一次性、每日、每周、间隔执行。
 */
data class ScheduleSpec(
    val type: ScheduleType,
    val time: LocalTime? = null,
    val date: LocalDate? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val intervalMinutes: Long? = null,
) {
    enum class ScheduleType {
        ONCE,
        DAILY,
        WEEKLY,
        INTERVAL,
    }

    companion object {
        fun once(date: LocalDate, time: LocalTime): ScheduleSpec = ScheduleSpec(
            type = ScheduleType.ONCE,
            date = date,
            time = time,
        )

        fun daily(time: LocalTime): ScheduleSpec = ScheduleSpec(
            type = ScheduleType.DAILY,
            time = time,
        )

        fun weekly(time: LocalTime, days: Set<DayOfWeek>): ScheduleSpec = ScheduleSpec(
            type = ScheduleType.WEEKLY,
            time = time,
            daysOfWeek = days,
        )

        fun interval(minutes: Long): ScheduleSpec = ScheduleSpec(
            type = ScheduleType.INTERVAL,
            intervalMinutes = minutes,
        )
    }
}
