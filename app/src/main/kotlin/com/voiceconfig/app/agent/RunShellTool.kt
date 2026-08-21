package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白名单 shell 执行工具。
 *
 * 只允许系统自动化常用命令，禁止 rm/reboot/pm/su/mount 等危险操作。
 * 参数：{"command": "settings put system screen_brightness 128"} 或 {"cmd": ["settings", "put", ...]}
 */
@Singleton
class RunShellTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "run_shell"
    override val description: String = "执行白名单 shell 命令（am/settings/dumpsys/input/uiautomator/logcat/getprop），参数：{\"command\":\"settings put system screen_brightness 128\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val command = when {
            args["command"] != null -> args["command"].toString()
            args["cmd"] is List<*> -> (args["cmd"] as List<*>).joinToString(" ") { it?.toString() ?: "" }
            args["cmd"] is Array<*> -> (args["cmd"] as Array<*>).joinToString(" ") { it?.toString() ?: "" }
            else -> return ToolResult.failure("缺少参数 command")
        }
        if (command.isBlank()) return ToolResult.failure("命令不能为空")

        val tokens = tokenize(command)
        if (tokens.isEmpty()) return ToolResult.failure("命令不能为空")

        val base = tokens[0]
        if (base !in ALLOWED_COMMANDS) {
            return ToolResult.failure("不允许的命令：$base，仅允许：${ALLOWED_COMMANDS.joinToString()}")
        }
        val joined = tokens.joinToString(" ")
        if (BLOCKED_PATTERNS.any { joined.contains(it, ignoreCase = true) }) {
            return ToolResult.failure("命令包含被禁止的危险操作")
        }

        val result = shizuku.execute(*tokens.toTypedArray())
        if (result.ok) {
            return ToolResult.success(
                result.stdout.trim().ifBlank { "命令执行成功（无输出）" },
                mapOf("exitCode" to result.exitCode, "stdout" to result.stdout, "stderr" to result.stderr),
            )
        }
        return ToolResult.failure(
            "命令执行失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}",
            mapOf("exitCode" to result.exitCode, "stdout" to result.stdout, "stderr" to result.stderr),
        )
    }

    private fun tokenize(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in input) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    companion object {
        private val ALLOWED_COMMANDS = setOf(
            "am", "cmd", "dumpsys", "input", "settings", "uiautomator", "logcat", "getprop", "echo", "cat", "ls", "pwd", "id", "which",
        )
        private val BLOCKED_PATTERNS = listOf(
            "rm ", "reboot", "pm ", "su ", "mount", "chmod", "chown", "mkfs", "dd ",
            "shutdown", "kill", "pkill", "service call", "content ", "device_config",
        )
    }
}
