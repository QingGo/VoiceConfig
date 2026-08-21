package com.voiceconfig.core.model

/**
 * 触发器规则：condition → action → verify。
 */
data class TriggerRule(
    val id: Long = 0,
    val name: String,
    val condition: TriggerCondition,
    val action: TriggerAction,
    val verify: VerifySpec = VerifySpec(VerifySpec.VerifyType.NONE),
    val fallback: FallbackSpec = FallbackSpec(),
    val enabled: Boolean = true,
    val nextRunAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class FallbackSpec(
    val notifyOnFailure: Boolean = true,
    val retryCount: Int = 0,
    val askUser: Boolean = false,
)
