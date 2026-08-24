package com.voiceconfig.app.agent

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直接创建一条定时提醒任务。
 *
 * 这是“单步快路径”的核心工具之一：模型只需调用一次本工具，
 * 就会完成解析时间、保存任务、注册闹钟，不需要创建 TaskPlan。
 */
@Singleton
class CreateReminderTool @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskScheduler: TaskScheduler,
    private val nextRunCalculator: NextRunCalculator,
) : AgentTool {

    override val name: String = "create_reminder"
    override val description: String =
        "创建一条定时提醒。参数：{\"content\":\"喝水\",\"time\":\"08:00\"}；" +
            "可选 {\"date\":\"today|tomorrow|yyyy-MM-dd\",\"scheduleType\":\"ONCE|DAILY|WEEKLY|INTERVAL\"," +
            "\"daysOfWeek\":[\"MONDAY\"],\"intervalMinutes\":30,\"timeText\":\"明早8点\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val content = (args["content"] ?: args["text"] ?: args["title"])
            ?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 content（提醒内容）")

        var schedule = AgentScheduleParser.parse(args) ?: return ToolResult.failure(
            "无法解析提醒时间，请提供 time（HH:mm）或 timeText（如“明早8点”）",
            mapOf("content" to content),
        )

        if (nextRunCalculator.nextRunAfter(schedule) == null &&
            schedule.type == ScheduleSpec.ScheduleType.ONCE &&
            schedule.date == LocalDate.now()
        ) {
            // “8点提醒我喝水”若今天已过该时间，按日常习惯顺延到明天。
            schedule = schedule.copy(date = LocalDate.now().plusDays(1))
        }
        val nextRunAt = nextRunCalculator.nextRunAfter(schedule)
            ?.atZone(java.time.ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
        if (nextRunAt == null) {
            return ToolResult.failure(
                "提醒时间已过或无法计算下次执行时间",
                mapOf("content" to content, "schedule" to schedule.toString()),
            )
        }

        val now = System.currentTimeMillis()
        val task = Task(
            rawText = "提醒：$content",
            title = "提醒：${content.take(24)}",
            schedule = schedule,
            actionType = ActionType.NOTIFY,
            executionMode = ExecutionMode.NOTIFICATION,
            nextRunAtEpochMillis = nextRunAt,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )

        val taskId = runCatching { taskRepository.saveTask(task) }
            .getOrElse { return ToolResult.failure("保存提醒失败：${it.message}") }
        val withId = task.copy(id = taskId)
        runCatching { taskScheduler.schedule(withId) }
            .getOrElse {
                // 调度失败时回滚已保存的提醒，避免出现“存在但不会执行”的脏任务。
                runCatching { taskRepository.deleteTask(taskId) }
                return ToolResult.failure("注册提醒失败：${it.message}")
            }

        val scheduleText = AgentScheduleParser.describe(schedule)
        return ToolResult.success(
            "已创建提醒：$content（$scheduleText）",
            mapOf(
                "taskId" to taskId,
                "content" to content,
                "scheduleType" to schedule.type.name,
                "schedule" to schedule.toString(),
                "nextRunAtEpochMillis" to nextRunAt,
                "verified" to true,
            ),
        )
    }
}
