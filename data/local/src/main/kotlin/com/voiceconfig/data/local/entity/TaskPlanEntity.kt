package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_plans")
data class TaskPlanEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val status: String,
    val waitingForHuman: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
