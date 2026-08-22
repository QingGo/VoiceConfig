package com.voiceconfig.app.agent

/**
 * 一个可被 LLM 调用的自动化工具。
 *
 * 设计目标：工具数量少（<10）、表达力强、参数简单，让没有多模态能力的
 * 文本 LLM 也能通过“读 XML 布局 + 绝对坐标点击”完成手机自动化。
 */
interface AgentTool {
    /** 工具名，LLM 在 JSON 中使用这个名字。 */
    val name: String

    /** 人类可读描述，用于拼进 LLM system prompt。 */
    val description: String

    /** 工具元数据，用于安全策略、自动验证、分类和日志。 */
    val metadata: AgentToolMetadata
        get() = AgentToolMetadataRegistry.of(name)

    /** 执行工具。参数由 LLM 提供，必须是简单 JSON 可序列化类型。 */
    suspend fun execute(args: Map<String, Any?>): ToolResult
}

enum class ToolRisk {
    READ_ONLY,
    LOW,
    MEDIUM,
    HIGH,
    SENSITIVE,
}

data class AgentToolMetadata(
    val category: String = "通用",
    val risk: ToolRisk = ToolRisk.READ_ONLY,
    val mutatesUi: Boolean = false,
    val requiresAutoVerify: Boolean = false,
    val needsShizuku: Boolean = false,
    val sensitive: Boolean = false,
)

/**
 * 集中维护内置工具元数据。
 *
 * 后续插件工具可以通过覆写 [AgentTool.metadata] 提供自己的元数据；
 * 这里主要覆盖核心内置工具，避免每个工具类重复声明。
 */
object AgentToolMetadataRegistry {
    private val byName: Map<String, AgentToolMetadata> = mapOf(
        "open_app" to AgentToolMetadata(
            category = "应用",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = false,
        ),
        "find_app" to AgentToolMetadata(
            category = "应用",
            risk = ToolRisk.READ_ONLY,
        ),
        "run_shell" to AgentToolMetadata(
            category = "系统",
            risk = ToolRisk.SENSITIVE,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
            sensitive = true,
        ),
        "read_ui" to AgentToolMetadata(
            category = "感知",
            risk = ToolRisk.READ_ONLY,
            needsShizuku = true,
        ),
        "read_screen" to AgentToolMetadata(
            category = "感知",
            risk = ToolRisk.READ_ONLY,
            needsShizuku = true,
        ),
        "get_screen_state" to AgentToolMetadata(
            category = "感知",
            risk = ToolRisk.READ_ONLY,
            needsShizuku = true,
        ),
        "tap" to AgentToolMetadata(
            category = "交互",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
        ),
        "tap_text" to AgentToolMetadata(
            category = "交互",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
        ),
        "review_tap" to AgentToolMetadata(
            category = "感知",
            risk = ToolRisk.READ_ONLY,
            needsShizuku = true,
        ),
        "input_text" to AgentToolMetadata(
            category = "交互",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
        ),
        "swipe" to AgentToolMetadata(
            category = "交互",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
        ),
        "press_key" to AgentToolMetadata(
            category = "交互",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
            requiresAutoVerify = true,
            needsShizuku = true,
        ),
        "wait" to AgentToolMetadata(
            category = "控制",
            risk = ToolRisk.READ_ONLY,
        ),
        "notify" to AgentToolMetadata(
            category = "通知",
            risk = ToolRisk.LOW,
        ),
        "web_search" to AgentToolMetadata(
            category = "信息",
            risk = ToolRisk.READ_ONLY,
        ),
        "file_write" to AgentToolMetadata(
            category = "文件",
            risk = ToolRisk.HIGH,
            sensitive = true,
        ),
        "file_read" to AgentToolMetadata(
            category = "文件",
            risk = ToolRisk.READ_ONLY,
        ),
        "clipboard_read" to AgentToolMetadata(
            category = "剪贴板",
            risk = ToolRisk.READ_ONLY,
        ),
        "logcat_read" to AgentToolMetadata(
            category = "调试",
            risk = ToolRisk.READ_ONLY,
            needsShizuku = true,
        ),
        "open_file" to AgentToolMetadata(
            category = "文件",
            risk = ToolRisk.MEDIUM,
            mutatesUi = true,
        ),
    )

    fun of(name: String): AgentToolMetadata = byName[name] ?: AgentToolMetadata()
}
