package com.voiceconfig.app.agent

import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取剪贴板文本。
 * 参数：无
 */
@Singleton
class ClipboardReadTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "clipboard_read"
    override val description: String = "读取剪贴板文本，无参数"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult.failure("无法访问剪贴板")
        val clip = clipboard.primaryClip ?: return ToolResult.failure("剪贴板为空")
        if (clip.itemCount == 0) return ToolResult.failure("剪贴板为空")
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return ToolResult.failure("剪贴板不是文本")
        return ToolResult.success("已读取剪贴板", mapOf("text" to text, "length" to text.length))
    }
}
