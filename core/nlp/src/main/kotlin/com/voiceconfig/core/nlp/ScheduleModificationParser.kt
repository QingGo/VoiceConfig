package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ScheduleSpec
import java.time.LocalDate
import java.time.LocalTime

/**
 * 解析多轮修改指令，例如：
 * - “不是8点，改成9点”
 * - “改成9点”
 * - “改到9点半”
 * - “不是明天，改成后天”
 */
class ScheduleModificationParser(
    private val timeParser: TimeExpressionParser = TimeExpressionParser(),
) {

    fun parse(input: String, current: ScheduleSpec?): ScheduleSpec? {
        if (current == null) return null
        val text = input.trim()
        if (text.isBlank()) return null
        if (!looksLikeModification(text)) return null

        val newTime = extractNewTime(text) ?: return null
        return when (current.type) {
            ScheduleSpec.ScheduleType.DAILY -> current.copy(time = newTime)
            ScheduleSpec.ScheduleType.WEEKLY -> current.copy(time = newTime)
            ScheduleSpec.ScheduleType.ONCE -> current.copy(
                time = newTime,
                date = extractNewDate(text) ?: current.date,
            )
            ScheduleSpec.ScheduleType.INTERVAL -> null
        }
    }

    private fun looksLikeModification(text: String): Boolean =
        listOf("改成", "改为", "改到", "变成", "调整到", "调到", "不是").any { text.contains(it) }

    private fun extractNewTime(text: String): LocalTime? {
        val afterModifier = Regex("(?:改成|改为|改到|变成|调整到|调到)\\s*(.+)").find(text)?.groupValues?.get(1)
        if (!afterModifier.isNullOrBlank()) {
            timeParser.extractTime(afterModifier)?.let { return it }
        }
        // 如果没有“改成”，尝试从整句中提取最后一个时间，避免被“不是8点”中的旧时间干扰
        val allTimes = Regex("[0-9０-９零一二三四五六七八九十两]+\\s*[:：点]\\s*[0-9０-９零一二三四五六七八九十两]*\\s*分?").findAll(text)
            .map { it.value }
            .toList()
        if (allTimes.isNotEmpty()) {
            timeParser.extractTime(allTimes.last())?.let { return it }
        }
        return null
    }

    private fun extractNewDate(text: String): LocalDate? = when {
        text.contains("大后天") -> LocalDate.now().plusDays(3)
        text.contains("后天") -> LocalDate.now().plusDays(2)
        text.contains("明天") || text.contains("明日") -> LocalDate.now().plusDays(1)
        text.contains("今天") || text.contains("今日") -> LocalDate.now()
        else -> null
    }
}
