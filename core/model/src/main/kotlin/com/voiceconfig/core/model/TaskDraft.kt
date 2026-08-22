package com.voiceconfig.core.model

/**
 * NLP 解析后的任务草稿，用于用户确认。
 */
data class TaskDraft(
    val rawText: String,
    val schedule: ScheduleSpec?,
    val actionType: ActionType,
    val targetPackage: String? = null,
    val targetActivity: String? = null,
    val deepLink: String? = null,
    val agentPrompt: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.AUTO,
    val confidence: Double = 0.0,
)
