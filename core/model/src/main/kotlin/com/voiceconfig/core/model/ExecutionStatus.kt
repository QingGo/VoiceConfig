package com.voiceconfig.core.model

enum class ExecutionStatus {
    SCHEDULED,
    EXECUTING,
    SUCCESS,
    FAILED,
    SKIPPED,
    FALLBACK,
    WAITING_HUMAN,
}
