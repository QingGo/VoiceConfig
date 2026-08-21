package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按绝对坐标点击屏幕。
 * 参数：{"x": 720, "y": 2400}
 */
@Singleton
class TapTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "tap"
    override val description: String = "按屏幕绝对坐标点击，参数：{\"x\":720,\"y\":2400}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val x = (args["x"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x")
        val y = (args["y"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y")
        if (x < 0 || y < 0) return ToolResult.failure("坐标不能为负数")
        if (!shizuku.isAvailable()) {
            val clicked = AgentAccessibilityService.clickPoint(x, y)
            return if (clicked == true) {
                ToolResult.success("已通过无障碍服务点击 ($x, $y)", mapOf("x" to x, "y" to y, "source" to "accessibility"))
            } else {
                ToolResult.failure("tap 需要 Shizuku 授权或开启无障碍服务；无障碍服务未能点击 $x,$y")
            }
        }
        val result = shizuku.execute("input", "tap", x.toString(), y.toString())
        return if (result.ok) {
            ToolResult.success("已点击 ($x, $y)", mapOf("x" to x, "y" to y))
        } else {
            ToolResult.failure("点击失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
