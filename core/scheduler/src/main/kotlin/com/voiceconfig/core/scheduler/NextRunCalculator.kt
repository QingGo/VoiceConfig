package com.voiceconfig.core.scheduler

import com.voiceconfig.core.model.ScheduleSpec
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 根据调度规格计算下一次执行时间。
 */
class NextRunCalculator(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    fun nextRunAfter(schedule: ScheduleSpec, after: LocalDateTime = now()): LocalDateTime? {
        return when (schedule.type) {
            ScheduleSpec.ScheduleType.ONCE -> {
                val date = schedule.date ?: after.toLocalDate()
                val time = schedule.time ?: return null
                val candidate = LocalDateTime.of(date, time)
                if (candidate.isAfter(after)) candidate else null
            }

            ScheduleSpec.ScheduleType.DAILY -> {
                val time = schedule.time ?: return null
                nextWithTime(after, time, DayOfWeek.values().toSet())
            }

            ScheduleSpec.ScheduleType.WEEKLY -> {
                val time = schedule.time ?: return null
                if (schedule.daysOfWeek.isEmpty()) return null
                nextWithTime(after, time, schedule.daysOfWeek)
            }

            ScheduleSpec.ScheduleType.INTERVAL -> {
                val interval = schedule.intervalMinutes ?: return null
                if (interval <= 0) return null
                after.plusMinutes(interval)
            }
        }
    }

    private fun nextWithTime(
        after: LocalDateTime,
        time: LocalTime,
        allowedDays: Set<DayOfWeek>,
    ): LocalDateTime? {
        var candidate = LocalDateTime.of(after.toLocalDate(), time)
        for (i in 0..7) {
            if (candidate.isAfter(after) && candidate.dayOfWeek in allowedDays) {
                return candidate
            }
            candidate = candidate.plusDays(1)
        }
        return null
    }
}
