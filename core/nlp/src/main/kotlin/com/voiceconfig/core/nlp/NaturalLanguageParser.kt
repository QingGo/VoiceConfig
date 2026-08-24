package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.TaskDraft

/**
 * 旧版自然语言解析接口。
 *
 * 注意：这只是兼容/历史数据层，不是用户意图判断主干。
 * 当前主路径已经统一为“云 LLM + Function Calling”。
 */
interface NaturalLanguageParser {
    fun parse(input: String): TaskDraft?
}
