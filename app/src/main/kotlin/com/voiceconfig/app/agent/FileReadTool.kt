package com.voiceconfig.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取 Agent 产物文件。
 * 参数：{"filename":"report.md"} 或 {"path":"/absolute/path"}
 */
@Singleton
class FileReadTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "file_read"
    override val description: String = "读取已保存的产物文件，参数：{\"filename\":\"report.md\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filename = args["filename"]?.toString()?.trim()?.ifBlank { null }
        val path = args["path"]?.toString()?.trim()?.ifBlank { null }
        val file = when {
            filename != null -> File(File(context.filesDir, "agent_outputs"), filename)
            path != null -> File(path)
            else -> return ToolResult.failure("缺少参数 filename 或 path")
        }
        if (!file.exists() || !file.isFile) return ToolResult.failure("文件不存在：${file.absolutePath}")
        if (!file.canRead()) return ToolResult.failure("文件不可读")
        return try {
            val content = file.readText(Charsets.UTF_8)
            ToolResult.success("已读取 ${file.name}", mapOf("content" to content, "path" to file.absolutePath))
        } catch (e: Exception) {
            ToolResult.failure("读取失败：${e.message}")
        }
    }
}
