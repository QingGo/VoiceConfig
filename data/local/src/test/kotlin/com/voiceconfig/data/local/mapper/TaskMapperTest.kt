package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMapperTest {

    @Test
    fun `task round trip`() {
        val task = Task(
            id = 9,
            rawText = "工作日9点打开钉钉",
            title = "工作日9点打开钉钉",
            enabled = true,
            schedule = ScheduleSpec.weekly(
                time = LocalTime.of(9, 0),
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            ),
            actionType = ActionType.OPEN_APP,
            targetPackage = "com.alibaba.android.rimet",
            executionMode = ExecutionMode.AUTO,
            nextRunAtEpochMillis = 123456L,
            createdAtEpochMillis = 111L,
            updatedAtEpochMillis = 222L,
        )

        val entity = TaskMapper.toEntity(task)
        assertEquals(9L, entity.id)
        assertEquals(ScheduleSpec.ScheduleType.WEEKLY, entity.scheduleType)
        assertEquals("09:00", entity.time)
        assertEquals(5, entity.daysOfWeek?.split(",")?.size)

        val restored = TaskMapper.toDomain(entity)
        assertEquals(task, restored)
    }
}
