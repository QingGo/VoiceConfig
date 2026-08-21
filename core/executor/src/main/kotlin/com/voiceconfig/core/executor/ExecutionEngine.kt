package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus

/**
 * 执行引擎：按请求模式执行，失败时按固定降级链降级。
 */
class ExecutionEngine(
    private val channels: List<ExecutionChannel>,
    private val fallbackOrder: List<ExecutionMode> = listOf(
        ExecutionMode.DEEP_LINK,
        ExecutionMode.SHIZUKU,
        ExecutionMode.NOTIFICATION,
    ),
) {
    fun execute(request: ExecutionRequest): ExecutionResult {
        val requested = channels.firstOrNull { it.supportedMode == request.requestedMode && it.canExecute(request) }
        if (requested != null) {
            val result = requested.execute(request)
            if (result.status == ExecutionStatus.SUCCESS) return result
        }

        for (mode in fallbackOrder) {
            if (mode == request.requestedMode) continue
            val channel = channels.firstOrNull { it.supportedMode == mode && it.canExecute(request) } ?: continue
            val result = channel.execute(request)
            if (result.status == ExecutionStatus.SUCCESS) {
                return result.copy(
                    status = ExecutionStatus.FALLBACK,
                    message = "已从 ${request.requestedMode} 降级到 $mode",
                )
            }
        }

        return ExecutionResult.failure(
            mode = request.requestedMode,
            errorCode = "ALL_CHANNELS_FAILED",
            message = "所有执行通道均失败",
        )
    }
}
