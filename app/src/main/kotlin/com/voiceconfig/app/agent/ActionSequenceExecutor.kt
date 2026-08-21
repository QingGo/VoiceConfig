package com.voiceconfig.app.agent

/**
 * 顺序执行 LLM 给出的动作序列。
 *
 * 安全限制：
 * - maxSteps：单次任务最大步数，防止 LLM 死循环。
 * - 每步可返回结果；调用方可以把每步结果回填给 LLM 做多轮反思。
 */
class ActionSequenceExecutor(
    private val registry: ToolRegistry,
    private val maxSteps: Int = 20,
) {
    suspend fun execute(sequence: List<ToolCall>): List<StepExecution> {
        if (sequence.size > maxSteps) {
            return listOf(
                StepExecution(
                    index = 0,
                    call = ToolCall("__limit__", emptyMap()),
                    result = ToolResult.failure("动作序列超过上限 $maxSteps 步，已停止"),
                ),
            )
        }
        return sequence.mapIndexed { index, call ->
            val tool = registry.get(call.tool)
            val result = if (tool == null) {
                ToolResult.failure("未知工具：${call.tool}，可用工具：${registry.names().joinToString()}")
            } else {
                runCatching { tool.execute(call.args) }
                    .getOrElse { ToolResult.failure("工具异常：${it.message ?: it.javaClass.simpleName}") }
            }
            StepExecution(index = index, call = call, result = result)
        }
    }
}

data class StepExecution(
    val index: Int,
    val call: ToolCall,
    val result: ToolResult,
)
