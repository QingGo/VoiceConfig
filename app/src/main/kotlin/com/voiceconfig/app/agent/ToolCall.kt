package com.voiceconfig.app.agent

/**
 * LLM 输出的一个工具调用。
 */
data class ToolCall(
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
)
