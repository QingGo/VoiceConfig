package com.voiceconfig.core.model

data class AppAlias(
    val id: Long = 0,
    val alias: String,
    val packageName: String,
    val activityName: String? = null,
    val source: AliasSource = AliasSource.BUILTIN,
) {
    enum class AliasSource {
        BUILTIN,
        USER,
        LEARNED,
    }
}
