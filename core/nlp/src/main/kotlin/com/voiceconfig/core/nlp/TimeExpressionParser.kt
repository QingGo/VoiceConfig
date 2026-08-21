package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ScheduleSpec
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 轻量中文时间表达式解析器。
 * 覆盖 PRD V1 核心表达：每天、工作日、周一至周五、明天、每 N 小时/分钟、几点几分。
 */
class TimeExpressionParser {

    fun parse(rawInput: String): ScheduleSpec? {
        val text = normalize(rawInput)

        parseInterval(text)?.let { return it }
        parseWeekly(text)?.let { return it }
        parseDaily(text)?.let { return it }
        parseOnce(text)?.let { return it }

        return null
    }

    private fun parseInterval(text: String): ScheduleSpec? {
        val hourly = Regex("(?:每|每隔)\\s*(\\d+)?\\s*(?:个)?小时").find(text)
        if (hourly != null) {
            val hours = hourly.groupValues[1].ifBlank { "1" }.toLong()
            return ScheduleSpec.interval(hours * 60)
        }
        val minutes = Regex("(?:每|每隔)\\s*(\\d+)?\\s*(?:分钟|分)").find(text)
        if (minutes != null) {
            return ScheduleSpec.interval(minutes.groupValues[1].ifBlank { "1" }.toLong())
        }
        if (text.contains("半小时")) {
            return ScheduleSpec.interval(30)
        }
        val daily = Regex("(?:每|每隔)\\s*(\\d+|[两])\\s*(?:天|日)").find(text)
        if (daily != null) {
            val raw = daily.groupValues[1]
            val days = if (raw == "两") 2L else raw.toLong()
            return ScheduleSpec.interval(days * 24 * 60)
        }
        return null
    }

    private fun parseWeekly(text: String): ScheduleSpec? {
        val time = extractTime(text) ?: return null

        if (text.contains("工作日") || text.contains("周一至周五") || text.contains("星期一到星期五")) {
            return ScheduleSpec.weekly(
                time = time,
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            )
        }
        if (text.contains("周末")) {
            return ScheduleSpec.weekly(
                time = time,
                days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            )
        }

        val dayMatch = Regex("周([一二三四五六日天])|星期([一二三四五六日天])").find(text)
        if (dayMatch != null) {
            val dayChar = dayMatch.groupValues[1].ifBlank { dayMatch.groupValues[2] }
            val day = when (dayChar) {
                "一" -> DayOfWeek.MONDAY
                "二" -> DayOfWeek.TUESDAY
                "三" -> DayOfWeek.WEDNESDAY
                "四" -> DayOfWeek.THURSDAY
                "五" -> DayOfWeek.FRIDAY
                "六" -> DayOfWeek.SATURDAY
                "日", "天" -> DayOfWeek.SUNDAY
                else -> return null
            }
            return ScheduleSpec.weekly(time = time, days = setOf(day))
        }

        return null
    }

    private fun parseDaily(text: String): ScheduleSpec? {
        if (!text.contains("每天") && !text.contains("每日")) return null
        val time = extractTime(text) ?: return null
        return ScheduleSpec.daily(time)
    }

    private fun parseOnce(text: String): ScheduleSpec? {
        val time = extractTime(text) ?: return null
        val date = when {
            text.contains("大后天") -> LocalDate.now().plusDays(3)
            text.contains("后天") -> LocalDate.now().plusDays(2)
            text.contains("明天") || text.contains("明日") || text.contains("明早") || text.contains("明晚") -> LocalDate.now().plusDays(1)
            text.contains("今天") || text.contains("今日") || text.contains("今晚") || text.contains("今早") -> LocalDate.now()
            else -> null
        }
        if (date != null) {
            return ScheduleSpec.once(date = date, time = time)
        }
        // 没有“每天/工作日/周几”等重复词时，默认按今天的一次性任务处理。
        val hasRepeatHint = listOf("每天", "每日", "每", "周", "星期", "工作", "周末").any { text.contains(it) }
        if (!hasRepeatHint) {
            return ScheduleSpec.once(date = LocalDate.now(), time = time)
        }
        return null
    }

    fun extractTime(text: String): LocalTime? {
        val numberPattern = "[0-9０-９零一二三四五六七八九十两]+"
        val patterns = listOf(
            Regex("($numberPattern)\\s*[:：点]\\s*($numberPattern)\\s*分?"),
            Regex("($numberPattern)\\s*[:：]\\s*($numberPattern)"),
            Regex("($numberPattern)\\s*点(?:半|30)?"),
        )

        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val hour = parseChineseNumber(match.groupValues[1]) ?: continue
            val minute = when {
                match.groupValues.size > 2 && match.groupValues[2].isNotBlank() -> parseChineseNumber(match.groupValues[2]) ?: continue
                text.contains("半") -> 30
                else -> 0
            }
            if (hour in 0..23 && minute in 0..59) {
                val adjustedHour = when {
                    (text.contains("下午") || text.contains("晚上") || text.contains("今晚") || text.contains("明晚")) && hour < 12 -> hour + 12
                    text.contains("凌晨") && hour == 12 -> 0
                    else -> hour
                }
                return LocalTime.of(adjustedHour, minute)
            }
        }
        return null
    }

    private fun parseChineseNumber(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        if (s == "十") return 10
        if (s.length == 1) return chineseDigit(s[0])
        if (s.startsWith("十")) {
            val ones = chineseDigit(s[1]) ?: return null
            return 10 + ones
        }
        if (s.endsWith("十")) {
            val tens = chineseDigit(s[0]) ?: return null
            return tens * 10
        }
        if (s.contains("十")) {
            val parts = s.split("十")
            val tens = if (parts[0].isEmpty()) 1 else chineseDigit(parts[0][0]) ?: return null
            val ones = if (parts.size > 1 && parts[1].isNotEmpty()) {
                chineseDigit(parts[1][0]) ?: return null
            } else {
                0
            }
            return tens * 10 + ones
        }
        if (s.length == 2 && s[0] == '零') {
            return chineseDigit(s[1])
        }
        if (s.length == 2) {
            val tens = chineseDigit(s[0]) ?: return null
            val ones = chineseDigit(s[1]) ?: return null
            return tens * 10 + ones
        }
        return null
    }

    private fun chineseDigit(c: Char): Int? = when (c) {
        '零' -> 0
        '一' -> 1
        '二' -> 2
        '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
    }

    private fun normalize(input: String): String {
        return input
            .trim()
            .lowercase(Locale.ROOT)
            .replace("：", ":")
            .replace("點", "点")
            .replace("０", "0")
            .replace("１", "1")
            .replace("２", "2")
            .replace("３", "3")
            .replace("４", "4")
            .replace("５", "5")
            .replace("６", "6")
            .replace("７", "7")
            .replace("８", "8")
            .replace("９", "9")
    }
}
