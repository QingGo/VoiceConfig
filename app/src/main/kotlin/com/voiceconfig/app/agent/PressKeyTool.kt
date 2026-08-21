package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发送 Android 按键事件，例如返回、主页、回车。
 * 参数：{"key":"back"}，支持 back/home/enter/menu/app_switch/search。
 */
@Singleton
class PressKeyTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "press_key"
    override val description: String = "发送系统按键，参数：{\"key\":\"back\"} 或 {\"keycode\":4}，支持 back/home/enter/menu/app_switch/search"

    private val keycodes = mapOf(
        "back" to 4,
        "home" to 3,
        "enter" to 66,
        "menu" to 82,
        "app_switch" to 187,
        "search" to 84,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString()?.lowercase()
        val code = key?.let { keycodes[it] } ?: (args["keycode"] as? Number)?.toInt()
            ?: return ToolResult.failure(if (key == null) "缺少参数 key" else "不支持的按键：$key")
        val result = shizuku.execute("input", "keyevent", code.toString())
        return if (result.ok) {
            ToolResult.success("已发送按键 $key ($code)", mapOf("key" to key, "keycode" to code))
        } else {
            ToolResult.failure("按键失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}")
        }
    }
}
