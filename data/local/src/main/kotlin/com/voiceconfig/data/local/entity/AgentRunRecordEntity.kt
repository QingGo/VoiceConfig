package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_run_records",
    indices = [Index(value = ["runId"], unique = true), Index("startedAtEpochMillis")],
)
data class AgentRunRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val userText: String,
    val ok: Boolean,
    val state: String,
    val message: String,
    val toolCallsJson: String,
    val durationMs: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val waitingForHuman: Boolean,
    val verified: Boolean?,
    val capabilitySummary: String? = null,
)
