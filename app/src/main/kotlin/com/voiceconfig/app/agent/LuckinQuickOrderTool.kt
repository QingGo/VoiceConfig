package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 瑞幸快速点单宏（薄封装）。
 *
 * 实际执行逻辑在通用 [UiFlowExecutor] + [BuiltinFlowScripts] 中，
 * 这里只负责参数化和结果转换，便于后续扩展到其他 App 流程。
 */
@Singleton
class LuckinQuickOrderTool @Inject constructor(
    private val flowExecutor: UiFlowExecutor,
) : AgentTool {

    override val name: String = "luckin_quick_order"
    override val description: String =
        "快速在瑞幸点常规冰美式并停在免密支付页；自动打开瑞幸、跳过更新/弹窗、选门店与标准美式、点立即购买、关闭换购浮层，绝不点击支付。"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "消费技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = false,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val drink = args["drink"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "标准美式"
        val temperature = args["temperature"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "冰"
        val result = flowExecutor.execute(
            script = BuiltinFlowScripts.luckinStandardIce,
            goal = "瑞幸快速点单",
            waitReason = "已到达瑞幸免密支付确认页（$drink / $temperature），等待用户确认。点击支付前不会自动提交。",
            overrides = mapOf("drink" to drink, "temperature" to temperature),
        )
        return if (result.ok) {
            ToolResult.success(
                result.message,
                mapOf(
                    "verified" to true,
                    "terminalStop" to result.terminalStop,
                    "foregroundPackage" to result.foregroundPackage,
                    "summary" to result.summary,
                    "reason" to result.reason,
                ),
            )
        } else {
            ToolResult.failure(result.message, mapOf("summary" to result.summary))
        }
    }
}
