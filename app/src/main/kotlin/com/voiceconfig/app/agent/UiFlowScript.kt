package com.voiceconfig.app.agent

/**
 * 通用 UI 流程脚本模型。
 *
 * 目标：把“某 App 的固定操作路径”从 Kotlin 状态机中抽成数据，
 * 让新 App/新业务只需要新增一份 FlowScript，而不需要新增一个专用工具类。
 */
data class FlowScript(
    val id: String,
    val name: String,
    val openPackage: String? = null,
    val steps: List<FlowStep>,
    val terminalMarkers: List<String> = listOf("免密支付", "确认订单"),
    val maxIterations: Int = 20,
)

data class FlowStep(
    val id: String,
    val name: String = "",
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
