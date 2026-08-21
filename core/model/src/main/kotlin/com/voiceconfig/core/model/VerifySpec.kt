package com.voiceconfig.core.model

/**
 * 触发器执行后的验证方式。
 */
data class VerifySpec(
    val type: VerifyType,
    val expectedPackage: String? = null,
    val expectedText: String? = null,
) {
    enum class VerifyType {
        FOREGROUND,
        UI_TEXT,
        USER_CONFIRM,
        NONE,
    }
}
