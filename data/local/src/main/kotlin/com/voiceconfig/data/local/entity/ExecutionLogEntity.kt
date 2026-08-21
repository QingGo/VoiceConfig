package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val status: ExecutionStatus,
    val executionMode: ExecutionMode?,
    val errorCode: String?,
    val message: String?,
    val agentSessionId: Long? = null,
)
