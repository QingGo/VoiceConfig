package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_debug_logs")
data class AiDebugLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMillis: Long,
    val input: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: String,
    val rawResponse: String?,
    val parseError: String?,
)
