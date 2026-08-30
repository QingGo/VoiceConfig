package com.voiceconfig.app.ui

import com.voiceconfig.app.agent.AgentCapabilitySnapshot
import com.voiceconfig.app.agent.AgentExecutionMode

/**
 * 面向 UI 的统一能力状态。
 *
 * 所有页面（首页 / 设置 / 引导 / 权限体检）共用这一份状态，
 * 避免各处自行读取 AgentAccessibilityService、ApiKeyStore、HA 等单例。
 */
data class CapabilityStatus(
    val cloudLlm: Boolean = false,
    val network: Boolean = false,
    val accessibility: Boolean = false,
    val shizuku: Boolean = false,
    val homeAssistant: Boolean = false,
    val remoteNodeCount: Int = 0,
    val wakeWord: Boolean = false,
    val exactAlarm: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val executionMode: AgentExecutionMode = AgentExecutionMode.NOTIFY,
) {
    val canRunAgent: Boolean get() = cloudLlm && network
    val canControlUi: Boolean get() = accessibility || shizuku
    val executionModeLabel: String
        get() = when (executionMode) {
            AgentExecutionMode.ASSIST -> "Assist / Shizuku"
            AgentExecutionMode.AMBIENT -> "Ambient / 无障碍"
            AgentExecutionMode.NOTIFY -> "Notify / 强提醒"
        }
    val readySummary: String
        get() = buildList {
            if (canRunAgent) add("AI 就绪") else add("AI 未就绪")
            if (canControlUi) add("可操作界面（$executionModeLabel）") else add("缺少界面权限")
            if (homeAssistant) add("HA 已连接") else add("HA 未配置")
        }.joinToString(" · ")
}

object CapabilityStatusMapper {
    fun from(
        snapshot: AgentCapabilitySnapshot,
        homeAssistantConfigured: Boolean,
        remoteNodeCount: Int,
        wakeWordEnabled: Boolean,
    ): CapabilityStatus = CapabilityStatus(
        cloudLlm = snapshot.cloudLlmAvailable,
        network = snapshot.networkAvailable,
        accessibility = snapshot.accessibilityEnabled,
        shizuku = snapshot.shizukuAvailable,
        homeAssistant = homeAssistantConfigured,
        remoteNodeCount = remoteNodeCount,
        wakeWord = wakeWordEnabled,
        exactAlarm = snapshot.exactAlarmAvailable,
        batteryOptimizationIgnored = snapshot.batteryOptimizationIgnored,
        executionMode = snapshot.executionMode,
    )
}
