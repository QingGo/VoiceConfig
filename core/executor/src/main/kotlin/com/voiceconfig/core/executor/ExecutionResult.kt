package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus

data class ExecutionResult(
    val status: ExecutionStatus,
    val usedMode: ExecutionMode,
    val errorCode: String? = null,
    val message: String? = null,
) {
    companion object {
        fun success(mode: ExecutionMode): ExecutionResult =
            ExecutionResult(status = ExecutionStatus.SUCCESS, usedMode = mode)

        fun failure(mode: ExecutionMode, errorCode: String, message: String? = null): ExecutionResult =
            ExecutionResult(status = ExecutionStatus.FAILED, usedMode = mode, errorCode = errorCode, message = message)
    }
}
