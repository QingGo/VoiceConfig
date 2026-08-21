package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val messageCount: Int = 0,
)
