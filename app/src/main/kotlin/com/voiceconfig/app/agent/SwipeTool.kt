package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import com.voiceconfig.app.service.AgentAccessibilityService

/**
 * 滑动 / 长按。
 * 参数：{"x1":0,"y1":1000,"x2":0,"y2":400,"durationMs":300}
 */
@Singleton
class SwipeTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "swipe"
    override val description: String = "滑动或长按，参数：{\"x1\":0,\"y1\":1000,\"x2\":0,\"y2\":400,\"durationMs\":300}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val x1 = (args["x1"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x1")
        val y1 = (args["y1"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y1")
        val x2 = (args["x2"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x2")
        val y2 = (args["y2"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y2")
        val duration = (args["durationMs"] as? Number)?.toInt()?.coerceIn(0, 10_000) ?: 300

        if (AgentAccessibilityService.gestureSwipe(x1, y1, x2, y2, duration) == true) {
            return ToolResult.success(
                "已通过无障碍手势滑动",
                mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "source" to "accessibility_gesture"),
            )
        }

        val result = shizuku.execute("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString())
        return if (result.ok) {
            ToolResult.success("已滑动", mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2))
        } else {
            ToolResult.failure("滑动失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
