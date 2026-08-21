package com.voiceconfig.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 打开/分享 Agent 产物文件。
 * 参数：{"filename":"report.md"} 或 {"path":"/absolute/path"}
 */
@Singleton
class OpenFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "open_file"
    override val description: String = "打开/分享已保存的产物文件，参数：{\"filename\":\"report.md\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filename = args["filename"]?.toString()?.trim()?.ifBlank { null }
        val path = args["path"]?.toString()?.trim()?.ifBlank { null }
        val file = when {
            filename != null -> File(File(context.filesDir, "agent_outputs"), filename)
            path != null -> File(path)
            else -> return ToolResult.failure("缺少参数 filename 或 path")
        }
        if (!file.exists() || !file.isFile) return ToolResult.failure("文件不存在：${file.absolutePath}")
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享 ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ToolResult.success("已打开分享面板：${file.absolutePath}", mapOf("path" to file.absolutePath))
        } catch (e: Exception) {
            ToolResult.failure("打开失败：${e.message}")
        }
    }
}
