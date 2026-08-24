package com.voiceconfig.app.agent

/**
 * 工具级验证矩阵。
 *
 * 目的：让“每个工具应该验证什么”成为确定性配置，而不是散落在各工具实现里。
 * 模型可以规划，但验证要求由本矩阵决定。
 */
enum class VerificationRequirement {
    /** 需要有前台包名/目标页面证据。 */
    FOREGROUND,

    /** 需要确认任务已持久化并调度。 */
    TASK_CREATED,

    /** 需要 UI 证据（读屏/弹窗关闭/页面变化）。 */
    UI_EVIDENCE,

    /** 当前无需验证或由上层 StopVerifier 统一判断。 */
    NONE,
}

data class ToolVerificationSpec(
    val requirement: VerificationRequirement,
    val evidenceField: String? = null,
    val description: String = "",
)

/**
 * 主路径工具集的验证配置。
 */
object AgentVerificationMatrix {

    private val specs: Map<String, ToolVerificationSpec> = mapOf(
        "open_app" to ToolVerificationSpec(
            VerificationRequirement.FOREGROUND,
            evidenceField = "verified",
            description = "打开后必须确认目标前台包名",
        ),
        "open_search" to ToolVerificationSpec(
            VerificationRequirement.UI_EVIDENCE,
            evidenceField = "url",
            description = "需要确认搜索页已打开或至少命令成功",
        ),
        "open_deeplink" to ToolVerificationSpec(
            VerificationRequirement.UI_EVIDENCE,
            evidenceField = "url",
            description = "Deep Link 打开后确认目标页面",
        ),
        "create_reminder" to ToolVerificationSpec(
            VerificationRequirement.TASK_CREATED,
            evidenceField = "verified",
            description = "提醒必须已保存并注册闹钟",
        ),
        "create_scheduled_task" to ToolVerificationSpec(
            VerificationRequirement.TASK_CREATED,
            evidenceField = "verified",
            description = "定时任务必须已保存并注册闹钟",
        ),
        "dismiss_popups" to ToolVerificationSpec(
            VerificationRequirement.UI_EVIDENCE,
            evidenceField = "actions",
            description = "关闭后需要返回动作列表或未检测到弹窗",
        ),
        "create_calendar_event" to ToolVerificationSpec(
            VerificationRequirement.UI_EVIDENCE,
            evidenceField = "startTimeMs",
            description = "打开日历预填页或成功插入事件",
        ),
        "task_plan" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "计划状态由 StopVerifier 统一判断",
        ),
        "wait_user" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "等待用户确认，由运行状态表示",
        ),
        "read_ui" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "感知类工具，本身即证据来源",
        ),
        "get_screen_state" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "感知类工具，本身即证据来源",
        ),
        "read_screen" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "感知类工具，本身即证据来源",
        ),
        "find_app" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "返回候选即可",
        ),
        "notify" to ToolVerificationSpec(
            VerificationRequirement.NONE,
            description = "通知发送成功即可",
        ),
    )

    fun specFor(toolName: String): ToolVerificationSpec =
        specs[toolName] ?: ToolVerificationSpec(
            requirement = VerificationRequirement.NONE,
            description = "未配置验证，按 NONE 处理",
        )
}
