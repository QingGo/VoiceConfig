package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode

interface ExecutionChannel {
    val supportedMode: ExecutionMode
    fun canExecute(request: ExecutionRequest): Boolean

    /**
     * 返回首选通道不可用时的可读原因，用于把降级原因写进执行日志。
     * 默认返回 null，由 ExecutionEngine 使用通用文案。
     */
    fun unavailableReason(request: ExecutionRequest): String? = null

    fun execute(request: ExecutionRequest): ExecutionResult
}
