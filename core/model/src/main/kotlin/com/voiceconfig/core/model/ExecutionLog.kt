package com.voiceconfig.core.model

data class ExecutionLog(
    val id: Long = 0,
    val taskId: Long,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val status: ExecutionStatus,
    val executionMode: ExecutionMode? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val agentSessionId: Long? = null,
)
