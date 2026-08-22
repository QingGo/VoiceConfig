package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_steps",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "runId", "stepIndex"], unique = true),
    ],
)
data class AgentStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val runId: String,
    val stepIndex: Int,
    val toolName: String,
    val argsText: String,
    val status: String,
    val message: String = "",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
