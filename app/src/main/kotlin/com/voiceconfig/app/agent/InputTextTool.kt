package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 输入文本。
 *
 * Android `input text` 不支持中文等多字节字符，部分模拟器/真机还会抛 NPE。
 * 因此优先使用 Shizuku shell 输入；失败时降级到 AccessibilityService 的
 * ACTION_SET_TEXT，支持中文。
 */
@Singleton
class InputTextTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "input_text"
    override val description: String = "向当前焦点输入框输入文本，参数：{\"text\":\"hello world\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: return ToolResult.failure("缺少参数 text")
        if (text.isBlank()) return ToolResult.failure("文本不能为空")

        val shellError = runCatching {
            if (shizuku.isAvailable()) {
                val escaped = text
                    .replace("%", "%s")
                    .replace(" ", "%s")
                    .replace("\"", "\\\"")
                val result = shizuku.execute("input", "text", escaped)
                if (result.ok) {
                    return ToolResult.success("已输入文本", mapOf("text" to text, "source" to "shizuku"))
                }
                result.stderr.trim().ifBlank { "exit=${result.exitCode}" }
            } else {
                "Shizuku 不可用"
            }
        }.getOrElse { "shell 输入异常：${it.message}" }

        val a11yOk = AgentAccessibilityService.inputText(text)
        if (a11yOk == true) {
            return ToolResult.success("已通过无障碍输入文本", mapOf("text" to text, "source" to "accessibility"))
        }

        return ToolResult.failure(
            "输入失败：shell=$shellError；无障碍=${if (a11yOk == null) "服务未开启或未连接" else "目标输入框不可用"}",
        )
    }
}
