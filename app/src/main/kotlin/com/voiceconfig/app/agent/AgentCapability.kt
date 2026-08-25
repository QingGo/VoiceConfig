package com.voiceconfig.app.agent

import android.app.AlarmManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.service.AgentAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一能力快照。
 *
 * 目的：在执行任何 Agent 任务前，先确定性检测“我们有哪些执行通道”，
 * 并把结果用于日志、降级策略和用户提示。模型只负责语义，不负责判断系统能力。
 */
data class AgentCapabilitySnapshot(
    val shizukuAvailable: Boolean,
    val accessibilityEnabled: Boolean,
    val cloudLlmAvailable: Boolean,
    val exactAlarmAvailable: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val networkAvailable: Boolean,
    val mediaProjectionAvailable: Boolean = false,
) {
    val canOpenAppReliably: Boolean
        get() = shizukuAvailable || accessibilityEnabled

    val canSenseScreen: Boolean
        get() = shizukuAvailable || accessibilityEnabled

    val canRunAgent: Boolean
        get() = cloudLlmAvailable

    fun summary(): String = buildString {
        append("Shizuku=").append(if (shizukuAvailable) "Y" else "N")
        append(", Accessibility=").append(if (accessibilityEnabled) "Y" else "N")
        append(", CloudLLM=").append(if (cloudLlmAvailable) "Y" else "N")
        append(", ExactAlarm=").append(if (exactAlarmAvailable) "Y" else "N")
        append(", Network=").append(if (networkAvailable) "Y" else "N")
        append(", MediaProjection=").append(if (mediaProjectionAvailable) "Y" else "N")
    }
}

@Singleton
class AgentCapabilityInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuCommandRunner,
    private val apiKeyStore: ApiKeyStore,
) {
    fun snapshot(): AgentCapabilitySnapshot {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (alarmManager?.canScheduleExactAlarms() == true)
        val battery = runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        val networkAvailable = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
        return AgentCapabilitySnapshot(
            shizukuAvailable = shizuku.isAvailable(),
            accessibilityEnabled = AgentAccessibilityService.instance != null,
            cloudLlmAvailable = apiKeyStore.deepSeekApiKey.isNotBlank(),
            exactAlarmAvailable = exactAlarm,
            batteryOptimizationIgnored = battery,
            networkAvailable = networkAvailable,
            mediaProjectionAvailable = false,
        )
    }
}
