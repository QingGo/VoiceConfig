package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 按文字点击工具：读取当前 UI 层级，找到包含目标文字的节点并点击其中心。
 *
 * 适合点击“发送”“立即购买”“同意”“菜单”等有明确文字标签的按钮，比纯坐标猜测更稳定。
 */
@Singleton
class TapTextTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "tap_text"
    override val description: String = "按界面文字点击按钮，参数：{\"text\":\"发送\"} 或 {\"texts\":[\"发送\",\"Send\",\"发送消息\"]}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val texts = mutableListOf<String>()
        (args["text"]?.toString())?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
        (args["texts"] as? List<*>)?.forEach { item ->
            item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
        }
        if (texts.isEmpty()) return ToolResult.failure("缺少参数 text 或 texts")

        // 优先使用无障碍点击：省去 uiautomator dump 的 shell 开销，速度更快。
        for (candidate in texts) {
            val clicked = AgentAccessibilityService.clickText(candidate)
            if (clicked == true) {
                return ToolResult.success(
                    "已通过无障碍服务按文字“$candidate”点击",
                    mapOf("text" to candidate, "texts" to texts, "source" to "accessibility"),
                )
            }
        }

        // 退而求其次：从无障碍节点快照里找文字并点中心，仍不需要 uiautomator dump。
        val a11yNodes = AgentAccessibilityService.currentNodes()
        val a11yNode = a11yNodes.firstOrNull { n ->
            texts.any { candidate ->
                n.text.contains(candidate, ignoreCase = true) || n.contentDesc.contains(candidate, ignoreCase = true)
            }
        }
        if (a11yNode != null) {
            val center = parseBoundsCenter(a11yNode.bounds)
            if (center != null) {
                if (AgentAccessibilityService.clickPoint(center.first, center.second) == true) {
                    return ToolResult.success(
                        "已通过无障碍节点定位并点击“${texts.first()}”",
                        mapOf("text" to a11yNode.text, "texts" to texts, "x" to center.first, "y" to center.second, "source" to "accessibility_nodes"),
                    )
                }
                if (AgentAccessibilityService.gestureTap(center.first, center.second) == true) {
                    return ToolResult.success(
                        "已通过无障碍手势点击“${texts.first()}”",
                        mapOf("text" to a11yNode.text, "texts" to texts, "x" to center.first, "y" to center.second, "source" to "accessibility_gesture"),
                    )
                }
            }
        }

        if (!shizuku.isAvailable()) {
            return ToolResult.failure("tap_text 需要 Shizuku 授权或开启无障碍服务")
        }
        val dumpFile = "/data/local/tmp/voiceconfig_tap_text.xml"
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        if (!dump.ok) {
            delay(300)
            for (candidate in texts) {
                val clicked = AgentAccessibilityService.clickText(candidate)
                if (clicked == true) {
                    return ToolResult.success(
                        "已通过无障碍服务按文字“$candidate”点击（uiautomator 失败后重试）",
                        mapOf("text" to candidate, "texts" to texts, "source" to "accessibility"),
                    )
                }
            }
            return ToolResult.failure("UI 层级获取失败：${dump.stderr.trim().ifBlank { "exit=${dump.exitCode}" }}")
        }
        val output = dump.stdout + "\n" + dump.stderr
        val dumpedPath = Regex("""dumped to:\s*(\S+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1) ?: dumpFile
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) {
                return ToolResult.failure("无法读取 UI 文件")
            }
            fallback.stdout
        }
        val nodes = UiDumpParser.parse(xml)
            .filter { node ->
                node.enabled && texts.any { candidate ->
                    node.text.contains(candidate, ignoreCase = true) || node.contentDesc.contains(candidate, ignoreCase = true)
                }
            }
        if (nodes.isEmpty()) {
            return ToolResult.failure("没有找到包含“$texts”的文字节点")
        }
        val node = nodes.minByOrNull { it.bounds.length } ?: nodes.first()
        val center = parseCenter(node.bounds) ?: return ToolResult.failure("无法解析节点坐标：${node.bounds}")
        val tap = shizuku.execute("input", "tap", center.first.toString(), center.second.toString())
        val matchedText = texts.firstOrNull { candidate ->
            node.text.contains(candidate, ignoreCase = true) || node.contentDesc.contains(candidate, ignoreCase = true)
        } ?: texts.first()
        return if (tap.ok) {
            ToolResult.success(
                "已按文字“$matchedText”点击 (${center.first}, ${center.second})",
                mapOf("text" to matchedText, "texts" to texts, "x" to center.first, "y" to center.second, "bounds" to node.bounds),
            )
        } else {
            ToolResult.failure("点击失败：${tap.stderr.trim().ifBlank { "exit=${tap.exitCode}" }}")
        }
    }

    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return null
        val x1 = m.groupValues[1].toIntOrNull() ?: return null
        val y1 = m.groupValues[2].toIntOrNull() ?: return null
        val x2 = m.groupValues[3].toIntOrNull() ?: return null
        val y2 = m.groupValues[4].toIntOrNull() ?: return null
        return (x1 + x2) / 2 to (y1 + y2) / 2
    }

    private fun parseCenter(bounds: String): Pair<Int, Int>? {
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return null
        val x1 = m.groupValues[1].toIntOrNull() ?: return null
        val y1 = m.groupValues[2].toIntOrNull() ?: return null
        val x2 = m.groupValues[3].toIntOrNull() ?: return null
        val y2 = m.groupValues[4].toIntOrNull() ?: return null
        return (x1 + x2) / 2 to (y1 + y2) / 2
    }
}
