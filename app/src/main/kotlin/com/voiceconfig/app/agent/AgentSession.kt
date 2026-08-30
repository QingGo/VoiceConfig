package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.voiceconfig.app.voice.VoiceCommandOrigin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个 runId 的运行控制状态。
 */
class RunControl {
    @Volatile var cancelled: Boolean = false
    @Volatile var paused: Boolean = false
}

/**
 * 单个 run 的安全事件计数，供运行记录/审计指标使用。
 */
data class SafetyRunStats(
    var confirmations: Int = 0,
    var approvals: Int = 0,
    var denials: Int = 0,
    var blocks: Int = 0,
)

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
    private val taskPlanStore: TaskPlanStore,
    private val runLedger: AgentRunLedger,
) {
    private val safety = AgentSafety()
    private val stopVerifier = StopVerifier()
    private val runMutex = Mutex()

    /** 可替换的工具参数解析器，便于测试。 */
    var argumentParser: (String) -> Map<String, Any?> = { JsonToolCallParser.parseArguments(it) }

    private val runControls = ConcurrentHashMap<String, RunControl>()
    private val safetyStatsByRun = ConcurrentHashMap<String, SafetyRunStats>()

    @Volatile
    private var activeRunId: String? = null

    private val history = mutableListOf<AgentMessage>()

    fun currentRunId(): String? = activeRunId

    fun cancel(runId: String? = activeRunId) {
        val id = runId ?: return
        runControls.getOrPut(id) { RunControl() }.cancelled = true
    }

    fun pause(runId: String? = activeRunId) {
        val id = runId ?: return
        runControls.getOrPut(id) { RunControl() }.paused = true
    }

    fun cancelAll() {
        runControls.values.forEach { it.cancelled = true }
    }

    fun pauseAll() {
        runControls.values.forEach { it.paused = true }
    }

    suspend fun clear() {
        runMutex.withLock {
            history.clear()
        }
    }

    suspend fun restore(messages: List<AgentMessage>) {
        runMutex.withLock {
            history.clear()
            history.addAll(messages)
        }
    }

    private fun cleanupRun(runId: String) {
        if (activeRunId == runId) activeRunId = null
        runControls.remove(runId)
    }

    fun historySnapshot(): List<AgentMessage> = history.toList()

    private suspend fun completeTaskPlan() {
        val plan = taskPlanStore.snapshot() ?: return
        val completed = plan.copy(status = TaskPlanStatus.COMPLETED, waitingForHuman = null)
        taskPlanStore.set(completed)
        taskPlanStore.saveCurrent()
    }

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
        plan: TaskPlan? = null,
        resetHistory: Boolean = false,
        capabilitySummary: String? = null,
        onStateChange: (AgentRunState) -> Unit = {},
        onStep: (AgentStepUi) -> Unit = {},
        onSensitiveAction: suspend (SensitiveActionRequest) -> Boolean = { false },
        origin: VoiceCommandOrigin? = null,
        preflight: AgentPreflightResult? = null,
    ): AgentTurnResult = runMutex.withLock {
        val saved = history.toList()
        history.clear()
        try {
            val rawResult = sendLocked(
                userText = userText,
                maxRounds = maxRounds,
                skills = skills,
                verifyPolicy = verifyPolicy,
                runPolicy = runPolicy,
                plan = plan,
                resetHistory = resetHistory,
                onStateChange = onStateChange,
                onStep = onStep,
                onSensitiveAction = onSensitiveAction,
                origin = origin,
                preflight = preflight,
            )
            val stats = safetyStatsByRun.remove(rawResult.runId) ?: SafetyRunStats()
            val result = rawResult.withSafetyStats(stats)
            runLedger.record(result.toRunRecord(userText, capabilitySummary))
            cleanupRun(result.runId)
            result
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
        plan: TaskPlan? = null,
        resetHistory: Boolean = false,
        capabilitySummary: String? = null,
        onStateChange: (AgentRunState) -> Unit = {},
        onStreamEvent: (AgentStreamEvent) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
        onSensitiveAction: suspend (SensitiveActionRequest) -> Boolean = { false },
        onStep: (AgentStepUi) -> Unit = {},
        origin: VoiceCommandOrigin? = null,
        preflight: AgentPreflightResult? = null,
    ): AgentTurnResult = runMutex.withLock {
        val rawResult = sendLocked(
            userText = userText,
            maxRounds = maxRounds,
            skills = skills,
            verifyPolicy = verifyPolicy,
            runPolicy = runPolicy,
            plan = plan,
            resetHistory = resetHistory,
            onStateChange = onStateChange,
            onStreamEvent = onStreamEvent,
            onMessage = onMessage,
            onSensitiveAction = onSensitiveAction,
            onStep = onStep,
            origin = origin,
            preflight = preflight,
        )
        val stats = safetyStatsByRun.remove(rawResult.runId) ?: SafetyRunStats()
        val result = rawResult.withSafetyStats(stats)
        runLedger.record(result.toRunRecord(userText, capabilitySummary))
        cleanupRun(result.runId)
        result
    }

    private suspend fun sendLocked(
        userText: String,
        maxRounds: Int = 60,
        skills: List<AgentSkill> = emptyList(),
        verifyPolicy: AgentVerificationPolicy = AgentVerificationPolicy(),
        runPolicy: AgentRunPolicy = AgentRunPolicy(),
        plan: TaskPlan? = null,
        resetHistory: Boolean = false,
        onStateChange: (AgentRunState) -> Unit = {},
        onStreamEvent: (AgentStreamEvent) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
        onSensitiveAction: suspend (SensitiveActionRequest) -> Boolean = { false },
        onStep: (AgentStepUi) -> Unit = {},
        origin: VoiceCommandOrigin? = null,
        preflight: AgentPreflightResult? = null,
    ): AgentTurnResult {
        if (userText.isBlank()) return AgentTurnResult(ok = false, message = "输入为空", toolCalls = emptyList(), history = historySnapshot(), runId = "")
        val runId = trace.startRun(userText)
        val control = runControls.getOrPut(runId) { RunControl() }
        safetyStatsByRun[runId] = SafetyRunStats()
        activeRunId = runId
        if (resetHistory) {
            history.clear()
        }
        history += AgentMessage("user", userText)
        onMessage(history.last())
        trace.log(runId, "user_input", mapOf("text" to userText))
        if (origin != null) {
            trace.log(runId, "voice_origin", mapOf(
                "commandId" to (origin.commandId ?: ""),
                "source" to (origin.source ?: ""),
                "confirmationToken" to (origin.confirmationToken ?: ""),
                "timestamp" to (origin.timestamp ?: 0L),
            ))
        }
        if (preflight != null) {
            trace.log(runId, "preflight", mapOf(
                "ready" to preflight.ready,
                "blockers" to preflight.blockers.joinToString(" | ") { it.message },
                "warnings" to preflight.warnings.joinToString(" | ") { it.message },
            ))
            if (!preflight.ready) {
                val blockedMessage = "能力预检未通过，已阻止执行：" + preflight.blockers.joinToString("；") { it.message }
                history += AgentMessage("assistant", blockedMessage)
                onMessage(history.last())
                trace.log(runId, "run_finished", mapOf("ok" to false, "message" to blockedMessage, "tool_call_count" to 0, "duration_ms" to 0, "preflight_blocked" to true))
                return AgentTurnResult(
                    ok = false,
                    message = blockedMessage,
                    toolCalls = emptyList(),
                    toolResults = emptyList(),
                    history = historySnapshot(),
                    runId = runId,
                    durationMs = 0,
                    rounds = 0,
                    state = AgentRunState.FAILED,
                )
            }
        }

        taskPlanStore.set(plan)
        trace.log(runId, "task_plan", mapOf(
            "goal" to userText,
            "steps" to (taskPlanStore.snapshot()?.steps?.size ?: 0),
            "waiting" to (taskPlanStore.snapshot()?.waitingForHuman != null),
            "provided" to (plan != null),
        ))

        val systemPrompt = buildSystemPrompt(skills)
        val allToolCalls = mutableListOf<ToolCall>()
        val allToolResults = mutableListOf<ToolResult>()
        val allSteps = mutableListOf<StepExecution>()
        var consecutiveFailures = 0
        var latestScreenBase64: String? = null
        var latestScreenWidth: Int? = null
        var latestScreenHeight: Int? = null
        var latestUiEvidence = ""
        var latestUiPackage: String? = null
        var autoVerifyCount = 0
        var lastAutoVerifyAt = 0L
        var visualReadCount = 0
        val startedAtMs = System.currentTimeMillis()
        var llmWaitMs = 0L
        var toolExecMs = 0L
        var verifyMs = 0L
        var rounds = 0
        var completionCheckCount = 0
        val maxCompletionChecks = 1
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
        suspend fun returnPaused(): AgentTurnResult {
            val reason = "用户已暂停（可从“未完成任务”中继续）"
            if (taskPlanStore.snapshot() == null) {
                taskPlanStore.set(
                    TaskPlan(
                        goal = userText,
                        waitingForHuman = reason,
                        status = TaskPlanStatus.WAITING_CONFIRM,
                    ),
                )
            } else {
                taskPlanStore.update {
                    it.copy(
                        waitingForHuman = reason,
                        status = TaskPlanStatus.WAITING_CONFIRM,
                    )
                }
            }
            taskPlanStore.saveCurrent()
            setState(AgentRunState.WAITING_CONFIRM)
            trace.log(runId, "run_paused", mapOf("round" to rounds, "reason" to reason))
            history += AgentMessage("assistant", "已暂停：$reason")
            onMessage(history.last())
            finishRun(true, "已暂停：$reason")
            return AgentTurnResult(
                ok = true,
                message = "已暂停：$reason",
                toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                history = historySnapshot(),
                runId = runId,
                durationMs = System.currentTimeMillis() - startedAtMs,
                llmWaitMs = llmWaitMs,
                toolExecMs = toolExecMs,
                verifyMs = verifyMs,
                rounds = rounds,
                state = AgentRunState.WAITING_CONFIRM,
                plan = taskPlanStore.snapshot(),
            )
        }

        for (round in 0 until maxRounds) {
            rounds++
            val roundStartMs = System.currentTimeMillis()
            var roundToolExecMs = 0L
            var roundVerifyMs = 0L
            latestScreenBase64 = null
            latestScreenWidth = null
            latestScreenHeight = null
            if (control.cancelled) {
                setState(AgentRunState.CANCELLED)
                trace.log(runId, "run_cancelled", mapOf("round" to round))
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                finishRun(false, "已停止")
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId)
            }
            if (control.paused) {
                return returnPaused()
            }
            if (System.currentTimeMillis() - startedAtMs > runPolicy.overallTimeoutMs) {
                val error = "整体执行超时（${runPolicy.overallTimeoutMs / 1000}s）"
                setState(AgentRunState.FAILED)
                trace.log(runId, "run_timeout", mapOf("round" to round, "timeout_ms" to runPolicy.overallTimeoutMs))
                history += AgentMessage("assistant", "执行失败：$error")
                onMessage(history.last())
                finishRun(false, error)
                return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
            }
            trace.log(
                runId,
                "llm_request",
                mapOf(
                    "round" to round,
                    "message_count" to historySnapshot().size,
                    "has_screenshot" to historyForLlm().any { it.imageBase64 != null },
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
                            // 为最大化 DeepSeek 前缀缓存，保持天然追加前缀；仅裁剪旧截图避免上下文膨胀。
                            historyForLlm(),
                            toolRegistry.modelTools(),
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
                return AgentTurnResult(ok = false, message = llmError, toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
            }
            if (control.paused) {
                return returnPaused()
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
                ttftMs = response.ttftMs,
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
                    "ttft_ms" to response.ttftMs,
                    "thinking_ms" to response.thinkingMs,
                    "output_ms" to response.outputMs,
                    "request_bytes" to response.requestBytes,
                    "prompt_cache_hit_tokens" to response.promptCacheHitTokens,
                    "prompt_cache_miss_tokens" to response.promptCacheMissTokens,
                    "prompt_tokens" to response.promptTokens,
                    "completion_tokens" to response.completionTokens,
                    "total_tokens" to response.totalTokens,
                ),
            )

            if (control.cancelled) {
                setState(AgentRunState.CANCELLED)
                history += AgentMessage("assistant", "已停止")
                onMessage(history.last())
                finishRun(false, "已停止")
                return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId)
            }

            if (response.toolCalls.isEmpty()) {
                if (response.finishReason == "tool_calls") {
                    val error = "模型声明需要调用工具，但未返回工具参数"
                    setState(AgentRunState.FAILED)
                    trace.log(runId, "llm_error", mapOf("round" to round, "error" to error))
                    history += AgentMessage("assistant", "执行失败：$error")
                    onMessage(history.last())
                    finishRun(false, error)
                    return AgentTurnResult(ok = false, message = error, toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId)
                }
                val currentPlan = taskPlanStore.snapshot()
                // 部分 App（如微信）不向无障碍暴露可读节点，模型只能从截图看到 Send/发送。
                // 这里把模型最后文本也纳入终端证据；终端只会触发等待用户，不会执行任何操作，因此仍是安全方向。
                val combinedEvidence = listOf(latestUiEvidence, assistantContent)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val stopDecision = stopVerifier.evaluate(currentPlan, combinedEvidence, latestUiPackage)
                val terminalHit = TerminalSafetyGate.detect(combinedEvidence, currentPlan?.goal, latestUiPackage)
                trace.log(runId, "stop_verifier", mapOf(
                    "round" to round,
                    "decision" to stopDecision.name,
                    "waiting" to (currentPlan?.waitingForHuman ?: ""),
                    "terminal_kind" to terminalHit.kind.name,
                    "terminal_marker" to terminalHit.marker,
                    "terminal_foreground_package" to (latestUiPackage ?: ""),
                    "steps_total" to (currentPlan?.steps?.size ?: 0),
                    "steps_completed" to (currentPlan?.steps?.count { it.status == TaskStepStatus.COMPLETED || it.status == TaskStepStatus.SKIPPED } ?: 0),
                ))
                when (stopDecision) {
                    StopDecision.WAIT_USER -> {
                        val reason = currentPlan?.waitingForHuman
                            ?: terminalHit.reason.ifBlank { "需要用户确认" }
                        val finalText = assistantContent.ifBlank { "（无文本回复）" } +
                            "\n（已暂停，等待用户确认：$reason）"
                        if (currentPlan == null) {
                            taskPlanStore.set(
                                TaskPlan(
                                    goal = userText,
                                    waitingForHuman = reason,
                                    status = TaskPlanStatus.WAITING_CONFIRM,
                                ),
                            )
                        } else if (currentPlan.waitingForHuman == null) {
                            taskPlanStore.update {
                                it.copy(waitingForHuman = reason, status = TaskPlanStatus.WAITING_CONFIRM)
                            }
                        }
                        taskPlanStore.saveCurrent()
                        val savedPlan = taskPlanStore.snapshot()
                        setState(AgentRunState.WAITING_CONFIRM)
                        trace.log(runId, "run_finished", mapOf("ok" to true, "message" to finalText, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs), "waiting" to true))
                        return AgentTurnResult(
                            ok = true,
                            message = finalText,
                            toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                            history = historySnapshot(),
                            runId = runId,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                            llmWaitMs = llmWaitMs,
                            toolExecMs = toolExecMs,
                            verifyMs = verifyMs,
                            rounds = rounds,
                            state = AgentRunState.WAITING_CONFIRM,
                            plan = savedPlan,
                        )
                    }
                    StopDecision.FAILED -> {
                        val failedSteps = currentPlan?.steps?.filter {
                            it.status == TaskStepStatus.FAILED || it.status == TaskStepStatus.BLOCKED
                        }?.joinToString("；") { "${it.id}: ${it.title}" }.orEmpty()
                        val error = "任务计划出现阻塞/失败，已停止。$failedSteps"
                        setState(AgentRunState.FAILED)
                        trace.log(runId, "plan_failed", mapOf("round" to round, "steps" to failedSteps, "message" to error))
                        history += AgentMessage("assistant", "执行失败：$error")
                        onMessage(history.last())
                        taskPlanStore.saveCurrent()
                        finishRun(false, error)
                        return AgentTurnResult(
                            ok = false,
                            message = error,
                            toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                            history = historySnapshot(),
                            runId = runId,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                            llmWaitMs = llmWaitMs,
                            toolExecMs = toolExecMs,
                            verifyMs = verifyMs,
                            rounds = rounds,
                            state = AgentRunState.FAILED,
                            plan = currentPlan,
                        )
                    }
                    StopDecision.DONE -> {
                        val commerceKeywords = listOf("下单", "支付", "购买", "订单", "结算", "确认订单", "点餐", "外卖", "购物", "买", "点一杯", "点单", "咖啡", "奶茶")
                        val isCommerceGoal = commerceKeywords.any { userText.contains(it) }
                        var cleanupNote = ""
                        if (isCommerceGoal) {
                            val cleanup = runCatching {
                                toolRegistry.get("dismiss_popups")?.execute(mapOf("allowBack" to false, "maxAttempts" to 1))
                            }.getOrNull()
                            if (cleanup?.ok == true) {
                                val actions = cleanup.data["actions"] as? List<*> ?: emptyList<Any?>()
                                if (actions.isNotEmpty()) {
                                    cleanupNote = "\n（已自动清理页面浮层：${cleanup.message}）"
                                    trace.log(runId, "auto_overlay_cleanup", mapOf("tool" to "dismiss_popups", "actions" to actions))
                                }
                            }
                        }
                        val finalText = assistantContent.ifBlank { "（无文本回复）" } + cleanupNote
                        setState(AgentRunState.DONE)
                        completeTaskPlan()
                        val donePlan = taskPlanStore.snapshot()
                        trace.log(runId, "run_finished", mapOf("ok" to true, "message" to finalText, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
                        return AgentTurnResult(
                            ok = true,
                            message = finalText,
                            toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                            history = historySnapshot(),
                            runId = runId,
                            durationMs = System.currentTimeMillis() - startedAtMs,
                            llmWaitMs = llmWaitMs,
                            toolExecMs = toolExecMs,
                            verifyMs = verifyMs,
                            rounds = rounds,
                            state = AgentRunState.DONE,
                            plan = donePlan,
                        )
                    }
                    StopDecision.CONTINUE -> {
                        // 计划尚未完成：继续走原有 completion_check，让模型再确认或继续执行。
                        // 如果多次后仍停止，现有逻辑仍会按完成处理；后续可改为强制失败。
                    }
                    StopDecision.UNKNOWN -> Unit
                }
                if (completionCheckCount < maxCompletionChecks) {
                    completionCheckCount++
                    val commerceKeywords = listOf("下单", "支付", "购买", "订单", "结算", "确认订单", "点餐", "外卖", "购物", "买", "点一杯", "点单", "咖啡", "奶茶")
                    val isCommerceGoal = commerceKeywords.any { userText.contains(it) }
                    val commerceCheck = if (isCommerceGoal) {
                        "如果已经到达确认订单/结算/支付页，请检查是否有换购、加购、免密支付、优惠等浮层或弹窗；如果有，先调用 dismiss_popups 或点击它的X关闭，然后再确认完成。不要点击最终支付/提交订单按钮。"
                    } else {
                        ""
                    }
                    val checkPrompt = "系统检查：以上是模型最后一轮回复。用户目标是：$userText。如果目标尚未完成，请继续调用工具完成；如果已经完成，请只回复“已完成”。不要只说“我要做xxx”而不调用工具。$commerceCheck"
                    history += AgentMessage("user", checkPrompt)
                    onMessage(history.last())
                    trace.log(runId, "completion_check", mapOf("round" to round, "count" to completionCheckCount, "goal" to userText))
                    val checkRoundEndMs = System.currentTimeMillis()
                    val checkRoundTotalMs = checkRoundEndMs - roundStartMs
                    val checkOtherMs = (checkRoundTotalMs - roundLlmWaitMs - roundToolExecMs - roundVerifyMs).coerceAtLeast(0)
                    trace.log(runId, "round_timing", mapOf(
                        "round" to round,
                        "total_ms" to checkRoundTotalMs,
                        "llm_wait_ms" to roundLlmWaitMs,
                        "llm_ttft_ms" to response.ttftMs,
                        "llm_thinking_ms" to response.thinkingMs,
                        "llm_output_ms" to response.outputMs,
                        "tool_exec_ms" to roundToolExecMs,
                        "verify_ms" to roundVerifyMs,
                        "other_ms" to checkOtherMs,
                        "tool_calls" to 0,
                        "phase" to "completion_check",
                    ))
                    continue
                }

                if (stopDecision == StopDecision.CONTINUE) {
                    val error = "任务计划尚未完成，且没有足够 UI 证据证明目标已完成。模型不能直接结束，请继续执行或向用户说明卡点。"
                    setState(AgentRunState.FAILED)
                    trace.log(runId, "plan_incomplete", mapOf("round" to round, "error" to error, "decision" to stopDecision.name))
                    history += AgentMessage("assistant", "执行失败：$error")
                    onMessage(history.last())
                    taskPlanStore.saveCurrent()
                    finishRun(false, error)
                    return AgentTurnResult(
                        ok = false,
                        message = error,
                        toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                        history = historySnapshot(),
                        runId = runId,
                        durationMs = System.currentTimeMillis() - startedAtMs,
                        llmWaitMs = llmWaitMs,
                        toolExecMs = toolExecMs,
                        verifyMs = verifyMs,
                        rounds = rounds,
                        state = AgentRunState.FAILED,
                        plan = currentPlan,
                    )
                }
                val commerceKeywords = listOf("下单", "支付", "购买", "订单", "结算", "确认订单", "点餐", "外卖", "购物", "买", "点一杯", "点单", "咖啡", "奶茶")
                val isCommerceGoal = commerceKeywords.any { userText.contains(it) }
                var cleanupNote = ""
                if (isCommerceGoal) {
                    val cleanup = runCatching {
                        toolRegistry.get("dismiss_popups")?.execute(mapOf("allowBack" to false, "maxAttempts" to 1))
                    }.getOrNull()
                    if (cleanup?.ok == true) {
                        val actions = cleanup.data["actions"] as? List<*> ?: emptyList<Any?>()
                        if (actions.isNotEmpty()) {
                            cleanupNote = "\n（已自动清理页面浮层：${cleanup.message}）"
                            trace.log(runId, "auto_overlay_cleanup", mapOf("tool" to "dismiss_popups", "actions" to actions))
                        }
                    }
                }
                val finalText = assistantContent.ifBlank { "（无文本回复）" } + cleanupNote
                setState(AgentRunState.DONE)
                completeTaskPlan()
                val donePlan = taskPlanStore.snapshot()
                trace.log(runId, "run_finished", mapOf("ok" to true, "message" to finalText, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
                return AgentTurnResult(
                    ok = true,
                    message = finalText,
                    toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                    history = historySnapshot(),
                    runId = runId,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    llmWaitMs = llmWaitMs,
                    toolExecMs = toolExecMs,
                    verifyMs = verifyMs,
                    rounds = rounds,
                    plan = donePlan,
                )
            }

            for (toolCall in response.toolCalls) {
                if (control.cancelled) {
                    setState(AgentRunState.CANCELLED)
                    history += AgentMessage("assistant", "已停止")
                    finishRun(false, "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId)
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
                val sameActionRecentCount = recentActionKeys.takeLastWhile { it == actionKey }.size
                val isPerceptionLoop = toolCall.name in setOf("get_screen_state", "read_ui", "read_screen") &&
                    sameActionRecentCount >= 3
                if (isPerceptionLoop) {
                    val guidance = "你已连续多次调用 ${toolCall.name} 但没有推动目标。请不要再重复读屏。改用其他操作：返回、滑动、搜索、点击有文字的元素，或向用户说明当前卡点。"
                    val interceptMsg = "系统拦截：检测到重复感知操作，未执行 ${toolCall.name}。请改用其他操作，不要再重复读屏。"
                    history += AgentMessage(
                        role = "tool",
                        content = interceptMsg,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        toolArgs = toolCall.arguments,
                        toolResultOk = false,
                    )
                    onMessage(history.last())
                    history += AgentMessage("user", guidance)
                    onMessage(history.last())
                    trace.log(runId, "perception_loop_detected", mapOf("round" to round, "tool" to toolCall.name, "count" to sameActionRecentCount + 1))
                    continue
                }
                val isVisualRead = toolCall.name == "read_screen" ||
                    (toolCall.name == "get_screen_state" && (
                        (args["includeImage"] as? Boolean) == true ||
                        (args["includeScreen"] as? Boolean) == true
                        ))
                if (isVisualRead) {
                    visualReadCount++
                    if (visualReadCount > runPolicy.maxVisualReadsPerRun) {
                        val interceptMsg = "系统拦截：本次任务视觉读屏次数已达上限（${runPolicy.maxVisualReadsPerRun} 次），未执行 ${toolCall.name}。请基于已有界面信息和工具结果继续；如果必须确认页面变化，请使用 read_ui，或说明当前卡点。"
                        history += AgentMessage(
                            role = "tool",
                            content = interceptMsg,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            toolArgs = toolCall.arguments,
                            toolResultOk = false,
                        )
                        onMessage(history.last())
                        history += AgentMessage("user", "请不要再继续截图。改用其他操作或直接说明卡点。")
                        onMessage(history.last())
                        trace.log(runId, "visual_budget_exceeded", mapOf("round" to round, "tool" to toolCall.name, "count" to visualReadCount, "limit" to runPolicy.maxVisualReadsPerRun))
                        continue
                    }
                }
                if (sameActionRecentCount >= runPolicy.maxSamePageRepeats) {
                    val repeatError = "检测到同一操作连续重复 ${sameActionRecentCount + 1} 次（$actionKey），已停止避免死循环"
                    setState(AgentRunState.FAILED)
                    trace.log(runId, "repeat_detected", mapOf("tool" to toolCall.name, "args" to args, "count" to sameActionRecentCount + 1))
                    history += AgentMessage("assistant", "执行失败：$repeatError")
                    onMessage(history.last())
                    finishRun(false, repeatError)
                    return AgentTurnResult(ok = false, message = repeatError, toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId, durationMs = System.currentTimeMillis() - startedAtMs, llmWaitMs = llmWaitMs, toolExecMs = toolExecMs, verifyMs = verifyMs, rounds = rounds)
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
                val decision = safety.decide(tool, args, latestUiPackage)
                trace.log(runId, "safety_evaluate", mapOf(
                    "tool" to tool.name,
                    "args" to args,
                    "level" to decision.level.name,
                    "requiresConfirmation" to decision.requiresConfirmation,
                    "blocked" to decision.blocked,
                    "reason" to decision.reason,
                ))
                val sensitiveRequest = SensitiveActionRequest(tool.name, args)
                if (decision.requiresConfirmation) {
                    safetyStatsByRun[runId]?.let { it.confirmations++ }
                    setState(AgentRunState.WAITING_CONFIRM)
                    val approved = withTimeoutOrNull(runPolicy.sensitiveConfirmTimeoutMs) {
                        onSensitiveAction(sensitiveRequest)
                    } ?: false
                    safetyStatsByRun[runId]?.let {
                        if (approved) it.approvals++ else it.denials++
                    }
                    setState(AgentRunState.RUNNING)
                    trace.log(runId, "safety_decision", mapOf(
                        "tool" to tool.name,
                        "args" to args,
                        "level" to decision.level.name,
                        "approved" to approved,
                        "approvedBy" to if (approved) "user" else "user_denied",
                        "reason" to decision.reason,
                    ))
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
                        val declinedResult = ToolResult.failure(declined)
                        allToolResults += declinedResult
                        allSteps += StepExecution(
                            index = allSteps.size,
                            call = ToolCall(toolCall.name, args),
                            result = declinedResult,
                        )
                        continue
                    }
                }

                if (decision.blocked) {
                    safetyStatsByRun[runId]?.let { it.blocks++ }
                    val blocked = "系统安全拦截：不允许直接执行最终操作（${safety.describe(tool.name, args)}）。${decision.reason.ifBlank { "请停在确认页等待用户。" }}"
                    onStep(
                        AgentStepUi(
                            index = stepIndex,
                            runId = runId,
                            toolName = toolCall.name,
                            argsText = args.toString(),
                            status = AgentStepStatus.FAILED,
                            message = blocked,
                        ),
                    )
                    trace.log(runId, "tool_blocked", mapOf("tool" to toolCall.name, "args" to args, "reason" to "hard_safety_gate"))
                    trace.log(runId, "safety_blocked", mapOf(
                        "tool" to tool.name,
                        "args" to args,
                        "level" to decision.level.name,
                        "reason" to "hard_safety_gate",
                    ))
                    history += AgentMessage(
                        role = "tool",
                        content = blocked,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        toolArgs = toolCall.arguments,
                        toolResultOk = false,
                    )
                    onMessage(history.last())
                    allToolCalls += ToolCall(toolCall.name, args)
                    val blockedResult = ToolResult.failure(blocked)
                    allToolResults += blockedResult
                    allSteps += StepExecution(
                        index = allSteps.size,
                        call = ToolCall(toolCall.name, args),
                        result = blockedResult,
                    )
                    setState(AgentRunState.FAILED)
                    history += AgentMessage("assistant", "执行失败：$blocked")
                    onMessage(history.last())
                    finishRun(false, blocked)
                    return AgentTurnResult(
                        ok = false,
                        message = blocked,
                        toolCalls = allToolCalls, toolResults = allToolResults.toList(),
                        history = historySnapshot(),
                        runId = runId,
                        durationMs = System.currentTimeMillis() - startedAtMs,
                        llmWaitMs = llmWaitMs,
                        toolExecMs = toolExecMs,
                        verifyMs = verifyMs,
                        rounds = rounds,
                        state = AgentRunState.FAILED,
                    )
                }

                val toolStartMs = System.currentTimeMillis()
                trace.log(runId, "tool_call", mapOf(
                    "round" to round,
                    "tool" to toolCall.name,
                    "args" to args,
                    "start_elapsed_ms" to toolStartMs - startedAtMs,
                ))
                var result = try {
                    withTimeout(runPolicy.toolTimeoutMs) { tool.execute(args) }
                } catch (e: TimeoutCancellationException) {
                    ToolResult.failure("工具 ${toolCall.name} 执行超时（${runPolicy.toolTimeoutMs / 1000}s）")
                } catch (e: Exception) {
                    ToolResult.failure(e.message ?: e.javaClass.simpleName)
                }
                val toolEndMs = System.currentTimeMillis()
                val toolDurationMs = toolEndMs - toolStartMs
                toolExecMs += toolDurationMs
                roundToolExecMs += toolDurationMs
                val verificationSpec = AgentVerificationMatrix.specFor(toolCall.name)
                val evidenceField = verificationSpec.evidenceField
                val evidenceValue = evidenceField?.let { result.data[it] }
                val hasVerificationEvidence = when (verificationSpec.requirement) {
                    VerificationRequirement.FOREGROUND,
                    VerificationRequirement.TASK_CREATED -> evidenceValue == true
                    else -> evidenceField == null || result.data.containsKey(evidenceField)
                }
                if (result.ok && !hasVerificationEvidence &&
                    (verificationSpec.requirement == VerificationRequirement.FOREGROUND ||
                        verificationSpec.requirement == VerificationRequirement.TASK_CREATED)
                ) {
                    val reason = when (verificationSpec.requirement) {
                        VerificationRequirement.FOREGROUND -> "缺少前台包名/目标页面证据"
                        VerificationRequirement.TASK_CREATED -> "缺少任务已保存并调度的证据"
                        else -> "缺少验证证据"
                    }
                    result = result.copy(
                        ok = false,
                        message = "${result.message}（系统验证失败：$reason）",
                    )
                    trace.log(runId, "verification_rejected", mapOf(
                        "tool" to toolCall.name,
                        "requirement" to verificationSpec.requirement.name,
                        "evidence_field" to (evidenceField ?: ""),
                        "message" to result.message,
                    ))
                }
                onStep(
                    AgentStepUi(
                        index = stepIndex,
                        runId = runId,
                        toolName = toolCall.name,
                        argsText = args.toString(),
                        status = if (result.ok) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
                        message = result.message,
                        durationMs = toolDurationMs,
                        gapBeforeMs = gapBeforeMs,
                        startedAtElapsedMs = toolStartElapsedMs,
                    ),
                )
                if (result.ok) consecutiveFailures = 0 else consecutiveFailures++
                trace.log(
                runId,
                    "tool_result",
                    mapOf(
                        "round" to round,
                        "tool" to toolCall.name,
                        "ok" to result.ok,
                        "message" to result.message,
                        "data_keys" to result.data.keys.toList(),
                        "timing_ms" to (result.data["timingMs"] ?: emptyMap<Any, Any>()),
                        "duration_ms" to toolDurationMs,
                        "start_elapsed_ms" to toolStartMs - startedAtMs,
                        "end_elapsed_ms" to toolEndMs - startedAtMs,
                        "verification" to verificationSpec.requirement.name,
                        "verification_desc" to verificationSpec.description,
                        "verification_evidence_present" to (
                            verificationSpec.evidenceField == null ||
                                result.data.containsKey(verificationSpec.evidenceField)
                            ),
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
                if (toolCall.name in setOf("read_ui", "get_screen_state", "read_screen") && result.message.isNotBlank()) {
                    latestUiEvidence = result.message.take(2000)
                    latestUiPackage = (result.data["foregroundPackage"] as? String)?.takeIf { it.isNotBlank() }
                }
                allToolCalls += ToolCall(toolCall.name, args)
                allToolResults += result
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

                // Phase 1.5 动作验证：普通点击用快速 read_ui 验证，只有敏感操作才用截图。
                if (result.data["image_base64"] == null &&
                    tool.metadata.requiresAutoVerify &&
                    verifyPolicy.enabled &&
                    autoVerifyCount < verifyPolicy.maxPerRun &&
                    System.currentTimeMillis() - lastAutoVerifyAt >= verifyPolicy.minIntervalMs
                ) {
                    val verifyToolName = if (tool.metadata.risk == ToolRisk.SENSITIVE) "read_screen" else "read_ui"
                    autoVerifyCount++
                    lastAutoVerifyAt = System.currentTimeMillis()
                    val verifyStepIndex = allSteps.size
                    val verifyStartMs = System.currentTimeMillis()
                    val verifyStartElapsedMs = System.currentTimeMillis() - startedAtMs
                    onStep(
                        AgentStepUi(
                            index = verifyStepIndex,
                            runId = runId,
                            toolName = verifyToolName,
                            argsText = "{\"autoVerify\":true}",
                            status = AgentStepStatus.RUNNING,
                            startedAtElapsedMs = verifyStartElapsedMs,
                        ),
                    )
                    val verify = runCatching { toolRegistry.get(verifyToolName)?.execute(emptyMap()) }.getOrNull()
                    val verifyDurationMs = System.currentTimeMillis() - verifyStartMs
                    val verifyImage = verify?.data?.get("image_base64") as? String
                    if (verify?.ok == true && !verifyImage.isNullOrBlank()) {
                        latestScreenBase64 = verifyImage
                        latestScreenWidth = (verify.data["width"] as? Number)?.toInt()
                        latestScreenHeight = (verify.data["height"] as? Number)?.toInt()
                        val path = trace.saveScreenshot(runId, verifyImage, "auto_verify_${toolCall.name}")
                        trace.log(runId, "auto_verify", mapOf("tool" to toolCall.name, "path" to path, "base64_length" to verifyImage.length))
                    }
                    val verifyResult = verify ?: ToolResult.failure("自动验证失败")
                    val verifyLabel = if (verifyToolName == "read_screen") "自动截屏验证" else "自动UI验证"
                    onStep(
                        AgentStepUi(
                            index = verifyStepIndex,
                            runId = runId,
                            toolName = verifyToolName,
                            argsText = "{\"autoVerify\":true}",
                            status = if (verifyResult.ok) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
                            message = if (verifyResult.ok) verifyLabel else verifyResult.message,
                            durationMs = verifyDurationMs,
                            gapBeforeMs = (verifyStartElapsedMs - lastStepEndElapsedMs).coerceAtLeast(0),
                            startedAtElapsedMs = verifyStartElapsedMs,
                        ),
                    )
                    onMessage(
                        AgentMessage(
                            role = "tool",
                            content = if (verifyResult.ok) "$verifyLabel（系统自动执行 $verifyToolName）" else verifyResult.message,
                            toolName = verifyToolName,
                            toolArgs = "{\"autoVerify\":true}",
                            toolResultOk = verifyResult.ok,
                            durationMs = verifyDurationMs,
                        ),
                    )
                    allSteps += StepExecution(
                        index = verifyStepIndex,
                        call = ToolCall(verifyToolName, mapOf("autoVerify" to true)),
                        result = verifyResult,
                    )
                    verifyMs += verifyDurationMs
                    roundVerifyMs += verifyDurationMs
                }

                lastStepEndElapsedMs = System.currentTimeMillis() - startedAtMs
                if (control.cancelled) {
                    setState(AgentRunState.CANCELLED)
                    history += AgentMessage("assistant", "已停止")
                    finishRun(false, "已停止")
                    return AgentTurnResult(ok = false, message = "已停止", toolCalls = allToolCalls, toolResults = allToolResults.toList(), history = historySnapshot(), runId = runId)
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
                pruneStaleImages()
                onMessage(history.last())
            }

            val roundEndMs = System.currentTimeMillis()
            val roundTotalMs = roundEndMs - roundStartMs
            val otherMs = (roundTotalMs - roundLlmWaitMs - roundToolExecMs - roundVerifyMs).coerceAtLeast(0)
            trace.log(
                runId,
                "round_timing",
                mapOf(
                    "round" to round,
                    "total_ms" to roundTotalMs,
                    "llm_wait_ms" to roundLlmWaitMs,
                    "llm_ttft_ms" to response.ttftMs,
                    "llm_thinking_ms" to response.thinkingMs,
                    "llm_output_ms" to response.outputMs,
                    "tool_exec_ms" to roundToolExecMs,
                    "verify_ms" to roundVerifyMs,
                    "other_ms" to otherMs,
                    "tool_calls" to response.toolCalls.size,
                ),
            )
        }

        val summary = allSteps.joinToString("\n") { step ->
            val status = if (step.result.ok) "OK" else "FAIL"
            "$status ${step.call.tool}(${step.call.args}) -> ${step.result.message}"
        }
        trace.log(runId, "run_finished", mapOf("ok" to allSteps.all { it.result.ok }, "message" to summary.ifBlank { "达到最大轮数" }, "tool_call_count" to allToolCalls.size, "duration_ms" to (System.currentTimeMillis() - startedAtMs)))
        return AgentTurnResult(
            ok = allSteps.all { it.result.ok },
            message = summary.ifBlank { "达到最大轮数" },
            toolCalls = allToolCalls, toolResults = allToolResults.toList(),
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

    private fun historyForLlm(): List<AgentMessage> {
        val messages = history.toList()
        val imageIndices = messages.mapIndexedNotNull { index, msg ->
            if (msg.imageBase64 != null) index else null
        }
        if (imageIndices.size <= MAX_IMAGE_HISTORY) return messages
        val keep = imageIndices.takeLast(MAX_IMAGE_HISTORY).toSet()
        return messages.filterIndexed { index, msg ->
            msg.imageBase64 == null || index in keep
        }
    }

    private fun pruneStaleImages() {
        val imageIndices = history.mapIndexedNotNull { index, msg ->
            if (msg.imageBase64 != null) index else null
        }
        if (imageIndices.size <= MAX_IMAGE_HISTORY) return
        val keep = imageIndices.takeLast(MAX_IMAGE_HISTORY).toSet()
        val pruned = history.filterIndexed { index, msg ->
            msg.imageBase64 == null || index in keep
        }
        history.clear()
        history.addAll(pruned)
    }

    companion object {
        private const val MAX_IMAGE_HISTORY = 2
    }

    private fun buildSystemPrompt(skills: List<AgentSkill> = emptyList()): String {
        val toolDesc = toolRegistry.modelDescriptions()
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
                    if (skill.requiredCapabilities.isNotEmpty()) {
                        appendLine("   所需能力：${skill.requiredCapabilities.joinToString("、")}")
                    }
                    appendLine("   历史成功步骤（每一步都可能因当前版本/页面变化而失效，必须重新验证，禁止直接照抄坐标/文案）：")
                    skill.steps.forEach { step ->
                        val line = buildString {
                            append("     - ")
                            append(step.toolName)
                            if (step.args.isNotBlank()) append(" ").append(step.args.take(100))
                            if (step.purpose.isNotBlank()) append("  // ").append(step.purpose)
                        }
                        appendLine(line)
                        if (step.expected.isNotBlank()) appendLine("       预期：${step.expected}")
                        if (step.verification.isNotBlank()) appendLine("       验证：${step.verification}")
                        if (step.fallback.isNotBlank()) appendLine("       兜底：${step.fallback}")
                    }
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
            
            【单步快路径】
            - 单步、明确、可直接执行的指令（打开某个 App、打开搜索、创建提醒、打开链接）必须只调用一个对应的工具就结束，不要创建 task_plan，不要写计划步骤。
            - 示例：“打开企业微信” → 只调 open_app{"package":"com.tencent.wework"}；“提醒我8点喝水” → 只调 create_reminder；“搜索 Android 15” → 只调 open_search。
            - 不要为简单任务创建 task_plan；task_plan 只用于真正多步、模糊、跨 App 或需要持续跟踪步骤的复杂任务。
            - 需要用户确认或决策时调用 wait_user（或 task_plan wait_user）暂停，然后停止等待；不要假装完成。
            
            【复杂任务】
            - 只有多步/模糊/跨 App 任务才先用 task_plan 创建步骤计划；每完成一步用 task_plan 标记；需要用户确认时用 wait_user 暂停，再停止等待。
            - 查找应用时优先使用 find_app，不要用 run_shell 列举全部包名。
            - 为节省时间，默认使用 read_ui 获取文字和坐标（它也返回弹窗提示），不要每步都调用 get_screen_state / read_screen。
            - get_screen_state 默认不包含截图，只返回 UI 树/坐标/弹窗提示，速度更快；只有需要看图片、图标位置、视觉布局时才传 includeImage:true 或使用 read_screen。每个页面最多只做 1 次视觉看图，不要连续多次 includeImage:true。
            - 同一页面连续操作时，沿用上一次 UI 树/坐标即可，不要重复读取屏幕；只有操作后页面疑似没变化、或进入新页面时才验证。
            - 点击/操作失败或页面没变化时，先 wait 500ms 再快速 read_ui 确认，不要连续 get_screen_state。
            - 菜单/分类/商品选择优先使用 tap_text 或搜索，不要反复用 tap 猜位置；搜索一次没有结果就返回菜单，不要反复搜索同一关键词。
            - 点击有明确文字的按钮/条目时，直接使用 tap_text，不要根据截图估计坐标，也不要为每个按钮先 review_tap；tap 只用于纯图标、无文字且坐标能确定的节点。
            - 当检测到营销/更新/广告弹窗时，立即调用 dismiss_popups，不要自己用 tap 猜关闭按钮坐标；弹窗未关闭前不要继续点击页面内容。
            - 当遇到功能性选择层（门店选择、商品选择、规格选择）时，不要调用 dismiss_popups，使用 tap_text 选择目标。
            - 遇到系统权限弹窗（定位/通知/相机/存储等）且任务需要该权限时，选择“允许 / 仅在使用期间允许 / 始终允许”；不要选“始终拒绝”，否则会绕到手动选择/城市选择等更慢流程。
            - 遇到“定位失败/无法定位”时优先尝试“重新定位”或返回重试；不要进入手动城市/门店长列表去翻城市，除非万不得已。
            - get_screen_state/read_ui 返回的 overlay 字段会提示弹窗类型和关闭按钮中心坐标，优先使用这些坐标。
            - dismiss_popups 成功关闭弹窗后，不要立刻再获取 get_screen_state；可直接基于上一个已知页面继续，或至多用 read_ui 确认。
            - 启动 App 后不要固定等待 2.5 秒；先 short wait 或直接 read_ui 确认页面状态，减少不必要延迟。
            - 操作后如果工具结果已经明确成功，不需要截图确认；需要确认新页面时才获取屏幕状态。
            - 网页搜索优先使用 open_search 直接打开搜索引擎结果页（如 baidu），避免在浏览器输入框手动输入中文；只有需要点击搜索按钮或打开具体结果时才进入浏览器操作。
            - 创建日历事件时优先使用 create_calendar_event 直接打开预填好的新建事件页，然后点击保存；不要手动在日历里反复点“+”。
            - 涉及下单/支付/购买等敏感操作时，可以点击“立即购买/去结算/去下单”进入确认订单页，但到达“确认订单页”后必须停止，不要点击“提交订单/确认支付/立即支付/付款/确认下单”等最终支付按钮。
            - 在确认订单/结算/支付页上，如果存在“换购/加购/免密支付/优惠”等浮层或弹窗，并且有 X/关闭按钮，应先调用 dismiss_popups 或 tap_text 关闭这个浮层，然后再结束；这属于清理页面，不等于提交订单。
            - 忽略无关的营销活动、优惠券领取、弹窗引导，除非用户明确要求。
            - 在聊天输入框中输入完成后，优先使用 press_key {"key":"enter"} 发送；如果无效再点击界面上的发送按钮。
            - 如果 read_ui/get_screen_state 因微信等 App 不暴露无障碍节点而失败，应改用 read_screen 看图，再按网格数字代表的原始屏幕坐标调用 tap 操作，不要因为 read_ui 失败就放弃。
            - 输入文字后必须用 read_screen 核对输入框是否真的出现了文字；如果 input_text 返回成功但截图仍为空，不要当作成功，尝试点击输入框下方的剪贴板候选词（通常是你要输入的整句），或长按输入框后选择“粘贴”。
            - 在聊天发送前最后一步，确认消息已显示在输入框中即可停止；不要点击“发送”或按回车发送。
            - 如果聊天输入框已显示目标消息且屏幕上出现 Send/发送按钮，必须调用 wait_user 暂停并说明“已停在发送前最后一步”，绝不能直接回复“已完成”。
            - 完成用户目标后，用简短中文总结结果。
            - 只有实际收到过屏幕截图（包括自动验证截图）时，才可以说“从截图/画面中看到”；没有截图时只能基于 UI 树和工具结果描述，不得声称看过截图。
            - 不要编造工具执行结果或截图内容。
        """.trimIndent()
    }
}

private fun AgentTurnResult.withSafetyStats(stats: SafetyRunStats): AgentTurnResult = copy(
    safetyConfirmations = stats.confirmations,
    safetyApprovals = stats.approvals,
    safetyDenials = stats.denials,
    safetyBlocks = stats.blocks,
)

data class AgentTurnResult(
    val ok: Boolean,
    val message: String,
    val toolCalls: List<ToolCall>,
    val toolResults: List<ToolResult> = emptyList(),
    val history: List<AgentMessage>,
    val runId: String = "",
    val durationMs: Long = 0,
    val llmWaitMs: Long = 0,
    val toolExecMs: Long = 0,
    val verifyMs: Long = 0,
    val rounds: Int = 0,
    val state: AgentRunState = AgentRunState.DONE,
    val plan: TaskPlan? = null,
    val safetyConfirmations: Int = 0,
    val safetyApprovals: Int = 0,
    val safetyDenials: Int = 0,
    val safetyBlocks: Int = 0,
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
    val sensitiveConfirmTimeoutMs: Long = 90_000L,
    val maxVisualReadsPerRun: Int = 6,
)
