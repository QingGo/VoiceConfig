package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 输入文本工具。
 *
 * 具体输入策略收敛到 [TextInputManager]：
 * - Accessibility ACTION_SET_TEXT
 * - Accessibility ACTION_PASTE
 * - 剪贴板 + KEYCODE_PASTE
 * - Shizuku shell
 *
 * 每次执行都会记录策略成功/失败次数，便于后续做自适应选择。
 */
@Singleton
class InputTextTool @Inject constructor(
    private val textInputManager: TextInputManager,
) : AgentTool {

    override val name: String = "input_text"
    override val description: String = "向当前焦点输入框输入文本，参数：{\"text\":\"hello world\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: return ToolResult.failure("缺少参数 text")
        if (text.isBlank()) return ToolResult.failure("文本不能为空")

        val result = textInputManager.input(text)
        return if (result.ok) {
            ToolResult.success(
                result.message,
                mapOf("text" to text, "source" to result.source),
            )
        } else {
            ToolResult.failure(
                "输入失败：${result.message}",
                mapOf("text" to text, "source" to result.source),
            )
        }
    }
}
