package com.voiceconfig.app.agent

import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.nlp.TimeExpressionParser
import java.time.LocalDate
import java.time.LocalTime

/**
 * 供 Agent 工具共用：把模型传入的时间参数解析成 [ScheduleSpec]。
 *
 * 支持三类输入：
 * 1. `schedule` 对象：{type, time, date, daysOfWeek, intervalMinutes}
 * 2. 直接参数：{scheduleType, time, date, daysOfWeek, intervalMinutes}
 * 3. 中文时间表达：{timeText:"明早8点"} / {when:"每天10点"} / {at:"8点"}
 */
object AgentScheduleParser {

    private val timeParser = TimeExpressionParser()

    fun parse(args: Map<String, Any?>): ScheduleSpec? {
        // 1) 结构化的 schedule 对象
        (args["schedule"] as? Map<*, *>)?.let { raw ->
            parseStructured(raw, args)?.let { return it }
        }

        // 2) 直接参数
        parseStructured(args, args)?.let { return it }

        // 3) 原始中文时间表达（由模型转写或透传）
        val timeText = (args["timeText"] ?: args["when"] ?: args["at"])?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        if (timeText != null) {
            return timeParser.parse(timeText)
        }
        return null
    }

    private fun parseStructured(
        source: Map<*, *>,
        fallbackArgs: Map<String, Any?>,
    ): ScheduleSpec? {
        val typeRaw = (source["scheduleType"] ?: source["type"] ?: fallbackArgs["scheduleType"]
            ?: fallbackArgs["type"])?.toString()?.trim()?.uppercase()
        val timeRaw = (source["time"] ?: fallbackArgs["time"])?.toString()?.trim()
        val dateRaw = (source["date"] ?: fallbackArgs["date"])?.toString()?.trim()
        val intervalRaw = (source["intervalMinutes"] ?: fallbackArgs["intervalMinutes"])?.toString()?.toLongOrNull()
        val days = parseDays(source["daysOfWeek"] ?: fallbackArgs["daysOfWeek"])

        val time = parseTime(timeRaw)
        val type = if (typeRaw == null) {
            when {
                intervalRaw != null -> ScheduleSpec.ScheduleType.INTERVAL
                time != null && dateRaw != null -> ScheduleSpec.ScheduleType.ONCE
                time != null -> ScheduleSpec.ScheduleType.ONCE
                else -> null
            }
        } else {
            runCatching { ScheduleSpec.ScheduleType.valueOf(typeRaw) }.getOrNull()
        } ?: return null

        return when (type) {
            ScheduleSpec.ScheduleType.ONCE -> {
                val timeValue = time ?: return null
                val date = parseDate(dateRaw) ?: LocalDate.now()
                ScheduleSpec.once(date, timeValue)
            }
            ScheduleSpec.ScheduleType.DAILY -> {
                val timeValue = time ?: return null
                ScheduleSpec.daily(timeValue)
            }
            ScheduleSpec.ScheduleType.WEEKLY -> {
                val timeValue = time ?: return null
                if (days.isNullOrEmpty()) return null
                ScheduleSpec.weekly(timeValue, days)
            }
            ScheduleSpec.ScheduleType.INTERVAL -> {
                val interval = intervalRaw ?: return null
                if (interval <= 0) return null
                ScheduleSpec.interval(interval)
            }
        }
    }

    private fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalTime.parse(raw.trim()) }.getOrNull()
            ?: runCatching {
                val parts = raw.trim().split(":")
                LocalTime.of(parts[0].toInt(), parts.getOrElse(1) { "0" }.toInt())
            }.getOrNull()
            ?: timeParser.extractTime(raw)
    }

    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim().lowercase()) {
            "today", "今天", "今日" -> LocalDate.now()
            "tomorrow", "明天", "明日" -> LocalDate.now().plusDays(1)
            "后天" -> LocalDate.now().plusDays(2)
            else -> runCatching { LocalDate.parse(raw.trim()) }
                .getOrElse { null }
        }
    }

    private fun parseDays(raw: Any?): Set<java.time.DayOfWeek>? {
        if (raw == null) return null
        val values = when (raw) {
            is List<*> -> raw
            is String -> raw.split(",").map { it.trim() }
            else -> return null
        }
        val days = values.mapNotNull { value ->
            runCatching {
                java.time.DayOfWeek.valueOf(value.toString().trim().uppercase())
            }.getOrNull()
        }.toSet()
        return days.takeIf { it.isNotEmpty() }
    }

    fun describe(schedule: ScheduleSpec): String {
        val sb = StringBuilder()
        when (schedule.type) {
            ScheduleSpec.ScheduleType.ONCE ->
                sb.append("${schedule.date} ${schedule.time}")
            ScheduleSpec.ScheduleType.DAILY ->
                sb.append("每天 ${schedule.time}")
            ScheduleSpec.ScheduleType.WEEKLY ->
                sb.append("每周 ${schedule.daysOfWeek.joinToString("/") { it.name }} ${schedule.time}")
            ScheduleSpec.ScheduleType.INTERVAL ->
                sb.append("每 ${schedule.intervalMinutes} 分钟")
        }
        return sb.toString()
    }
}
