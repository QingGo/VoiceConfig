package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务计划管理工具：让 LLM 显式地维护“当前目标”的执行计划。
 *
 * 作用：
 * - create：把大目标拆成可跟踪步骤；
 * - update：标记某一步完成/失败/阻塞；
 * - wait_user：到达需要用户决策的点，暂停等待；
 * - get：查看当前计划。
 *
 * 这个工具和 StopVerifier 配合后，Agent 才有“可靠的停止条件”，
 * 而不是仅仅靠模型说一句“已完成”。
 */
@Singleton
class TaskPlanTool @Inject constructor(
    private val store: TaskPlanStore,
) : AgentTool {

    override val name: String = "task_plan"
    override val description: String =
        "管理当前任务的执行计划。可执行 action：create（创建计划，steps 为字符串数组）、" +
            "update（更新步骤状态，status 取 PENDING/IN_PROGRESS/COMPLETED/FAILED/BLOCKED/SKIPPED）、" +
            "wait_user（暂停等待用户确认，reason 为原因）、pause（暂停）、resume（继续）、cancel（取消）、" +
            "get（查看当前计划）。复杂任务应先用 create 建计划，再逐步 update，最后在需要用户确认时 wait_user。"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: return ToolResult.failure("task_plan 需要 action")
        return when (action) {
            "create" -> create(args)
            "update" -> update(args)
            "wait_user" -> waitUser(args)
            "pause" -> pause()
            "resume" -> resume()
            "cancel" -> cancel()
            "get" -> getPlan()
            else -> ToolResult.failure("未知 action：$action")
        }
    }

    private suspend fun create(args: Map<String, Any?>): ToolResult {
        val goal = (args["goal"] as? String)?.trim().takeUnless { it.isNullOrBlank() }
            ?: return ToolResult.failure("create 需要 goal")
        val rawSteps = args["steps"] as? List<*> ?: emptyList<Any?>()
        val steps = rawSteps.mapIndexedNotNull { index, raw ->
            val title = when (raw) {
                is String -> raw.trim()
                is Map<*, *> -> (raw["title"] as? String)?.trim()
                else -> null
            }?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            TaskStep(
                id = "step_${index + 1}",
                title = title,
                status = TaskStepStatus.PENDING,
            )
        }
        store.set(TaskPlan(goal = goal, steps = steps.toMutableList()))
        store.saveCurrent()
        return ToolResult.success(
            if (steps.isEmpty()) "已创建任务计划：$goal（未拆分步骤）" else "已创建任务计划：$goal，共 ${steps.size} 步",
            mapOf("plan" to store.snapshot()?.let(::planToText)),
        )
    }

    private suspend fun update(args: Map<String, Any?>): ToolResult {
        val plan = store.snapshot() ?: return ToolResult.failure("当前没有任务计划，请先 create")
        val stepId = (args["stepId"] as? String)?.trim()
            ?: return ToolResult.failure("update 需要 stepId")
        val statusText = (args["status"] as? String)?.trim()?.uppercase()
            ?: return ToolResult.failure("update 需要 status")
        val status = runCatching { TaskStepStatus.valueOf(statusText) }
            .getOrElse { return ToolResult.failure("未知步骤状态：$statusText") }
        val evidence = (args["evidence"] as? String)?.trim().orEmpty()
        val note = (args["note"] as? String)?.trim().orEmpty()
        val index = plan.steps.indexOfFirst { it.id == stepId }
        if (index < 0) return ToolResult.failure("未找到步骤：$stepId")
        store.update { p ->
            val newSteps = p.steps.toMutableList()
            val old = newSteps[index]
            newSteps[index] = old.copy(status = status, evidence = evidence, note = note)
            p.copy(steps = newSteps)
        }
        store.saveCurrent()
        val updated = store.snapshot()!!
        return ToolResult.success(
            "已更新步骤 $stepId 为 $status",
            mapOf("plan" to planToText(updated)),
        )
    }

    private suspend fun waitUser(args: Map<String, Any?>): ToolResult {
        val reason = (args["reason"] as? String)?.trim().orEmpty().ifBlank { "需要用户确认" }
        store.update { it.copy(waitingForHuman = reason, status = TaskPlanStatus.WAITING_CONFIRM) }
        store.saveCurrent()
        return ToolResult.success(
            "已暂停等待用户确认：$reason。请停止继续操作，向用户说明当前状态并等待确认。",
            mapOf("reason" to reason, "plan" to store.snapshot()?.let(::planToText)),
        )
    }

    private suspend fun pause(): ToolResult {
        val plan = store.snapshot() ?: return ToolResult.failure("当前没有任务计划")
        store.update {
            it.copy(
                waitingForHuman = "已暂停",
                status = TaskPlanStatus.WAITING_CONFIRM,
            )
        }
        store.saveCurrent()
        return ToolResult.success("任务已暂停，等待用户继续", mapOf("plan" to store.snapshot()?.let(::planToText)))
    }

    private suspend fun resume(): ToolResult {
        val plan = store.snapshot() ?: return ToolResult.failure("当前没有任务计划")
        store.update {
            it.copy(
                waitingForHuman = null,
                status = TaskPlanStatus.ACTIVE,
            )
        }
        store.saveCurrent()
        return ToolResult.success("任务已恢复，可以继续执行", mapOf("plan" to store.snapshot()?.let(::planToText)))
    }

    private suspend fun cancel(): ToolResult {
        val plan = store.snapshot() ?: return ToolResult.failure("当前没有任务计划")
        store.update {
            it.copy(
                waitingForHuman = null,
                status = TaskPlanStatus.CANCELLED,
            )
        }
        store.saveCurrent()
        return ToolResult.success("任务已取消", mapOf("plan" to store.snapshot()?.let(::planToText)))
    }


    private fun getPlan(): ToolResult {
        val plan = store.snapshot() ?: return ToolResult.success("当前没有任务计划", mapOf("plan" to null))
        return ToolResult.success(planToText(plan), mapOf("plan" to planToText(plan)))
    }

    private fun planToText(plan: TaskPlan): String = buildString {
        appendLine("目标：${plan.goal}")
        if (plan.waitingForHuman != null) appendLine("等待用户确认：${plan.waitingForHuman}")
        if (plan.steps.isEmpty()) {
            appendLine("步骤：无")
        } else {
            appendLine("步骤：")
            plan.steps.forEach { step ->
                appendLine("- [${step.status}] ${step.id}: ${step.title}" +
                    if (step.evidence.isNotBlank()) "（证据：${step.evidence}）" else "")
            }
        }
    }.trim()
}
