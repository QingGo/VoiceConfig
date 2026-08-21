package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import com.voiceconfig.data.local.entity.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

object TaskMapper {

    fun toEntity(task: Task): TaskEntity = TaskEntity(
        id = task.id,
        rawText = task.rawText,
        title = task.title,
        enabled = task.enabled,
        scheduleType = task.schedule.type,
        time = task.schedule.time?.toString(),
        date = task.schedule.date?.toString(),
        daysOfWeek = task.schedule.daysOfWeek.joinToString(",") { it.name },
        intervalMinutes = task.schedule.intervalMinutes,
        actionType = task.actionType,
        targetPackage = task.targetPackage,
        targetActivity = task.targetActivity,
        deepLink = task.deepLink,
        executionMode = task.executionMode,
        nextRunAtEpochMillis = task.nextRunAtEpochMillis,
        createdAtEpochMillis = task.createdAtEpochMillis,
        updatedAtEpochMillis = task.updatedAtEpochMillis,
    )

    fun toDomain(entity: TaskEntity): Task = Task(
        id = entity.id,
        rawText = entity.rawText,
        title = entity.title,
        enabled = entity.enabled,
        schedule = ScheduleSpec(
            type = entity.scheduleType,
            time = entity.time?.let(LocalTime::parse),
            date = entity.date?.let(LocalDate::parse),
            daysOfWeek = entity.daysOfWeek
                ?.split(",")
                ?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
                ?.toSet()
                .orEmpty(),
            intervalMinutes = entity.intervalMinutes,
        ),
        actionType = entity.actionType,
        targetPackage = entity.targetPackage,
        targetActivity = entity.targetActivity,
        deepLink = entity.deepLink,
        executionMode = entity.executionMode,
        nextRunAtEpochMillis = entity.nextRunAtEpochMillis,
        createdAtEpochMillis = entity.createdAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
    )
}
