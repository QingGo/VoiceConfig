package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取最近 logcat 日志，用于报错排查。
 * 参数：{"lines":200}（可选，默认 200）
 */
@Singleton
class LogcatReadTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "logcat_read"
    override val description: String = "读取最近系统日志，参数：{\"lines\":200}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val lines = (args["lines"] as? Number)?.toInt()?.coerceIn(10, 1000) ?: 200
        val result = shizuku.execute("logcat", "-d", "-t", lines.toString())
        return if (result.ok) {
            val output = result.stdout.trim()
            if (output.isBlank()) {
                ToolResult.success("logcat 无输出", mapOf("lines" to 0))
            } else {
                ToolResult.success("已读取 $lines 行日志", mapOf("log" to output, "lines" to output.lines().size))
            }
        } else {
            ToolResult.failure("读取日志失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
