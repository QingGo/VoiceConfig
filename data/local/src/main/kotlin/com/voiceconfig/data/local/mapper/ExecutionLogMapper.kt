package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.data.local.entity.ExecutionLogEntity

object ExecutionLogMapper {
    fun toEntity(log: ExecutionLog): ExecutionLogEntity = ExecutionLogEntity(
        id = log.id,
        taskId = log.taskId,
        scheduledAtEpochMillis = log.scheduledAtEpochMillis,
        startedAtEpochMillis = log.startedAtEpochMillis,
        finishedAtEpochMillis = log.finishedAtEpochMillis,
        status = log.status,
        executionMode = log.executionMode,
        errorCode = log.errorCode,
        message = log.message,
        agentSessionId = log.agentSessionId,
    )

    fun toDomain(entity: ExecutionLogEntity): ExecutionLog = ExecutionLog(
        id = entity.id,
        taskId = entity.taskId,
        scheduledAtEpochMillis = entity.scheduledAtEpochMillis,
        startedAtEpochMillis = entity.startedAtEpochMillis,
        finishedAtEpochMillis = entity.finishedAtEpochMillis,
        status = entity.status,
        executionMode = entity.executionMode,
        errorCode = entity.errorCode,
        message = entity.message,
        agentSessionId = entity.agentSessionId,
    )
}
