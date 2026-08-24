package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.TaskDraft

/**
 * 【已冻结】V1 规则版 NLU，仅保留作为模板/历史数据兼容。
 *
 * 不再作为用户意图判断主干；新能力一律走云 LLM + Function Calling。
 *
 * 历史说明：
 * 1. 解析时间/重复规则
 * 2. 识别动作
 * 3. 解析目标 App
 */
class RuleBasedNlpParser(
    private val timeParser: TimeExpressionParser = TimeExpressionParser(),
    private val appAliasResolver: AppAliasResolver = AppAliasResolver(),
) : NaturalLanguageParser {

    override fun parse(input: String): TaskDraft? {
        val text = input.trim()
        if (text.isBlank()) return null
        if (looksLikeComplexTask(text)) return null

        val schedule = timeParser.parse(text)
        val actionType = detectActionType(text)
        val target = detectTarget(text)

        if (schedule == null && actionType == ActionType.NOTIFY && target == null) {
            return TaskDraft(
                rawText = text,
                schedule = null,
                actionType = ActionType.NOTIFY,
                executionMode = ExecutionMode.NOTIFICATION,
                confidence = 0.5,
            )
        }
        if (schedule == null && actionType == ActionType.OPEN_APP && target == null) {
            return null
        }

        return TaskDraft(
            rawText = text,
            schedule = schedule,
            actionType = actionType,
            targetPackage = target?.packageName,
            targetActivity = target?.activityName,
            executionMode = if (actionType == ActionType.NOTIFY) ExecutionMode.NOTIFICATION else ExecutionMode.AUTO,
            confidence = confidence(schedule, actionType, target),
        )
    }

    private fun looksLikeComplexTask(text: String): Boolean {
        val complexWords = listOf(
            "点单", "点咖啡", "下单", "购买", "结算", "支付", "付款",
            "填写", "提交", "打卡", "选择", "切换", "查找", "搜索并",
            "输入", "登录", "注册", "预约", "订", "领券", "签到",
        )
        return complexWords.any { text.contains(it, ignoreCase = true) }
    }

    private fun detectActionType(text: String): ActionType {
        return when {
            text.contains("打开页面") || text.contains("跳转") -> ActionType.OPEN_DEEPLINK
            text.contains("打开") || text.contains("启动") || text.contains("进入") -> ActionType.OPEN_APP
            text.contains("提醒") || text.contains("通知") || text.contains("告诉我") -> ActionType.NOTIFY
            else -> ActionType.OPEN_APP
        }
    }

    private fun detectTarget(text: String): AppAliasResolver.ResolvedApp? {
        // 优先提取“打开 X / 启动 X / 进入 X”中的 X
        val afterVerb = Regex("(?:打开|启动|进入)\\s*(.+)").find(text)?.groupValues?.get(1)
        val candidates = afterVerb
            ?.replace("每天", "")
            ?.replace("工作日", "")
            ?.replace("明天", "")
            ?.replace("早上", "")
            ?.replace("上午", "")
            ?.replace("中午", "")
            ?.replace("下午", "")
            ?.replace("晚上", "")
            ?.trim()
            ?.trimEnd('吧', '啊', '呀', '哦')
            ?.takeIf { it.isNotBlank() }

        if (candidates != null) {
            appAliasResolver.resolve(candidates)?.let { return it }
        }

        // 支持“提醒我打开企业微信”等省略形式
        val reminderTarget = Regex("提醒我?(?:打开|启动|进入)?\\s*(.+)").find(text)?.groupValues?.get(1)
            ?.replace("每天", "")
            ?.replace("工作日", "")
            ?.replace("明天", "")
            ?.replace("早上", "")
            ?.replace("上午", "")
            ?.replace("中午", "")
            ?.replace("下午", "")
            ?.replace("晚上", "")
            ?.trim()
            ?.trimEnd('吧', '啊', '呀', '哦')
            ?.takeIf { it.isNotBlank() }
        return reminderTarget?.let { appAliasResolver.resolve(it) }
    }

    private fun confidence(
        schedule: ScheduleSpec?,
        actionType: ActionType,
        target: AppAliasResolver.ResolvedApp?,
    ): Double {
        var score = 0.4
        if (schedule != null) score += 0.3
        if (target != null) score += 0.3
        if (actionType != ActionType.OPEN_APP || target != null) score += 0.1
        return score.coerceIn(0.0, 1.0)
    }
}
