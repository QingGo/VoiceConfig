package com.voiceconfig.app.agent

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 打开系统日历的“新建事件”页并预填标题/时间。
 *
 * 日历 App 的“+ 新建事件”在部分模拟器/无账号设备上会卡在 “Checking info...”，
 * 直接使用 Android 标准 CalendarContract ACTION_INSERT intent 打开预填编辑器，
 * 成功率更高且无需针对某个日历 App 硬编码 UI。
 */
@Singleton
class CreateCalendarEventTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "create_calendar_event"
    override val description: String = "打开系统日历新建事件页并预填标题/时间，参数：{\"title\":\"周会\",\"date\":\"tomorrow\",\"startHour\":15,\"startMinute\":0,\"durationMinutes\":60} 或 {\"title\":\"周会\",\"startTimeMs\":1234567890000}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val title = args["title"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 title")
        val durationMinutes = (args["durationMinutes"] as? Number)?.toInt()?.coerceIn(1, 24 * 60) ?: 60

        val startMs = (args["startTimeMs"] as? Number)?.toLong()
            ?: buildStartMillis(args)
            ?: return ToolResult.failure("缺少开始时间：请提供 startTimeMs 或 date/startHour/startMinute")

        val endMs = startMs + durationMinutes * 60_000L

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                putExtra(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(
                "已打开日历新建事件页并预填：$title $startMs-$endMs（${durationMinutes}分钟）",
                mapOf("title" to title, "startTimeMs" to startMs, "endTimeMs" to endMs, "durationMinutes" to durationMinutes),
            )
        } catch (e: Exception) {
            ToolResult.failure("打开日历新建事件页失败：${e.message}", mapOf("title" to title))
        }
    }

    private fun buildStartMillis(args: Map<String, Any?>): Long? {
        val dateRaw = args["date"]?.toString()?.trim()?.ifBlank { null } ?: return null
        val zone = ZoneId.systemDefault()
        val date = when (dateRaw.lowercase()) {
            "today" -> LocalDate.now(zone)
            "tomorrow" -> LocalDate.now(zone).plusDays(1)
            "后天" -> LocalDate.now(zone).plusDays(2)
            else -> runCatching { LocalDate.parse(dateRaw) }.getOrNull() ?: return null
        }
        val hour = (args["startHour"] as? Number)?.toInt()?.coerceIn(0, 23) ?: 9
        val minute = (args["startMinute"] as? Number)?.toInt()?.coerceIn(0, 59) ?: 0
        val time = LocalTime.of(hour, minute)
        return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }
}
