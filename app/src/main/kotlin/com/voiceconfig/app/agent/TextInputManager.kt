package com.voiceconfig.app.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import com.voiceconfig.app.service.AgentAccessibilityService
import com.voiceconfig.app.service.AccessibilityKeepAlive
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 统一文本输入策略层。
 *
 * 每个策略独立记录成功/失败次数，后续可基于成功率自动选择最优路径。
 */
interface TextInputStrategy {
    val name: String
    suspend fun input(text: String): TextInputResult
}

data class TextInputResult(
    val ok: Boolean,
    val source: String,
    val message: String = "",
)

@Singleton
class TextInputManager @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
    private val accessibilityKeepAlive: AccessibilityKeepAlive,
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voice_config", Context.MODE_PRIVATE)

    private val strategies: List<TextInputStrategy> = listOf(
        AccessibilitySetTextStrategy(accessibilityKeepAlive),
        AccessibilityPasteStrategy(),
        ClipboardKeyeventStrategy(shizuku),
        ShizukuShellStrategy(shizuku),
    )

    suspend fun input(text: String): TextInputResult {
        if (text.isBlank()) return TextInputResult(ok = false, source = "none", message = "文本不能为空")
        accessibilityKeepAlive.ensureEnabled()
        if (AgentAccessibilityService.instance == null) {
            // 刚写入系统设置后，无障碍服务通常需要一点时间绑定。
            delay(700)
        }
        setClipboard(text)
        for (strategy in strategies) {
            val result = strategy.input(text)
            record(strategy.name, result.ok)
            if (result.ok) {
                return result
            }
        }
        val last = strategies.lastOrNull()?.let { it.name to it } ?: return TextInputResult(ok = false, source = "none", message = "无可用输入策略")
        return TextInputResult(
            ok = false,
            source = last.first,
            message = "所有输入策略均失败",
        )
    }

    private fun setClipboard(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("voiceconfig", text))
        }
    }

    fun stats(): Map<String, Pair<Int, Int>> = STRATEGY_NAMES.associateWith { name ->
        prefs.getInt(statKey(name, true), 0) to prefs.getInt(statKey(name, false), 0)
    }

    private fun record(name: String, ok: Boolean) {
        val key = statKey(name, ok)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun statKey(name: String, ok: Boolean) = "text_input_stat_${name}_${if (ok) "ok" else "fail"}"

    companion object {
        private val STRATEGY_NAMES = listOf(
            "accessibility_set_text",
            "accessibility_paste",
            "clipboard_keyevent",
            "shizuku_shell",
        )
    }
}

class AccessibilitySetTextStrategy(
    private val accessibilityKeepAlive: AccessibilityKeepAlive,
) : TextInputStrategy {
    override val name: String = "accessibility_set_text"
    override suspend fun input(text: String): TextInputResult {
        accessibilityKeepAlive.ensureEnabled()
        val result = AgentAccessibilityService.inputText(text)
        return if (result == true) {
            TextInputResult(true, name, "已通过无障碍输入文本")
        } else {
            TextInputResult(false, name, if (result == null) "无障碍服务未开启或未连接" else "目标输入框不可用")
        }
    }
}

class AccessibilityPasteStrategy : TextInputStrategy {
    override val name: String = "accessibility_paste"
    override suspend fun input(text: String): TextInputResult {
        val result = AgentAccessibilityService.paste()
        return if (result == true) {
            TextInputResult(true, name, "已通过无障碍粘贴输入文本")
        } else {
            TextInputResult(false, name, "无障碍粘贴失败")
        }
    }
}

class ClipboardKeyeventStrategy(
    private val shizuku: ShizukuCommandRunner,
) : TextInputStrategy {
    override val name: String = "clipboard_keyevent"
    override suspend fun input(text: String): TextInputResult {
        val result = shizuku.execute("input", "keyevent", "279")
        return if (result.ok) {
            TextInputResult(true, name, "已通过剪贴板+KEYCODE_PASTE 输入文本")
        } else {
            TextInputResult(false, name, "KEYCODE_PASTE 失败")
        }
    }
}

class ShizukuShellStrategy(
    private val shizuku: ShizukuCommandRunner,
) : TextInputStrategy {
    override val name: String = "shizuku_shell"
    override suspend fun input(text: String): TextInputResult {
        if (!shizuku.isAvailable()) {
            return TextInputResult(false, name, "Shizuku 不可用")
        }
        val escaped = text
            .replace("%", "%s")
            .replace(" ", "%s")
            .replace("\"", "\\\"")
        val result = shizuku.execute("input", "text", escaped)
        return if (result.ok) {
            TextInputResult(true, name, "已输入文本")
        } else {
            TextInputResult(false, name, result.stderr.trim().ifBlank { "exit=${result.exitCode}" })
        }
    }
}
