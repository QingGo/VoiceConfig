package com.voiceconfig.core.model

/**
 * 已保存的自动化任务。
 */
data class Task(
    val id: Long = 0,
    val rawText: String,
    val title: String,
    val enabled: Boolean = true,
    val schedule: ScheduleSpec,
    val actionType: ActionType,
    val targetPackage: String? = null,
    val targetActivity: String? = null,
    val deepLink: String? = null,
    val agentPrompt: String? = null,
    val executionMode: ExecutionMode,
    val nextRunAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
