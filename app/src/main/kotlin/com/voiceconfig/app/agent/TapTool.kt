package com.voiceconfig.app.agent

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
        val result = shizuku.execute("input", "tap", x.toString(), y.toString())
        return if (result.ok) {
            ToolResult.success("已点击 ($x, $y)", mapOf("x" to x, "y" to y))
        } else {
            ToolResult.failure("点击失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
