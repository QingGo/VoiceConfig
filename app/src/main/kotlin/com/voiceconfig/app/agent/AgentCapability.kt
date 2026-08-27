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

enum class PreflightSeverity {
    BLOCKER,
    WARNING,
}

data class AgentPreflightIssue(
    val severity: PreflightSeverity,
    val code: String,
    val message: String,
)

/**
 * 能力预检结果。
 *
 * 在执行 Agent 前由确定性代码给出，不依赖 LLM：
 * - blockers：缺少必要条件，直接不启动；
 * - warnings：可能影响效果/可靠性，但可以继续执行。
 */
data class AgentPreflightResult(
    val blockers: List<AgentPreflightIssue> = emptyList(),
    val warnings: List<AgentPreflightIssue> = emptyList(),
) {
    val ready: Boolean
        get() = blockers.isEmpty()

    fun summary(): String = buildString {
        if (blockers.isNotEmpty()) {
            appendLine("无法执行：")
            blockers.forEach { appendLine("- ${it.message}") }
        }
        if (warnings.isNotEmpty()) {
            appendLine("提示：")
            warnings.forEach { appendLine("- ${it.message}") }
        }
    }.trimEnd()
}

/**
 * 确定性能力预检。
 *
 * 只做“系统有没有能力”的判断，不替模型做语义规划。
 */
object AgentPreflight {

    private val UI_KEYWORDS = listOf(
        "打开", "点击", "点一下", "输入", "滑动", "翻页", "返回", "发送", "回复",
        "微信", "企业微信", "瑞幸", "咖啡", "购物", "下单", "加购", "购买", "支付",
        "屏幕", "界面", "页面", "App", "应用", "聊天", "消息", "相册", "电话",
        "短信", "日历", "天气", "地图", "外卖", "点餐", "扫码", "截图",
    )

    private val SCHEDULE_KEYWORDS = listOf("定时", "每天", "每周", "明天", "提醒", "闹钟", "间隔")

    fun evaluate(
        snapshot: AgentCapabilitySnapshot,
        userText: String,
    ): AgentPreflightResult {
        val blockers = mutableListOf<AgentPreflightIssue>()
        val warnings = mutableListOf<AgentPreflightIssue>()
        val text = userText.orEmpty()

        if (!snapshot.cloudLlmAvailable) {
            blockers += AgentPreflightIssue(
                PreflightSeverity.BLOCKER,
                "NO_CLOUD_LLM",
                "未配置 DeepSeek API Key，无法运行 Agent",
            )
        }

        if (!snapshot.networkAvailable) {
            blockers += AgentPreflightIssue(
                PreflightSeverity.BLOCKER,
                "NO_NETWORK",
                "当前无网络，无法调用云端模型或远程服务",
            )
        }

        val needsUi = UI_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (needsUi && !snapshot.canOpenAppReliably) {
            blockers += AgentPreflightIssue(
                PreflightSeverity.BLOCKER,
                "NO_UI_CONTROL",
                "缺少 Shizuku 或无障碍服务，无法可靠打开/操作手机应用",
            )
        }

        if (needsUi && !snapshot.canSenseScreen) {
            blockers += AgentPreflightIssue(
                PreflightSeverity.BLOCKER,
                "NO_SCREEN_SENSING",
                "缺少 Shizuku 或无障碍服务，无法读取屏幕和验证执行结果",
            )
        }

        if (SCHEDULE_KEYWORDS.any { text.contains(it, ignoreCase = true) } &&
            !snapshot.exactAlarmAvailable
        ) {
            warnings += AgentPreflightIssue(
                PreflightSeverity.WARNING,
                "NO_EXACT_ALARM",
                "系统未授予精确闹钟权限，定时任务可能延迟执行",
            )
        }

        if (!snapshot.batteryOptimizationIgnored) {
            warnings += AgentPreflightIssue(
                PreflightSeverity.WARNING,
                "BATTERY_OPTIMIZATION",
                "未忽略电池优化，长时间 Agent 任务可能被系统中断",
            )
        }

        return AgentPreflightResult(blockers, warnings)
    }
}
