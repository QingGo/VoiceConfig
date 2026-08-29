package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按文字点击工具：读取当前 UI 层级，找到包含目标文字的节点并点击其中心。
 *
 * 统一走 [UiActionLayer]，优先 resource-id / text / content-desc / 无障碍真实点击，
 * 坐标只作为兜底。
 */
@Singleton
class TapTextTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "tap_text"
    override val description: String = "按界面文字点击按钮，参数：{\"text\":\"发送\"} 或 {\"texts\":[\"发送\",\"Send\",\"发送消息\"]}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val texts = mutableListOf<String>()
        (args["text"]?.toString())?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
        (args["texts"] as? List<*>)?.forEach { item ->
            item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
        }
        if (texts.isEmpty()) return ToolResult.failure("缺少参数 text 或 texts")

        val result = uiActionLayer.tapByText(*texts.toTypedArray())
        return if (result.ok) {
            ToolResult.success(result.message, result.data)
        } else {
            ToolResult.failure(result.message, result.data)
        }
    }
}
