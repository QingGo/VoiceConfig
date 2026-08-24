package com.voiceconfig.app.agent

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import java.net.URLEncoder
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用定时任务创建工具。
 *
 * 用于把“每天8点打开企业微信”“明天9点打开百度搜索”等带时间的自然语言
 * 转成真正持久化并调度的自动化任务。这样所有自然语言入口都能统一通过
 * 云 LLM + Function Calling 完成，无需本地解析器做意图判断。
 */
@Singleton
class CreateScheduledTaskTool @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskScheduler: TaskScheduler,
    private val nextRunCalculator: NextRunCalculator,
) : AgentTool {

    override val name: String = "create_scheduled_task"
    override val description: String =
        "创建带时间的定时任务，支持打开App、打开DeepLink、网页搜索、提醒。参数：" +
            "{\"action\":\"open_app|open_deeplink|open_search|remind\",\"package\":\"com.tencent.wework\"," +
            "\"deepLink\":\"https://...\",\"query\":\"关键词\",\"content\":\"提醒内容\"," +
            "\"scheduleType\":\"ONCE|DAILY|WEEKLY|INTERVAL\",\"time\":\"08:00\",\"date\":\"tomorrow\"," +
            "\"timeText\":\"每天8点\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val action = (args["action"] ?: args["type"] ?: args["taskType"])
            ?.toString()?.trim()?.lowercase()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 action（open_app/open_deeplink/open_search/remind）")

        var schedule = AgentScheduleParser.parse(args) ?: return ToolResult.failure(
            "无法解析定时时间，请提供 time（HH:mm）、timeText 或 intervalMinutes",
        )
        if (nextRunCalculator.nextRunAfter(schedule) == null &&
            schedule.type == ScheduleSpec.ScheduleType.ONCE &&
            schedule.date == LocalDate.now()
        ) {
            schedule = schedule.copy(date = LocalDate.now().plusDays(1))
        }
        val nextRunAt = nextRunCalculator.nextRunAfter(schedule)
            ?.atZone(java.time.ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
        if (nextRunAt == null) {
            return ToolResult.failure(
                "定时时间已过或无法计算下次执行时间",
                mapOf("action" to action, "schedule" to schedule.toString()),
            )
        }

        val task = when (action) {
            "remind", "reminder", "notify" -> buildReminder(args, schedule, nextRunAt)
            "open_app", "open", "launch" -> buildOpenApp(args, schedule, nextRunAt)
            "open_deeplink", "deeplink", "open_link", "link" -> buildDeepLink(args, schedule, nextRunAt)
            "open_search", "search" -> buildSearch(args, schedule, nextRunAt)
            else -> return ToolResult.failure("不支持的定时任务类型：$action")
        } ?: return ToolResult.failure("定时任务参数不完整")

        val taskId = runCatching { taskRepository.saveTask(task) }
            .getOrElse { return ToolResult.failure("保存定时任务失败：${it.message}") }
        val withId = task.copy(id = taskId)
        runCatching { taskScheduler.schedule(withId) }
            .getOrElse {
                runCatching { taskRepository.deleteTask(taskId) }
                return ToolResult.failure("注册定时任务失败：${it.message}")
            }

        return ToolResult.success(
            "已创建定时任务：${task.title}（${AgentScheduleParser.describe(schedule)}）",
            mapOf(
                "taskId" to taskId,
                "action" to task.actionType.name,
                "scheduleType" to schedule.type.name,
                "schedule" to schedule.toString(),
                "nextRunAtEpochMillis" to nextRunAt,
                "verified" to true,
            ),
        )
    }

    private fun buildReminder(
        args: Map<String, Any?>,
        schedule: ScheduleSpec,
        nextRunAt: Long,
    ): Task? {
        val content = (args["content"] ?: args["text"] ?: args["title"])
            ?.toString()?.trim()?.ifBlank { null } ?: return null
        val now = System.currentTimeMillis()
        return Task(
            rawText = "提醒：$content",
            title = "提醒：${content.take(24)}",
            schedule = schedule,
            actionType = ActionType.NOTIFY,
            executionMode = ExecutionMode.NOTIFICATION,
            nextRunAtEpochMillis = nextRunAt,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private fun buildOpenApp(
        args: Map<String, Any?>,
        schedule: ScheduleSpec,
        nextRunAt: Long,
    ): Task? {
        val packageName = (args["package"] ?: args["pkg"] ?: args["app"])
            ?.toString()?.trim()?.ifBlank { null } ?: return null
        val activity = args["activity"]?.toString()?.trim()?.ifBlank { null }
        val title = (args["title"] ?: args["name"] ?: "打开 $packageName")?.toString()?.trim()
            ?.ifBlank { null } ?: "打开 $packageName"
        val now = System.currentTimeMillis()
        return Task(
            rawText = "定时打开：$title",
            title = title.take(30),
            schedule = schedule,
            actionType = ActionType.OPEN_APP,
            targetPackage = packageName,
            targetActivity = activity,
            executionMode = ExecutionMode.AUTO,
            nextRunAtEpochMillis = nextRunAt,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private fun buildDeepLink(
        args: Map<String, Any?>,
        schedule: ScheduleSpec,
        nextRunAt: Long,
    ): Task? {
        val deepLink = (args["deepLink"] ?: args["url"] ?: args["link"])
            ?.toString()?.trim()?.ifBlank { null } ?: return null
        val title = (args["title"] ?: args["name"] ?: "打开链接")?.toString()?.trim()
            ?.ifBlank { null } ?: "打开链接"
        val now = System.currentTimeMillis()
        return Task(
            rawText = "定时打开：$title",
            title = title.take(30),
            schedule = schedule,
            actionType = ActionType.OPEN_DEEPLINK,
            deepLink = deepLink,
            executionMode = ExecutionMode.AUTO,
            nextRunAtEpochMillis = nextRunAt,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private fun buildSearch(
        args: Map<String, Any?>,
        schedule: ScheduleSpec,
        nextRunAt: Long,
    ): Task? {
        val query = (args["query"] ?: args["q"] ?: args["keyword"])
            ?.toString()?.trim()?.ifBlank { null } ?: return null
        val engine = (args["engine"]?.toString() ?: "baidu").lowercase()
        val url = buildSearchUrl(engine, query) ?: return null
        val now = System.currentTimeMillis()
        return Task(
            rawText = "定时搜索：$query",
            title = "定时搜索：${query.take(24)}",
            schedule = schedule,
            actionType = ActionType.OPEN_DEEPLINK,
            deepLink = url,
            executionMode = ExecutionMode.AUTO,
            nextRunAtEpochMillis = nextRunAt,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private fun buildSearchUrl(engine: String, query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return when (engine.lowercase()) {
            "baidu" -> "https://www.baidu.com/s?wd=$encoded"
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> null
        }
    }
}
