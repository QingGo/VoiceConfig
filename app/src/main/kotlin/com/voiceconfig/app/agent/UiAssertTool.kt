package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 确定性 UI 断言/等待工具。
 *
 * 让 Agent 不再用文字“我觉得已经到达”，而是用系统化断言验证：
 * - visible：当前界面必须存在目标节点
 * - not_visible：当前界面必须不存在目标节点
 * - wait_for：轮询等待目标节点出现
 */
@Singleton
class UiAssertTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "ui_assert"
    override val description: String =
        "断言当前界面元素状态，参数：{\"action\":\"visible|not_visible|wait_for\",\"resourceId\":\"...\",\"text\":\"...\",\"desc\":\"...\",\"timeoutMs\":5000}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val action = (args["action"]?.toString() ?: "visible").trim().lowercase()
        val selector = UiSelector(
            resourceId = (args["resourceId"] as? String).orEmpty(),
            text = (args["text"] as? String).orEmpty(),
            desc = (args["desc"] as? String).orEmpty(),
        )
        if (selector.resourceId.isBlank() && selector.text.isBlank() && selector.desc.isBlank()) {
            return ToolResult.failure("ui_assert 需要至少提供 resourceId/text/desc 之一")
        }
        val timeoutMs = (args["timeoutMs"] as? Number)?.toLong()?.coerceIn(100, 30_000) ?: 5_000L
        val result = when (action) {
            "visible" -> uiActionLayer.assertVisible(selector)
            "not_visible", "absent", "gone" -> uiActionLayer.assertNotVisible(selector)
            "wait_for", "wait", "wait_visible" -> uiActionLayer.waitFor(selector, timeoutMs)
            else -> return ToolResult.failure("不支持的 action：$action（支持 visible/not_visible/wait_for）")
        }
        return if (result.ok) {
            ToolResult.success(result.message, result.data + mapOf("action" to action))
        } else {
            ToolResult.failure(result.message, result.data + mapOf("action" to action))
        }
    }
}
