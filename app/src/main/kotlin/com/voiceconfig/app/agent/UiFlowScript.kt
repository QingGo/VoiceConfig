package com.voiceconfig.app.agent

/**
 * FlowScript 状态。
 *
 * 内置脚本默认 APPROVED；从外部导入的脚本必须先审核为 APPROVED 才能被
 * [FlowScriptTool] / [UiFlowExecutor] 执行。
 */
enum class FlowScriptStatus {
    APPROVED,
    PENDING,
    REJECTED,
    DISABLED,
}

/**
 * 通用 UI 流程脚本模型。
 *
 * 目标：把“某 App 的固定操作路径”从 Kotlin 状态机中抽成数据，
 * 让新 App/新业务只需要新增一份 FlowScript，而不需要新增一个专用工具类。
 * 支持 JSON 导入导出、版本号、schema 校验和禁止动作安全字段。
 */
data class FlowScript(
    val id: String,
    val name: String,
    val description: String = "",
    val openPackage: String? = null,
    val steps: List<FlowStep>,
    val terminalMarkers: List<String> = listOf("免密支付", "确认订单"),
    val maxIterations: Int = 20,
    val version: Int = 1,
    val schemaVersion: Int = FlowScriptCodec.CURRENT_SCHEMA_VERSION,
    val status: FlowScriptStatus = FlowScriptStatus.APPROVED,
    val enabled: Boolean = true,
    val source: String = "builtin",
    val forbiddenActionTokens: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class FlowStep(
    val id: String,
    val name: String = "",
    val label: String = "",
    val whenContains: List<String> = emptyList(),
    val whenNotContains: List<String> = emptyList(),
    val action: FlowAction,
    val once: Boolean = true,
)

sealed class FlowAction {
    data class TapText(val candidates: List<String>) : FlowAction()
    data class TapId(val resourceIds: List<String>) : FlowAction()
    object Back : FlowAction()
    object DismissPopups : FlowAction()
    data class TapTextOrBack(val candidates: List<String>) : FlowAction()
}

data class FlowExecutionResult(
    val ok: Boolean,
    val message: String,
    val terminalStop: Boolean = false,
    val summary: String = "",
    val foregroundPackage: String? = null,
    val reason: String = "",
)
