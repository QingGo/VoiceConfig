package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResultOk: Boolean? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val reasoningContent: String? = null,
    val createdAtEpochMillis: Long,
)
