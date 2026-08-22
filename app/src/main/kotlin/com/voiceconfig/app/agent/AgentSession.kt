package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
    private val safety = AgentSafety()

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

    /**
     * 后台/自动化执行入口：临时使用独立 history，执行结束后恢复手动会话上下文。
     * 适用于定时 Agent 任务、立即执行等不希望污染用户当前会话的场景。
     */
    suspend fun sendIsolated(
        userText: String,
        maxRounds: Int = 60,
        skills: List<AgentSkill> = emptyList(),
        verifyPolicy: AgentVerificationPolicy = AgentVerificationPolicy(),
        runPolicy: AgentRunPolicy = AgentRunPolicy(),
        onSensitiveAction: suspend (SensitiveActionRequest) -> Boolean = { false },
    ): AgentTurnResult {
        val saved = history.toList()
        history.clear()
        return try {
            send(
                userText = userText,
                maxRounds = maxRounds,
                skills = skills,
                verifyPolicy = verifyPolicy,
                runPolicy = runPolicy,
                onSensitiveAction = onSensitiveAction,
            )
        } finally {
            history.clear()
            history.addAll(saved)
        }
    }

    suspend fun send(
        userText: String,
        maxRounds: Int = 60,
        skills: List<AgentSkill> = emptyList(),
        verifyPolicy: AgentVerificationPolicy = AgentVerificationPolicy(),
        runPolicy: AgentRunPolicy = AgentRunPolicy(),
        onStateChange: (AgentRunState) -> Unit = {},
        onStreamEvent: (AgentStreamEvent) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
        onSensitiveAction: suspend (SensitiveActionRequest) -> Boolean = { false },
        onStep: (AgentStepUi) -> Unit = {},
    ): AgentTurnResult {
        if (userText.isBlank()) return AgentTurnResult(ok = false, message = "输入为空", toolCalls = emptyList(), history = historySnapshot(), runId = "")
        cancelled = false
        val runId = trace.startRun(userText)
        history += AgentMessage("user", userText)
        onMessage(history.last())
        trace.log(runId, "user_input", mapOf("text" to userText))

        val systemPrompt = buildSystemPrompt(skills)
        val allToolCalls = mutableListOf<ToolCall>()
        val allSteps = mutableListOf<StepExecution>()
        var consecutiveFailures = 0
        var latestScreenBase64: String? = null
        var latestScreenWidth: Int? = null
        var latestScreenHeight: Int? = null
        var autoVerifyCount = 0
        var lastAutoVerifyAt = 0L
        val startedAtMs = System.currentTimeMillis()
        var llmWaitMs = 0L
        var toolExecMs = 0L
        var verifyMs = 0L
        var rounds = 0
        var lastStepEndElapsedMs = 0L
        val recentActionKeys = mutableListOf<String>()
        var runState: AgentRunState? = null
        fun setState(newState: AgentRunState) {
            if (runState != newState) {
                runState = newState
                onStateChange(newState)
                trace.log(runId, "run_state", mapOf("state" to newState.name, "round" to rounds))
            }
        }
        setState(AgentRunState.RUNNING)
        fun finishRun(ok: Boolean, message: String) {
            trace.log(runId, "run_finished", mapOf("ok" to ok, "message" to message, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
        }

        for (round in 0 until maxRounds) {
            rounds++
            latestScreenBase64 = null
            latestScreenWidth = null
            latestScreenHeight = null
            if (cancelled) {
                setState(AgentRunState.CANCELLED)
                trace.log(runId, "run_cancelled", mapOf("round" to round))
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                finishRun(false, "已停止")
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot(), runId = runId)
            }
            if (System.currentTimeMillis() - startedAtMs > runPolicy.overallTimeoutMs) {
                val error = "整体执行超时（${runPolicy.overallTimeoutMs / 1000}s）"
                setState(AgentRunState.FAILED)
                trace.log(runId, "run_timeout", mapOf("round" to round, "timeout_ms" to runPolicy.overallTimeoutMs))
                history += AgentMessage("assistant", "执行失败：$error")
                onMessage(history.last())
                finishRun(false, error)
                return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
            }
            trace.log(
                runId,
                "llm_request",
                mapOf(
                    "round" to round,
                    "message_count" to historySnapshot().size,
                    "has_screenshot" to pruneImageHistory(historySnapshot()).any { it.imageBase64 != null },
                ),
            )
            val llmStartMs = System.currentTimeMillis()
            var response: AgentChatResponse? = null
            var llmError = ""
            for (attempt in 0..runPolicy.llmRetries) {
                try {
                    response = withTimeout(runPolicy.llmTimeoutMs) {
                        chatClient.streamWithTools(
                            systemPrompt,
                            pruneImageHistory(historySnapshot()),
                            toolRegistry.coreTools(),
                            onStreamEvent,
                        )
                    }
                    if (response != null) break
                    llmError = chatClient.lastError?.let { "模型未返回结果：$it" } ?: "模型未返回结果"
                } catch (e: TimeoutCancellationException) {
                    llmError = "LLM 请求超时（${runPolicy.llmTimeoutMs / 1000}s）"
                    if (attempt < runPolicy.llmRetries) delay(1_500)
                } catch (e: Exception) {
                    llmError = "LLM 请求异常：${e.message ?: e.javaClass.simpleName}"
                    if (attempt < runPolicy.llmRetries) delay(1_500)
                }
            }
            val roundLlmWaitMs = System.currentTimeMillis() - llmStartMs
            llmWaitMs += roundLlmWaitMs

            if (response == null) {
                setState(AgentRunState.FAILED)
                trace.log(runId, "llm_error", mapOf("round" to round, "error" to llmError))
                history += AgentMessage("assistant", "执行失败：$llmError")
                onMessage(history.last())
                finishRun(false, llmError)
                return AgentTurnResult(ok = false, message = llmError, toolCalls = allToolCalls, history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
            }

            // 把 assistant 消息（含 reasoning/tool_calls）追加到历史
            val assistantContent = response.content ?: ""
            history += AgentMessage(
                role = "assistant",
                content = assistantContent,
                reasoningContent = response.reasoningContent,
                toolCallsJson = toolCallsToJson(response.toolCalls),
                durationMs = roundLlmWaitMs,
                thinkingMs = response.thinkingMs,
                outputMs = response.outputMs,
            )
            onMessage(history.last())
            trace.log(
                runId,
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
                setState(AgentRunState.CANCELLED)
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                finishRun(false, "已停止")
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot(), runId = runId)
            }

            if (response.toolCalls.isEmpty()) {
                if (response.finishReason == "tool_calls") {
                    val error = "模型声明需要调用工具，但未返回工具参数"
                    setState(AgentRunState.FAILED)
                    trace.log(runId, "llm_error", mapOf("round" to round, "error" to error))
                    history += AgentMessage("assistant", "执行失败：$error")
                    onMessage(history.last())
                    finishRun(false, error)
                    return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, history = historySnapshot(), runId = runId)
                }
                val finalText = assistantContent.ifBlank { "（无文本回复）" }
                setState(AgentRunState.DONE)
                trace.log(runId, "run_finished", mapOf("ok" to true, "message" to finalText, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
                return AgentTurnResult(
                    ok = true,
                    message = finalText,
                    toolCalls = allToolCalls,
                    history = historySnapshot(),
                    runId = runId,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    llmWaitMs = llmWaitMs,
                    toolExecMs = toolExecMs,
                    verifyMs = verifyMs,
                    rounds = rounds,
                )
            }

            for (toolCall in response.toolCalls) {
                if (cancelled) {
                    setState(AgentRunState.CANCELLED)
                    history += AgentMessage("assistant", "已停止")
                    finishRun(false, "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot(), runId = runId)
                }
                val tool = toolRegistry.get(toolCall.name)
                if (tool == null) {
                    val errMsg = "未知工具：${toolCall.name}"
                    trace.log(runId, "tool_error", mapOf("tool" to toolCall.name, "error" to errMsg))
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
                val actionKey = toolCall.name + ":" + args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
                val sameActionRecentCount = recentActionKeys.takeLast(12).count { it == actionKey }
                if (sameActionRecentCount >= runPolicy.maxSamePageRepeats) {
                    val repeatError = "检测到同一操作重复 ${sameActionRecentCount + 1} 次（$actionKey），已停止避免死循环"
                    setState(AgentRunState.FAILED)
                    trace.log(runId, "repeat_detected", mapOf("tool" to toolCall.name, "args" to args, "count" to sameActionRecentCount + 1))
                    history += AgentMessage("assistant", "执行失败：$repeatError")
                    onMessage(history.last())
                    finishRun(false, repeatError)
                    return AgentTurnResult(ok = false, message = repeatError, toolCalls = allToolCalls, history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
                }
                recentActionKeys += actionKey
                if (recentActionKeys.size > 30) recentActionKeys.removeAt(0)
                val stepIndex = allSteps.size
                val toolStartElapsedMs = System.currentTimeMillis() - startedAtMs
                val gapBeforeMs = (toolStartElapsedMs - lastStepEndElapsedMs).coerceAtLeast(0)
                onStep(
                    AgentStepUi(
                        index = stepIndex,
                        runId = runId,
                        toolName = toolCall.name,
                        argsText = args.toString(),
                        status = AgentStepStatus.RUNNING,
                        gapBeforeMs = gapBeforeMs,
                        startedAtElapsedMs = toolStartElapsedMs,
                    ),
                )
                val sensitiveRequest = SensitiveActionRequest(tool.name, args)
                if (safety.requiresConfirmation(tool, args)) {
                    setState(AgentRunState.WAITING_CONFIRM)
                    val approved = onSensitiveAction(sensitiveRequest)
                    setState(AgentRunState.RUNNING)
                    if (!approved) {
                        val declined = "用户未确认敏感操作，已取消：${safety.describe(tool.name, args)}"
                        onStep(
                            AgentStepUi(
                                index = stepIndex,
                                runId = runId,
                                toolName = toolCall.name,
                                argsText = args.toString(),
                                status = AgentStepStatus.DECLINED,
                                message = declined,
                            ),
                        )
                        trace.log(runId, "tool_declined", mapOf("tool" to toolCall.name, "args" to args, "reason" to "user_denied"))
                        history += AgentMessage(
                            role = "tool",
                            content = declined,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            toolArgs = toolCall.arguments,
                            toolResultOk = false,
                        )
                        onMessage(history.last())
                        allToolCalls += ToolCall(toolCall.name, args)
                        allSteps += StepExecution(
                            index = allSteps.size,
                            call = ToolCall(toolCall.name, args),
                            result = ToolResult.failure(declined),
                        )
                        continue
                    }
                }

                trace.log(runId, "tool_call", mapOf("tool" to toolCall.name, "args" to args))
                val toolStartMs = System.currentTimeMillis()
                val result = try {
                    withTimeout(runPolicy.toolTimeoutMs) { tool.execute(args) }
                } catch (e: TimeoutCancellationException) {
                    ToolResult.failure("工具 ${toolCall.name} 执行超时（${runPolicy.toolTimeoutMs / 1000}s）")
                } catch (e: Exception) {
                    ToolResult.failure(e.message ?: e.javaClass.simpleName)
                }
                toolExecMs += System.currentTimeMillis() - toolStartMs
                onStep(
                    AgentStepUi(
                        index = stepIndex,
                        runId = runId,
                        toolName = toolCall.name,
                        argsText = args.toString(),
                        status = if (result.ok) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
                        message = result.message,
                        durationMs = System.currentTimeMillis() - toolStartMs,
                        gapBeforeMs = gapBeforeMs,
                        startedAtElapsedMs = toolStartElapsedMs,
                    ),
                )
                if (result.ok) consecutiveFailures = 0 else consecutiveFailures++
                trace.log(
                runId,
                    "tool_result",
                    mapOf(
                        "tool" to toolCall.name,
                        "ok" to result.ok,
                        "message" to result.message,
                        "data_keys" to result.data.keys.toList(),
                        "timing_ms" to (result.data["timingMs"] ?: emptyMap<Any, Any>()),
                    ),
                )
                history += AgentMessage(
                    role = "tool",
                    content = result.message,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    toolArgs = toolCall.arguments,
                    toolResultOk = result.ok,
                    durationMs = System.currentTimeMillis() - toolStartMs,
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
                    val path = trace.saveScreenshot(runId, image, toolCall.name)
                    trace.log(runId, "image_seen", mapOf("tool" to toolCall.name, "path" to path, "base64_length" to image.length))
                }

                // Phase 1.5 动作验证循环：按工具元数据 + 成本策略自动截屏。
                if (result.data["image_base64"] == null &&
                    tool.metadata.requiresAutoVerify &&
                    verifyPolicy.enabled &&
                    autoVerifyCount < verifyPolicy.maxPerRun &&
                    System.currentTimeMillis() - lastAutoVerifyAt >= verifyPolicy.minIntervalMs
                ) {
                    autoVerifyCount++
                    lastAutoVerifyAt = System.currentTimeMillis()
                    val verifyStepIndex = allSteps.size
                    val verifyStartMs = System.currentTimeMillis()
                    onStep(
                        AgentStepUi(
                            index = verifyStepIndex,
                            runId = runId,
                            toolName = "read_screen",
                            argsText = "{\"autoVerify\":true}",
                            status = AgentStepStatus.RUNNING,
                            startedAtElapsedMs = System.currentTimeMillis() - startedAtMs,
                        ),
                    )
                    val verify = runCatching { toolRegistry.get("read_screen")?.execute(emptyMap()) }.getOrNull()
                    val verifyDurationMs = System.currentTimeMillis() - verifyStartMs
                    val verifyImage = verify?.data?.get("image_base64") as? String
                    if (verify?.ok == true && !verifyImage.isNullOrBlank()) {
                        latestScreenBase64 = verifyImage
                        latestScreenWidth = (verify.data["width"] as? Number)?.toInt()
                        latestScreenHeight = (verify.data["height"] as? Number)?.toInt()
                        val path = trace.saveScreenshot(runId, verifyImage, "auto_verify_${toolCall.name}")
                        trace.log(runId, "auto_verify", mapOf("tool" to toolCall.name, "path" to path, "base64_length" to verifyImage.length))
                    }
                    val verifyResult = verify ?: ToolResult.failure("自动验证截屏失败")
                    onStep(
                        AgentStepUi(
                            index = verifyStepIndex,
                            runId = runId,
                            toolName = "read_screen",
                            argsText = "{\"autoVerify\":true}",
                            status = if (verifyResult.ok) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
                            message = if (verifyResult.ok) "自动截屏验证" else verifyResult.message,
                            durationMs = verifyDurationMs,
                            gapBeforeMs = (System.currentTimeMillis() - startedAtMs - lastStepEndElapsedMs).coerceAtLeast(0),
                            startedAtElapsedMs = System.currentTimeMillis() - startedAtMs - verifyDurationMs,
                        ),
                    )
                    allSteps += StepExecution(
                        index = verifyStepIndex,
                        call = ToolCall("read_screen", mapOf("autoVerify" to true)),
                        result = verifyResult,
                    )
                    verifyMs += verifyDurationMs
                }

                lastStepEndElapsedMs = System.currentTimeMillis() - startedAtMs
                if (cancelled) {
                    setState(AgentRunState.CANCELLED)
                    history += AgentMessage("assistant", "已停止")
                    finishRun(false, "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, history = historySnapshot(), runId = runId)
                }
            }

            if (consecutiveFailures >= 3) {
                val guidance = "检测到连续 ${consecutiveFailures} 次工具失败。请停止重复尝试相同操作，改用其他入口（如搜索、返回上一页、换一种方式），或者直接向用户说明并询问下一步。"
                history += AgentMessage("user", guidance)
                onMessage(history.last())
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
        trace.log(runId, "run_finished", mapOf("ok" to allSteps.all { it.result.ok }, "message" to summary.ifBlank { "达到最大轮数" }, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
        return AgentTurnResult(
            ok = allSteps.all { it.result.ok },
            message = summary.ifBlank { "达到最大轮数" },
            toolCalls = allToolCalls,
            history = historySnapshot(),
            runId = runId,
            durationMs = System.currentTimeMillis() - startedAtMs,
            llmWaitMs = llmWaitMs,
            toolExecMs = toolExecMs,
            verifyMs = verifyMs,
            rounds = rounds,
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

    private fun buildSystemPrompt(skills: List<AgentSkill> = emptyList()): String {
        val toolDesc = toolRegistry.coreDescriptions()
        val skillText = if (skills.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine()
                appendLine("历史成功路径参考（仅作参考，必须结合当前界面重新验证）：")
                skills.forEachIndexed { index, skill ->
                    appendLine("${index + 1}. 技能：${skill.name}")
                    if (skill.description.isNotBlank()) appendLine("   说明：${skill.description}")
                    if (skill.whenToUse.isNotBlank()) appendLine("   适用：${skill.whenToUse}")
                    appendLine("   成功步骤：${skill.steps.joinToString(" -> ") { step ->
                        val purpose = if (step.purpose.isNotBlank()) " // ${step.purpose}" else ""
                        step.toolName + "(" + step.args.take(120) + ")" + purpose
                    }}")
                }
            }
        }
        return """
            你是“言控”手机自动化 Agent。根据用户指令选择并调用工具。
            可用工具：
            $toolDesc$skillText

            规则：
            - 如果需要调用工具，使用 function calling 返回 tool_calls；一轮可以返回多个工具调用。
            - 执行完工具后，根据工具结果继续判断是否需要更多工具，直到目标完成。
            - 查找应用时优先使用 find_app，不要用 run_shell 列举全部包名。
            - 为节省时间和 token，不要每步都调用 get_screen_state / read_screen。get_screen_state 已返回完整 UI 树和截图时，优先继续使用其中的文字与坐标完成连续操作；同一页面没有明显变化就不要重复获取。
            - 仅在页面切换、需要看图标按钮位置、或现有坐标不再可靠时，再获取一次 get_screen_state（或 read_screen）。
            - 点击有明确文字的按钮时，直接使用 tap_text，不要为每个按钮先 review_tap；只有纯图标且坐标不确定时才 review_tap 预览后 tap。
            - 操作后如果工具结果已经明确成功，不需要截图确认；需要确认新页面时才获取屏幕状态。
            - 网页搜索优先使用 open_search 直接打开搜索引擎结果页（如 baidu），避免在浏览器输入框手动输入中文；只有需要点击搜索按钮或打开具体结果时才进入浏览器操作。
            - 创建日历事件时优先使用 create_calendar_event 直接打开预填好的新建事件页，然后点击保存；不要手动在日历里反复点“+”。
            - 涉及下单/支付/购买等敏感操作时，只负责操作到“确认订单”页面，不要点击“提交订单/支付/确认购买”等最终按钮，最后请用户手动确认下单。
            - 忽略无关的营销活动、优惠券领取、弹窗引导，除非用户明确要求。
            - 在聊天输入框中输入完成后，优先使用 press_key {"key":"enter"} 发送；如果无效再点击界面上的发送按钮。
            - 完成用户目标后，用简短中文总结结果。
            - 只有实际收到过屏幕截图（包括自动验证截图）时，才可以说“从截图/画面中看到”；没有截图时只能基于 UI 树和工具结果描述，不得声称看过截图。
            - 不要编造工具执行结果或截图内容。
        """.trimIndent()
    }
}

data class AgentTurnResult(
    val ok: Boolean,
    val message: String,
    val toolCalls: List<ToolCall>,
    val history: List<AgentMessage>,
    val runId: String = "",
    val durationMs: Long = 0,
    val llmWaitMs: Long = 0,
    val toolExecMs: Long = 0,
    val verifyMs: Long = 0,
    val rounds: Int = 0,
)


data class AgentStepUi(
    val index: Int,
    val toolName: String,
    val argsText: String,
    val status: AgentStepStatus,
    val message: String = "",
    val runId: String = "",
    val durationMs: Long = 0,
    val gapBeforeMs: Long = 0,
    val startedAtElapsedMs: Long = 0,
)

enum class AgentStepStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    DECLINED,
}

data class AgentVerificationPolicy(
    val enabled: Boolean = true,
    val maxPerRun: Int = 2,
    val minIntervalMs: Long = 3_000,
)

enum class AgentRunState {
    RUNNING,
    WAITING_CONFIRM,
    DONE,
    FAILED,
    CANCELLED,
}

data class AgentRunPolicy(
    val overallTimeoutMs: Long = 10 * 60 * 1_000L,
    val llmTimeoutMs: Long = 120_000L,
    val llmRetries: Int = 1,
    val toolTimeoutMs: Long = 45_000L,
    val maxSamePageRepeats: Int = 6,
)
