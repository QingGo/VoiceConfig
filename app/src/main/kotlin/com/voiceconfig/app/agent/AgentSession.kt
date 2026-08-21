package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 多轮 Agent 会话层：原生 function calling 循环。
 *
 * 流程：
 * 用户输入 → LLM 返回 tool_calls → 逐个执行工具 → 回填 role=tool 消息
 * → 再次调用 LLM → 直到没有 tool_calls。
 */
@Singleton
class AgentSession @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val chatClient: AgentToolChat,
    private val trace: AgentTrace,
) {
    /** 可替换的工具参数解析器，便于测试。 */
    var argumentParser: (String) -> Map<String, Any?> = { JsonToolCallParser.parseArguments(it) }

    @Volatile
    private var cancelled = false

    private val history = mutableListOf<AgentMessage>()

    fun cancel() {
        cancelled = true
    }

    fun clear() {
        history.clear()
    }

    fun restore(messages: List<AgentMessage>) {
        history.clear()
        history.addAll(messages)
    }

    fun historySnapshot(): List<AgentMessage> = history.toList()

    suspend fun send(
        userText: String,
        maxRounds: Int = 60,
        onStreamEvent: (AgentStreamEvent) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
    ): AgentTurnResult {
        if (userText.isBlank()) return AgentTurnResult(ok = false, message = "输入为空", toolCalls = emptyList(), history = historySnapshot())
        cancelled = false
        history += AgentMessage("user", userText)
        onMessage(history.last())
        trace.log("user_input", mapOf("text" to userText))

        val systemPrompt = buildSystemPrompt()
        val allToolCalls = mutableListOf<ToolCall>()
        val allSteps = mutableListOf<StepExecution>()
        var latestScreenBase64: String? = null
        var latestScreenWidth: Int? = null
        var latestScreenHeight: Int? = null

        for (round in 0 until maxRounds) {
            latestScreenBase64 = null
            latestScreenWidth = null
            latestScreenHeight = null
            if (cancelled) {
                trace.log("run_cancelled", mapOf("round" to round))
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot())
            }
            trace.log(
                "llm_request",
                mapOf(
                    "round" to round,
                    "message_count" to historySnapshot().size,
                    "has_screenshot" to pruneImageHistory(historySnapshot()).any { it.imageBase64 != null },
                ),
            )
            val response = chatClient.streamWithTools(systemPrompt, pruneImageHistory(historySnapshot()), toolRegistry.tools(), onStreamEvent) ?: run {
                val detail = chatClient.lastError?.let { "：$it" } ?: ""
                val error = "模型未返回结果$detail"
                trace.log("llm_error", mapOf("round" to round, "error" to error))
                history += AgentMessage("assistant", "执行失败：$error")
                onMessage(history.last())
                return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, history = historySnapshot())
            }

            // 把 assistant 消息（含 reasoning/tool_calls）追加到历史
            val assistantContent = response.content ?: ""
            history += AgentMessage(
                role = "assistant",
                content = assistantContent,
                reasoningContent = response.reasoningContent,
                toolCallsJson = toolCallsToJson(response.toolCalls),
            )
            onMessage(history.last())
            trace.log(
                "llm_response",
                mapOf(
                    "round" to round,
                    "content" to assistantContent,
                    "reasoning" to (response.reasoningContent ?: ""),
                    "tool_calls" to response.toolCalls.map { mapOf("id" to it.id, "name" to it.name, "arguments" to it.arguments) },
                    "finish_reason" to (response.finishReason ?: ""),
                ),
            )

            if (cancelled) {
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot())
            }

            if (response.toolCalls.isEmpty()) {
                if (response.finishReason == "tool_calls") {
                    val error = "模型声明需要调用工具，但未返回工具参数"
                    trace.log("llm_error", mapOf("round" to round, "error" to error))
                    history += AgentMessage("assistant", "执行失败：$error")
                    onMessage(history.last())
                    return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, history = historySnapshot())
                }
                val finalText = assistantContent.ifBlank { "（无文本回复）" }
                trace.log("run_finished", mapOf("ok" to true, "message" to finalText, "tool_call_count" to allToolCalls.size))
                return AgentTurnResult(
                    ok = true,
                    message = finalText,
                    toolCalls = allToolCalls,
                    history = historySnapshot(),
                )
            }

            for (toolCall in response.toolCalls) {
                if (cancelled) {
                    history += AgentMessage("assistant", "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot())
                }
                val tool = toolRegistry.get(toolCall.name)
                if (tool == null) {
                    val errMsg = "未知工具：${toolCall.name}"
                    trace.log("tool_error", mapOf("tool" to toolCall.name, "error" to errMsg))
                    history += AgentMessage(
                        role = "tool",
                        content = errMsg,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        toolArgs = toolCall.arguments,
                        toolResultOk = false,
                    )
                    onMessage(history.last())
                    allSteps += StepExecution(
                        index = allSteps.size,
                        call = ToolCall(toolCall.name, emptyMap()),
                        result = ToolResult.failure(errMsg),
                    )
                    continue
                }
                val args = runCatching { argumentParser(toolCall.arguments) }.getOrDefault(emptyMap())
                trace.log("tool_call", mapOf("tool" to toolCall.name, "args" to args))
                val result = runCatching { tool.execute(args) }
                    .getOrElse { ToolResult.failure(it.message ?: it.javaClass.simpleName) }
                trace.log(
                    "tool_result",
                    mapOf(
                        "tool" to toolCall.name,
                        "ok" to result.ok,
                        "message" to result.message,
                        "data_keys" to result.data.keys.toList(),
                    ),
                )
                history += AgentMessage(
                    role = "tool",
                    content = result.message,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    toolArgs = toolCall.arguments,
                    toolResultOk = result.ok,
                )
                onMessage(history.last())
                allToolCalls += ToolCall(toolCall.name, args)
                allSteps += StepExecution(
                    index = allSteps.size,
                    call = ToolCall(toolCall.name, args),
                    result = result,
                )
                (result.data["image_base64"] as? String)?.takeIf { it.isNotBlank() }?.let { image ->
                    latestScreenBase64 = image
                    latestScreenWidth = (result.data["width"] as? Number)?.toInt()
                    latestScreenHeight = (result.data["height"] as? Number)?.toInt()
                    val path = trace.saveScreenshot(image, toolCall.name)
                    trace.log("image_seen", mapOf("tool" to toolCall.name, "path" to path, "base64_length" to image.length))
                }
                if (cancelled) {
                    history += AgentMessage("assistant", "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot())
                }
            }

            latestScreenBase64?.let { image ->
                val resolutionText = if (latestScreenWidth != null && latestScreenHeight != null) {
                    "屏幕分辨率 ${latestScreenWidth}x${latestScreenHeight}"
                } else {
                    "屏幕分辨率未知"
                }
                history += AgentMessage(
                    role = "user",
                    content = "这是执行上述工具后的当前屏幕截图。$resolutionText，坐标原点在左上角，单位是像素。请根据画面中的元素估算绝对坐标，再调用 tap/input_text/swipe。",
                    imageBase64 = image,
                )
                onMessage(history.last())
            }
        }

        val summary = allSteps.joinToString("\n") { step ->
            val status = if (step.result.ok) "✅" else "❌"
            "$status ${step.call.tool}(${step.call.args}) -> ${step.result.message}"
        }
        trace.log("run_finished", mapOf("ok" to allSteps.all { it.result.ok }, "message" to summary.ifBlank { "达到最大轮数" }, "tool_call_count" to allToolCalls.size))
        return AgentTurnResult(
            ok = allSteps.all { it.result.ok },
            message = summary.ifBlank { "达到最大轮数" },
            toolCalls = allToolCalls,
            history = historySnapshot(),
        )
    }

    private fun toolCallsToJson(toolCalls: List<AgentToolCall>): String? {
        if (toolCalls.isEmpty()) return null
        return buildString {
            append('[')
            toolCalls.forEachIndexed { index, call ->
                if (index > 0) append(',')
                append("{\"id\":\"${escapeJson(call.id)}\",\"type\":\"function\",\"function\":{\"name\":\"${escapeJson(call.name)}\",\"arguments\":\"${escapeJson(call.arguments)}\"}}")
            }
            append(']')
        }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun pruneImageHistory(messages: List<AgentMessage>): List<AgentMessage> {
        val imageIndices = messages.mapIndexedNotNull { index, msg ->
            if (msg.imageBase64 != null) index else null
        }
        if (imageIndices.size <= 2) return messages
        val drop = imageIndices.dropLast(2).toSet()
        return messages.filterIndexed { index, _ -> index !in drop }
    }

    private fun buildSystemPrompt(): String {
        val toolDesc = toolRegistry.descriptions()
        return """
            你是“言控”手机自动化 Agent。根据用户指令选择并调用工具。
            可用工具：
            $toolDesc

            规则：
            - 如果需要调用工具，使用 function calling 返回 tool_calls；一轮可以返回多个工具调用。
            - 执行完工具后，根据工具结果继续判断是否需要更多工具，直到目标完成。
            - 查找应用时优先使用 find_app，不要用 run_shell 列举全部包名。
            - 对于需要“看屏幕”的任务（如微信小程序下单、App 内操作），优先调用 read_screen 获取当前画面，再根据画面调用 tap/input_text/swipe/wait。
            - 点击有明确文字的按钮时，优先使用 tap_text，并传入当前界面实际看到的文字（可用 texts 传多个候选，例如发送按钮可能是“发送/Send/发送消息”）；如果按钮是纯图标没有文字，用 review_tap 预览后 tap。
            - 点击前如果不确定坐标，先调用 review_tap 查看标记位置，再决定是否调整或执行 tap。
            - 每次操作后应继续 read_screen 确认页面变化，再决定下一步，不要一次性盲目点击。
            - 涉及下单/支付/购买等敏感操作时，只负责操作到“确认订单”页面，不要点击“提交订单/支付/确认购买”等最终按钮，最后请用户手动确认下单。
            - 忽略无关的营销活动、优惠券领取、弹窗引导，除非用户明确要求。
            - 在聊天输入框中输入完成后，优先使用 press_key {"key":"enter"} 发送；如果无效再点击界面上的发送按钮。
            - 完成用户目标后，用简短中文总结结果。
            - 不要编造工具执行结果或截图内容。
        """.trimIndent()
    }
}

data class AgentTurnResult(
    val ok: Boolean,
    val message: String,
    val toolCalls: List<ToolCall>,
    val history: List<AgentMessage>,
)
