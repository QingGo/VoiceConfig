package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

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
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                return ToolResult.success("已通过无障碍服务读取当前界面：\n$a11y", mapOf("source" to "accessibility"))
            }
            return ToolResult.failure("read_ui 需要 Shizuku 授权或开启无障碍服务")
        }
        val maxNodes = (args["maxNodes"] as? Number)?.toInt()?.coerceIn(10, 500) ?: 120
        val timingMs = linkedMapOf<String, Long>()
        val totalStartMs = System.currentTimeMillis()

        val dumpFile = "/data/local/tmp/voiceconfig_ui.xml"
        val dumpStartMs = System.currentTimeMillis()
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        timingMs["dump_cmd_ms"] = System.currentTimeMillis() - dumpStartMs
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
        val catStartMs = System.currentTimeMillis()
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) {
                return ToolResult.failure("无法读取 UI 文件：${cat.stderr} ${fallback.stderr}")
            }
            fallback.stdout
        }
        timingMs["cat_xml_ms"] = System.currentTimeMillis() - catStartMs

        // 防止 uiautomator dump 返回上一个窗口/旧 App 的 UI 树。
        var finalXml = xml
        repeat(2) { attempt ->
            val currentPackage = currentForegroundPackage()
            val dumpedPackage = Regex("""package="([^"]+)"""").find(finalXml)?.groupValues?.get(1)
            if (currentPackage == null || dumpedPackage == null || currentPackage == dumpedPackage) {
                return@repeat
            }
            if (attempt == 0) {
                delay(400)
                finalXml = dumpUiXml(dumpedPath) ?: finalXml
            }
        }
        val foreStartMs = System.currentTimeMillis()
        val currentPackageFinal = currentForegroundPackage()
        val dumpedPackageFinal = Regex("""package="([^"]+)"""").find(finalXml)?.groupValues?.get(1)
        timingMs["foreground_verify_ms"] = System.currentTimeMillis() - foreStartMs
        if (currentPackageFinal != null && dumpedPackageFinal != null && currentPackageFinal != dumpedPackageFinal) {
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                return ToolResult.success("已通过无障碍服务读取当前界面（uiautomator 窗口不一致）：\n$a11y", mapOf("source" to "accessibility", "foregroundPackage" to currentPackageFinal))
            }
            return ToolResult.failure("UI 树与当前前台窗口不一致：dump=$dumpedPackageFinal foreground=$currentPackageFinal，请改用 read_screen 或重试")
        }
        val parseStartMs = System.currentTimeMillis()
        val summary = UiDumpParser.summarize(finalXml, maxNodes, maxChars = (args["maxChars"] as? Number)?.toInt()?.coerceIn(500, 20_000) ?: 4_000)
        val nodes = UiDumpParser.parse(finalXml).filter { it.enabled && (it.hasLabel || it.clickable || it.focusable) }.take(maxNodes)
        timingMs["parse_ms"] = System.currentTimeMillis() - parseStartMs
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        return ToolResult.success(
            summary,
            mapOf(
                "ui" to summary,
                "nodeCount" to nodes.size,
                "bounds" to nodes.map { it.bounds },
                "timingMs" to timingMs,
            ),
        )
    }

    private fun currentForegroundPackage(): String? {
        val result = shizuku.execute("dumpsys", "activity", "activities")
        if (!result.ok) return null
        val match = Regex("""topResumedActivity=.*?\s([A-Za-z0-9_.]+)/""")
            .find(result.stdout)
            ?: Regex("""mResumedActivity=.*?\s([A-Za-z0-9_.]+)/""")
                .find(result.stdout)
        return match?.groupValues?.getOrNull(1)
    }

    private fun dumpUiXml(dumpedPath: String?): String? {
        val path = dumpedPath ?: return null
        val cat = shizuku.execute("cat", path)
        if (cat.ok && cat.stdout.isNotBlank()) return cat.stdout
        val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
        return if (fallback.ok && fallback.stdout.isNotBlank()) fallback.stdout else null
    }
}
