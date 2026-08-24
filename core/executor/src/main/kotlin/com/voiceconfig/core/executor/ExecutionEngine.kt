package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus

/**
 * 执行引擎：按请求模式执行，失败时按固定降级链降级。
 *
 * 降级结果会保留原始失败原因（errorCode + message），便于排查
 * “为什么这次没有走首选通道”。
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
        val requestedChannel = channels.firstOrNull { it.supportedMode == request.requestedMode }
        val requested = requestedChannel?.takeIf { it.canExecute(request) }

        var primaryFailure: ExecutionResult? = null
        if (requested != null) {
            val result = requested.execute(request)
            if (result.status == ExecutionStatus.SUCCESS) return result
            primaryFailure = result
        } else {
            primaryFailure = ExecutionResult.failure(
                mode = request.requestedMode,
                errorCode = "${request.requestedMode}_UNAVAILABLE",
                message = requestedChannel?.unavailableReason(request)
                    ?: "${request.requestedMode} 通道当前不可用",
            )
        }

        for (mode in fallbackOrder) {
            if (mode == request.requestedMode) continue
            val channel = channels.firstOrNull { it.supportedMode == mode && it.canExecute(request) } ?: continue
            val result = channel.execute(request)
            if (result.status == ExecutionStatus.SUCCESS) {
                val reason = primaryFailure?.message?.let { "：$it" } ?: ""
                return result.copy(
                    status = ExecutionStatus.FALLBACK,
                    errorCode = primaryFailure?.errorCode,
                    message = "已从 ${request.requestedMode} 降级到 $mode$reason",
                )
            }
        }

        return ExecutionResult.failure(
            mode = request.requestedMode,
            errorCode = primaryFailure?.errorCode ?: "ALL_CHANNELS_FAILED",
            message = primaryFailure?.message?.let { "所有执行通道均失败：$it" } ?: "所有执行通道均失败",
        )
    }
}
