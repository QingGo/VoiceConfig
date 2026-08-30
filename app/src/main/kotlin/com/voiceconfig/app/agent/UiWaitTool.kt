package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 等待工具。
 *
 * 与 [UiAssertTool] 同源：显式提供 `wait_for` 语义的独立工具名，
 * 让模型在“等待页面/元素出现”时有更明确的入口，而不是把等待混在断言里。
 */
@Singleton
class UiWaitTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "ui_wait"
    override val description: String =
        "等待当前界面出现指定元素，参数：{\"resourceId\":\"...\",\"text\":\"...\",\"desc\":\"...\",\"timeoutMs\":5000}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val effective = args.toMutableMap()
        if (effective["action"] == null) {
            effective["action"] = "wait_for"
        }
        return UiAssertTool(uiActionLayer).execute(effective)
    }
}
