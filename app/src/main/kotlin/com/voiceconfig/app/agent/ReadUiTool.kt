package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取当前页面 UI 层级（XML + 绝对坐标）。
 *
 * 通过 Shizuku 执行 `uiautomator dump`，再用 `cat` 读取 XML，解析成紧凑摘要。
 * 参数：{"maxNodes": 120}（可选）
 */
@Singleton
class ReadUiTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "read_ui"
    override val description: String = "读取当前页面 UI 元素（含绝对坐标），参数：{\"maxNodes\":120}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        if (!shizuku.isAvailable()) {
            return ToolResult.failure("read_ui 需要 Shizuku 授权")
        }
        val maxNodes = (args["maxNodes"] as? Number)?.toInt()?.coerceIn(10, 500) ?: 120

        val dumpFile = "/data/local/tmp/voiceconfig_ui.xml"
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        if (!dump.ok) {
            return ToolResult.failure("UI 层级获取失败：${dump.stderr.trim().ifBlank { "exit=${dump.exitCode}" }}")
        }
        // 不同设备/Shizuku 环境可能把文件写到不同路径，优先从输出中解析实际路径。
        val dumpOutput = dump.stdout + "\n" + dump.stderr
        val dumpedPath = Regex("""dumped to:\s*(\S+)""", RegexOption.IGNORE_CASE)
            .find(dumpOutput)
            ?.groupValues
            ?.getOrNull(1)
            ?: dumpFile
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) {
                return ToolResult.failure("无法读取 UI 文件：${cat.stderr} ${fallback.stderr}")
            }
            fallback.stdout
        }

        val summary = UiDumpParser.summarize(xml, maxNodes)
        val nodes = UiDumpParser.parse(xml).filter { it.enabled && (it.hasLabel || it.clickable || it.focusable) }.take(maxNodes)
        return ToolResult.success(
            summary,
            mapOf(
                "ui" to summary,
                "nodeCount" to nodes.size,
                "bounds" to nodes.map { it.bounds },
            ),
        )
    }
}
