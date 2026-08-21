package com.voiceconfig.app.agent

/**
 * 一个可被 LLM 调用的自动化工具。
 *
 * 设计目标：工具数量少（<10）、表达力强、参数简单，让没有多模态能力的
 * 文本 LLM 也能通过“读 XML 布局 + 绝对坐标点击”完成手机自动化。
 */
interface AgentTool {
    /** 工具名，LLM 在 JSON 中使用这个名字。 */
    val name: String

    /** 人类可读描述，用于拼进 LLM system prompt。 */
    val description: String

    /** 执行工具。参数由 LLM 提供，必须是简单 JSON 可序列化类型。 */
    suspend fun execute(args: Map<String, Any?>): ToolResult
}
