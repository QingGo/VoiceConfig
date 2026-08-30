package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

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
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "input_text"
    override val description: String = "向当前焦点输入框输入文本；如果输入框未聚焦，可同时传 x/y 先点击输入框坐标再输入，参数：{\"text\":\"hello world\",\"x\":500,\"y\":2870}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: return ToolResult.failure("缺少参数 text")
        if (text.isBlank()) return ToolResult.failure("文本不能为空")

        val focusX = (args["x"] as? Number)?.toInt()
        val focusY = (args["y"] as? Number)?.toInt()
        if (focusX != null && focusY != null) {
            val focusResult = uiActionLayer.tapCenter(focusX, focusY)
            if (!focusResult.ok) {
                return ToolResult.failure("无法点击输入框：${focusResult.message}", mapOf("text" to text, "focusX" to focusX, "focusY" to focusY))
            }
            delay(350)
        }

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
