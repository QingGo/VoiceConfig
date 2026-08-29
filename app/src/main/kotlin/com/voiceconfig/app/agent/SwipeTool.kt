package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 滑动 / 长按。
 * 参数：{"x1":0,"y1":1000,"x2":0,"y2":400,"durationMs":300}
 */
@Singleton
class SwipeTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "swipe"
    override val description: String = "滑动或长按，参数：{\"x1\":0,\"y1\":1000,\"x2\":0,\"y2\":400,\"durationMs\":300}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val x1 = (args["x1"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x1")
        val y1 = (args["y1"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y1")
        val x2 = (args["x2"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x2")
        val y2 = (args["y2"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y2")
        val duration = (args["durationMs"] as? Number)?.toInt()?.coerceIn(1, 10_000) ?: 300

        val result = uiActionLayer.swipe(x1, y1, x2, y2, duration)
        return if (result.ok) {
            ToolResult.success(result.message, result.data)
        } else {
            ToolResult.failure(result.message, result.data)
        }
    }
}
