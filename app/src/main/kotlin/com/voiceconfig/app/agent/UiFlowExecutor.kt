package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 通用 UI 流程执行器。
 *
 * 执行 [FlowScript] 数据：
 * - 按条件匹配当前界面上的步骤；
 * - 执行 tap/tap_id/back/dismiss 等通用原语；
 * - 检测到终端页时自动写入 WAITING_CONFIRM 并停止。
 *
 * 这样各 App 的“点单/发送/控制”路径都只是数据，不再散落在专用工具的状态机里。
 */
@Singleton
class UiFlowExecutor @Inject constructor(
    private val uiActionLayer: UiActionLayer,
    private val openAppTool: OpenAppTool,
    private val dismissPopupsTool: DismissPopupsTool,
    private val taskPlanStore: TaskPlanStore,
) {

    suspend fun execute(
        script: FlowScript,
        goal: String = script.name,
        waitReason: String = "已到达 ${script.name} 的终端确认页，等待用户确认",
    ): FlowExecutionResult {
        script.openPackage?.let { pkg ->
            val opened = openAppTool.execute(mapOf("package" to pkg))
            if (!opened.ok) {
                return FlowExecutionResult(false, "无法打开 ${script.name}：${opened.message}")
            }
        }

        val used = mutableSetOf<String>()
        for (iteration in 0 until script.maxIterations) {
            delay(220)
            val ui = uiActionLayer.readUi(maxNodes = 120, maxChars = 3000)
            if (!ui.ok) {
                return FlowExecutionResult(false, "无法读取当前界面：${ui.error ?: ui.message}", summary = ui.summary.take(600))
            }
            val text = ui.summary

            // 终端页优先：无论当前执行到哪一步，到达终端就停。
            if (isTerminal(text, script.terminalMarkers)) {
                runCatching { dismissPopupsTool.execute(emptyMap()) }
                taskPlanStore.set(
                    TaskPlan(
                        goal = goal,
                        waitingForHuman = waitReason,
                        status = TaskPlanStatus.WAITING_CONFIRM,
                    ),
                )
                taskPlanStore.saveCurrent()
                return FlowExecutionResult(
                    ok = true,
                    message = "已到达终端确认页：$waitReason",
                    terminalStop = true,
                    summary = text.take(800),
                    foregroundPackage = ui.foregroundPackage,
                    reason = waitReason,
                )
            }

            val step = script.steps.firstOrNull { candidate ->
                candidate.id !in used && matches(candidate, text)
            } ?: continue

            val actionOk = executeAction(step.action)
            if (actionOk && step.once) {
                used += step.id
            }
        }

        return FlowExecutionResult(
            ok = false,
            message = "${script.name} 未能在限步内到达终端页，请交给 LLM 接管。",
            summary = uiActionLayer.readUi(80, 1200).summary,
        )
    }

    private fun matches(step: FlowStep, text: String): Boolean {
        if (step.whenContains.isNotEmpty() && step.whenContains.none { text.contains(it, ignoreCase = true) }) return false
        if (step.whenNotContains.any { text.contains(it, ignoreCase = true) }) return false
        return true
    }

    private fun isTerminal(text: String, markers: List<String>): Boolean {
        if (text.contains("立即更新", ignoreCase = true)) return false
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    private suspend fun executeAction(action: FlowAction): Boolean = when (action) {
        is FlowAction.TapText -> uiActionLayer.tapByText(*action.candidates.toTypedArray()).ok
        is FlowAction.TapId -> action.resourceIds.firstOrNull { id -> uiActionLayer.tapById(id).ok } != null
        FlowAction.Back -> uiActionLayer.back().ok
        FlowAction.DismissPopups -> runCatching { dismissPopupsTool.execute(emptyMap()) }.getOrNull()?.ok == true
        is FlowAction.TapTextOrBack -> {
            val tapped = uiActionLayer.tapByText(*action.candidates.toTypedArray()).ok
            if (tapped) true else uiActionLayer.back().ok
        }
    }
}
