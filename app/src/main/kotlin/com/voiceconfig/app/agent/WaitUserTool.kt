package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 等待用户确认工具。
 *
 * 与 task_plan wait_user 等价，但暴露为独立核心工具，便于模型在“需要用户确认”
 * 时直接调用，不强制先创建 TaskPlan。
 */
@Singleton
class WaitUserTool @Inject constructor(
    private val store: TaskPlanStore,
    private val dismissPopupsTool: DismissPopupsTool,
) : AgentTool {

    override val name: String = "wait_user"
    override val description: String =
        "暂停当前任务并等待用户确认，参数：{\"reason\":\"需要用户确认支付\"}。调用后不要再继续操作，直接停止等待。"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        // 在暂停等待用户前，先尝试关闭确认/支付页上的额外浮层（如“一键换购”）。
        val dismissResult = runCatching { dismissPopupsTool.execute(emptyMap()) }.getOrNull()
        val dismissNote = if (dismissResult?.ok == true) {
            "；已关闭额外浮层：${dismissResult.message}"
        } else {
            ""
        }

        val reason = (args["reason"] ?: args["message"] ?: args["question"])
            ?.toString()?.trim()?.ifBlank { null }
            ?: "需要用户确认"

        val current = store.snapshot()
        if (current == null) {
            store.set(
                TaskPlan(
                    goal = reason,
                    waitingForHuman = reason,
                    status = TaskPlanStatus.WAITING_CONFIRM,
                ),
            )
        } else {
            store.update {
                it.copy(
                    waitingForHuman = reason,
                    status = TaskPlanStatus.WAITING_CONFIRM,
                )
            }
        }
        store.saveCurrent()
        return ToolResult.success(
            "已暂停等待用户确认：$reason$dismissNote。请停止继续操作，向用户说明当前状态并等待确认。",
            mapOf("reason" to reason, "dismissedOverlay" to (dismissResult?.ok == true)),
        )
    }
}
