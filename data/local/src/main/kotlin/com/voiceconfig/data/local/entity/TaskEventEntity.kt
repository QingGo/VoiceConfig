package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_events",
    indices = [Index("taskId"), Index("agentSessionId")],
)
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long? = null,
    val agentSessionId: Long? = null,
    val eventType: String,
    val rawText: String? = null,
    val summary: String,
    val createdAtEpochMillis: Long,
)
