package com.voiceconfig.app.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.voiceconfig.app.service.AgentAccessibilityService
import com.voiceconfig.app.service.AccessibilityKeepAlive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 输入文本。
 *
 * Android `input text` 不支持中文等多字节字符，部分模拟器/真机还会抛 NPE。
 * 因此输入策略按优先级：
 * 1. 无障碍 ACTION_SET_TEXT（最可靠，支持中文）
 * 2. 剪贴板 + ACTION_PASTE（适合 input_text 失败但焦点仍在输入框）
 * 3. 剪贴板 + Shizuku KEYCODE_PASTE
 * 4. Shizuku shell `input text`（仅适合纯 ASCII）
 *
 * 同时尝试自动拉起/保持无障碍服务，避免应用重启后中文输入失效。
 */
@Singleton
class InputTextTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
    private val accessibilityKeepAlive: AccessibilityKeepAlive,
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "input_text"
    override val description: String = "向当前焦点输入框输入文本，参数：{\"text\":\"hello world\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: return ToolResult.failure("缺少参数 text")
        if (text.isBlank()) return ToolResult.failure("文本不能为空")

        val hasNonAscii = text.any { it.code > 127 }

        // 尝试自动恢复/保持无障碍，这样中文输入不依赖用户手动重开。
        accessibilityKeepAlive.ensureEnabled()
        if (AgentAccessibilityService.instance == null) {
            // 刚写入系统设置后，无障碍服务通常需要一点时间绑定。
            delay(700)
        }

        // 1. 无障碍 ACTION_SET_TEXT：中文首选。
        if (AgentAccessibilityService.instance != null || !hasNonAscii) {
            val a11ySetText = AgentAccessibilityService.inputText(text)
            if (a11ySetText == true) {
                return ToolResult.success(
                    "已通过无障碍输入文本",
                    mapOf("text" to text, "source" to "accessibility_set_text"),
                )
            }
        }

        // 2/3. 剪贴板 + 粘贴：主要针对中文输入框。
        if (hasNonAscii) {
            setClipboard(text)
            val a11yPaste = AgentAccessibilityService.paste()
            if (a11yPaste == true) {
                return ToolResult.success(
                    "已通过无障碍粘贴输入文本",
                    mapOf("text" to text, "source" to "accessibility_paste"),
                )
            }
            if (shizuku.isAvailable()) {
                val keyResult = shizuku.execute("input", "keyevent", "279")
                if (keyResult.ok) {
                    return ToolResult.success(
                        "已通过剪贴板+KEYCODE_PASTE 输入文本",
                        mapOf("text" to text, "source" to "clipboard_keyevent"),
                    )
                }
            }
        }

        // 4. Shizuku shell 输入：仅适合 ASCII；对中文不保证成功。
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

        val a11yFinal = AgentAccessibilityService.inputText(text)
        if (a11yFinal == true) {
            return ToolResult.success(
                "已通过无障碍输入文本",
                mapOf("text" to text, "source" to "accessibility_set_text"),
            )
        }

        return ToolResult.failure(
            "输入失败：shell=$shellError；无障碍=${if (a11yFinal == null) "服务未开启或未连接" else "目标输入框不可用"}",
        )
    }

    private fun setClipboard(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("voiceconfig", text))
        }
    }
}
