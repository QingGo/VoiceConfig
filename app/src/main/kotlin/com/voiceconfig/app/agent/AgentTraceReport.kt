package com.voiceconfig.app.agent

/**
 * 单次 Agent run 的自动报告。
 *
 * 从 [AgentTrace.readRun] 的 JSON 事件中提取：
 * - 耗时 / 状态 / 是否等待真人
 * - 工具序列
 * - LLM 轮次、截图/验证次数、安全拦截数量
 * - 失败/异常原因
 */
data class AgentTraceReport(
    val runId: String,
    val userText: String,
    val ok: Boolean,
    val message: String,
    val waitingForHuman: Boolean,
    val durationMs: Long,
    val rounds: Int,
    val toolCalls: Int,
    val toolSequence: List<String>,
    val screenshotCount: Int,
    val verificationCount: Int,
    val safetyBlocks: Int,
    val llmRounds: Int,
    val llmErrors: Int,
    val issues: List<String>,
)

object AgentTraceReportBuilder {

    fun build(events: List<Map<String, Any?>>): AgentTraceReport {
        val normalized = events.mapNotNull { normalize(it) as? Map<String, Any?> }
        val userText = normalized.firstOrNull { it["type"] == "run_start" }?.get("userText")?.toString() ?: ""
        var ok = true
        var message = ""
        var waitingForHuman = false
        var durationMs = 0L
        val toolSequence = mutableListOf<String>()
        var toolResultCount = 0
        var llmRounds = 0
        var screenshotCount = 0
        var verificationCount = 0
        var safetyBlocks = 0
        var llmErrors = 0
        val issues = linkedSetOf<String>()
        var rounds = 0

        normalized.forEach { event ->
            val type = event["type"]?.toString() ?: return@forEach
            when (type) {
                "run_finished" -> {
                    ok = event["ok"] == true || event["ok"]?.toString() == "true"
                    message = event["message"]?.toString().orEmpty()
                    waitingForHuman = event["waiting"] == true || event["waiting"]?.toString() == "true"
                    durationMs = (event["duration_ms"] as? Number)?.toLong() ?: durationMs
                }
                "round_timing" -> {
                    llmRounds++
                    val round = (event["round"] as? Number)?.toInt() ?: 0
                    if (round > rounds) rounds = round
                }
                "tool_call" -> {
                    val tool = event["tool"]?.toString().orEmpty()
                    if (tool.isNotBlank()) toolSequence += tool
                }
                "tool_result" -> {
                    toolResultCount++
                    if (event["ok"] != true && event["ok"]?.toString() != "true") {
                        issues += "工具失败：${event["tool"]} ${event["message"]?.toString().orEmpty().take(80)}"
                    }
                }
                "image_seen" -> screenshotCount++
                "auto_verify" -> verificationCount++
                "safety_blocked" -> {
                    safetyBlocks++
                    issues += "安全拦截：${event["tool"]} ${event["reason"]?.toString().orEmpty().take(80)}"
                }
                "tool_blocked" -> issues += "工具硬阻断：${event["tool"]}"
                "llm_error" -> {
                    llmErrors++
                    issues += "LLM 错误：${event["error"]?.toString().orEmpty().take(80)}"
                }
                "run_timeout" -> issues += "运行超时：${event["timeout_ms"]}ms"
                "plan_incomplete" -> issues += "计划未完成：${event["error"]?.toString().orEmpty().take(80)}"
                "perception_loop_detected" -> issues += "感知循环：${event["tool"]} x${event["count"]}"
                "visual_budget_exceeded" -> issues += "视觉预算超限：${event["tool"]} x${event["count"]}"
                "verification_rejected" -> issues += "验证未通过：${event["tool"]}"
            }
        }

        if (!ok && issues.isEmpty() && message.isNotBlank()) {
            issues += message.take(120)
        }
        return AgentTraceReport(
            runId = normalized.firstOrNull { it["type"] == "run_start" }?.get("runId")?.toString()
                ?: normalized.firstOrNull()?.get("runId")?.toString().orEmpty(),
            userText = userText,
            ok = ok,
            message = message,
            waitingForHuman = waitingForHuman,
            durationMs = durationMs,
            rounds = rounds,
            toolCalls = toolSequence.size,
            toolSequence = toolSequence,
            screenshotCount = screenshotCount,
            verificationCount = verificationCount,
            safetyBlocks = safetyBlocks,
            llmRounds = llmRounds,
            llmErrors = llmErrors,
            issues = issues.toList(),
        )
    }

    fun toMarkdown(report: AgentTraceReport): String = buildString {
        appendLine("# Agent Trace Report")
        appendLine()
        appendLine("- runId: `${report.runId}`")
        appendLine("- 用户目标：${report.userText.take(80)}")
        appendLine("- 状态：${if (report.ok) "成功" else "失败"}${if (report.waitingForHuman) "（等待真人）" else ""}")
        appendLine("- 耗时：${report.durationMs}ms")
        appendLine("- LLM 轮次：${report.llmRounds}（round_timing ${report.rounds}）")
        appendLine("- 工具调用：${report.toolCalls}")
        appendLine("- 工具序列：${report.toolSequence.joinToString(" → ")}")
        appendLine("- 截图：${report.screenshotCount}，自动验证：${report.verificationCount}")
        appendLine("- 安全拦截：${report.safetyBlocks}，LLM 错误：${report.llmErrors}")
        if (report.issues.isNotEmpty()) {
            appendLine("- 问题：")
            report.issues.take(10).forEach { appendLine("  - $it") }
        }
        appendLine("- 最终消息：${report.message.take(160)}")
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to normalize(it.value) }
        is List<*> -> value.map { normalize(it) }
        else -> value
    }
}
