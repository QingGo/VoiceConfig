package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 通用 FlowScript 执行工具。
 *
 * 让 LLM 通过 scriptId 运行任何“已审核且启用”的 FlowScript，
 * 不再局限于瑞幸专用工具。执行引擎统一停在终端确认页。
 */
@Singleton
class FlowScriptTool @Inject constructor(
    private val flowScriptStore: FlowScriptStore,
    private val flowExecutor: UiFlowExecutor,
) : AgentTool {

    override val name: String = "run_flow_script"
    override val description: String =
        "运行一个已审核并启用的 FlowScript 流程，自动完成固定 UI 操作并停在终端确认页；参数：{\"scriptId\":\"流程 ID\", \"params\":\"可选 JSON 对象，覆盖脚本参数\"}"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "流程技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = false,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val scriptId = args["scriptId"]?.toString()?.trim().orEmpty()
        if (scriptId.isBlank()) {
            return ToolResult.failure("缺少 scriptId")
        }
        val script = flowScriptStore.get(scriptId)
            ?: return ToolResult.failure("未找到 FlowScript：$scriptId")
        if (script.status != FlowScriptStatus.APPROVED || !script.enabled) {
            return ToolResult.failure("FlowScript 未审核或未启用：$scriptId")
        }
        val overrides = parseParams(args["params"]?.toString())
        val result = flowExecutor.execute(
            script = script,
            goal = script.name,
            waitReason = "已到达 ${script.name} 的终端确认页，等待用户确认",
            overrides = overrides,
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
                    "scriptId" to script.id,
                ),
            )
        } else {
            ToolResult.failure(result.message, mapOf("summary" to result.summary))
        }
    }

    private fun parseParams(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.keys().forEach { key -> put(key, obj.opt(key)) }
        }
    }
}
