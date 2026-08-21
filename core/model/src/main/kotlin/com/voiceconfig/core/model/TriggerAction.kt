package com.voiceconfig.core.model

/**
 * 触发器要执行的动作。
 */
data class TriggerAction(
    val type: ActionType,
    val targetPackage: String? = null,
    val targetActivity: String? = null,
    val deepLink: String? = null,
    val tapTarget: String? = null,        // text / resource-id / bounds
    val inputText: String? = null,
    val shellCommand: String? = null,
    val settingKey: String? = null,
    val settingValue: String? = null,
)
