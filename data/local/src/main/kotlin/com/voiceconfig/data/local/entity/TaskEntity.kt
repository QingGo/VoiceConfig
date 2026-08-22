package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawText: String,
    val title: String,
    val enabled: Boolean = true,
    val scheduleType: ScheduleSpec.ScheduleType,
    val time: String?,
    val date: String?,
    val daysOfWeek: String?,
    val intervalMinutes: Long?,
    val actionType: ActionType,
    val targetPackage: String?,
    val targetActivity: String?,
    val deepLink: String?,
    val agentPrompt: String? = null,
    val executionMode: ExecutionMode,
    val nextRunAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
