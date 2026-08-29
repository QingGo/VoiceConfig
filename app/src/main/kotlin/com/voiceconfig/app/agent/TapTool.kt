package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按绝对坐标点击屏幕（最后兜底）。
 * 参数：{"x": 720, "y": 2400}
 */
@Singleton
class TapTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "tap"
    override val description: String = "按屏幕绝对坐标点击，参数：{\"x\":720,\"y\":2400}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val x = (args["x"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x")
        val y = (args["y"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y")
        val result = uiActionLayer.tapCenter(x, y)
        return if (result.ok) {
            ToolResult.success(result.message, result.data)
        } else {
            ToolResult.failure(result.message, result.data)
        }
    }
}
