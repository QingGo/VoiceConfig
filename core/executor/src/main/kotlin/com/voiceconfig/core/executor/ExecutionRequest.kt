package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.Task

data class ExecutionRequest(
    val task: Task,
    val requestedMode: ExecutionMode,
)
