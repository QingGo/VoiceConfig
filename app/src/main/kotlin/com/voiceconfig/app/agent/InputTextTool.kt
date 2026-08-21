package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 输入文本。
 *
 * Android `input text` 不支持所有特殊字符，这里做基础转义：
 * 空格 -> %s，常见 shell 特殊字符按 `input text` 规则处理。
 * 参数：{"text":"hello"}
 */
@Singleton
class InputTextTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "input_text"
    override val description: String = "向当前焦点输入框输入文本，参数：{\"text\":\"hello world\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: return ToolResult.failure("缺少参数 text")
        if (text.isBlank()) return ToolResult.failure("文本不能为空")
        val escaped = text
            .replace("%", "%s")
            .replace(" ", "%s")
            .replace("\"", "\\\"")
        val result = shizuku.execute("input", "text", escaped)
        return if (result.ok) {
            ToolResult.success("已输入文本", mapOf("text" to text))
        } else {
            ToolResult.failure("输入失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
