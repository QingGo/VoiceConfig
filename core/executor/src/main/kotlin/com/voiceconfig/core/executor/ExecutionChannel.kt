package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode

interface ExecutionChannel {
    val supportedMode: ExecutionMode
    fun canExecute(request: ExecutionRequest): Boolean
    fun execute(request: ExecutionRequest): ExecutionResult
}
