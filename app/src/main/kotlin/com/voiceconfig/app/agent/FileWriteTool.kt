package com.voiceconfig.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 Agent 产物写入应用私有目录 `files/agent_outputs`。
 *
 * 参数：{"filename":"note.md","content":"..."}
 * 安全限制：只允许相对文件名，不允许 `..` 或绝对路径。
 */
@Singleton
class FileWriteTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "file_write"
    override val description: String = "把文本保存为文件（Markdown/脚本/文本），参数：{\"filename\":\"report.md\",\"content\":\"...\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filename = args["filename"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 filename")
        val content = args["content"]?.toString() ?: return ToolResult.failure("缺少参数 content")
        if (filename.contains("..") || filename.startsWith("/") || filename.contains(":")) {
            return ToolResult.failure("文件名不合法")
        }
        return try {
            val dir = File(context.filesDir, "agent_outputs").apply { mkdirs() }
            val file = File(dir, filename)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            ToolResult.success(
                "已保存 ${file.absolutePath}",
                mapOf("path" to file.absolutePath, "size" to file.length()),
            )
        } catch (e: Exception) {
            ToolResult.failure("保存失败：${e.message}")
        }
    }
}
