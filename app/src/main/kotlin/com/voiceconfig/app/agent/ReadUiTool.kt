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
private fun com.voiceconfig.app.service.AccessibilityUiSnapshot.toUiNode(): UiDumpParser.UiNode =
    UiDumpParser.UiNode(
        text = text,
        contentDesc = contentDesc,
        resourceId = resourceId,
        className = className,
        bounds = bounds,
        clickable = clickable,
        focusable = focusable,
        enabled = true,
    )

@Singleton
class ReadUiTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    @Volatile
    private var lastUiMessage: String? = null
    @Volatile
    private var lastUiData: Map<String, Any?>? = null
    @Volatile
    private var lastUiAtMs: Long = 0L

    override val name: String = "read_ui"
    override val description: String = "读取当前页面 UI 元素（含绝对坐标），参数：{\"maxNodes\":120}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        fun success(message: String, data: Map<String, Any?>): ToolResult {
            lastUiMessage = message
            lastUiData = data
            lastUiAtMs = System.currentTimeMillis()
            return ToolResult.success(message, data)
        }

        fun staleFallback(): ToolResult? {
            val msg = lastUiMessage ?: return null
            val ageMs = System.currentTimeMillis() - lastUiAtMs
            if (ageMs > STALE_MAX_AGE_MS) return null
            // 如果已经切换到另一个 App，不能再返回旧 App 的快照，否则会严重误导模型。
            val lastPkg = (lastUiData ?: emptyMap())["foregroundPackage"] as? String
            val currentPkg = currentForegroundPackage()
            if (lastPkg != null && currentPkg != null && lastPkg != currentPkg) return null
            val staleData = (lastUiData ?: emptyMap()) + mapOf("stale" to true)
            return ToolResult.success(
                "UI 读取失败，返回 ${ageMs / 1000}s 前的上一次成功界面快照（可能已过期，请谨慎使用）：" + "\n" + "$msg",
                staleData,
            )
        }

        if (!shizuku.isAvailable()) {
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                return success(
                    "已通过无障碍服务读取当前界面：\n$a11y",
                    mapOf(
                        "source" to "accessibility",
                        "foregroundPackage" to AgentAccessibilityService.currentPackageName(),
                    ),
                )
            }
            return staleFallback() ?: ToolResult.failure("read_ui 需要 Shizuku 授权或开启无障碍服务")
        }
        val maxNodes = (args["maxNodes"] as? Number)?.toInt()?.coerceIn(10, 500) ?: 120
        val maxChars = (args["maxChars"] as? Number)?.toInt()?.coerceIn(500, 20_000) ?: 4_000

        // 优先使用无障碍服务读取：不需要 uiautomator dump，速度快很多。
        val a11ySnapshots = AgentAccessibilityService.currentNodes()
        val foregroundForA11y = currentForegroundPackage()
        val a11yMatchesForeground = a11ySnapshots.isNotEmpty() &&
            (foregroundForA11y == null || a11ySnapshots.any { it.packageName == foregroundForA11y })
        if (a11yMatchesForeground) {
            val uiNodes = a11ySnapshots.map {
                UiDumpParser.UiNode(
                    text = it.text,
                    contentDesc = it.contentDesc,
                    resourceId = it.resourceId,
                    className = it.className,
                    bounds = it.bounds,
                    clickable = it.clickable,
                    focusable = it.focusable,
                    enabled = true,
                )
            }
            val summary = UiDumpParser.summarizeNodes(uiNodes, maxNodes, maxChars)
            val overlay = OverlayDetector.analyze(uiNodes)
            val overlayData = buildOverlayData(overlay)
            val overlayNote = when (overlay.kind) {
                OverlayDetector.OverlayKind.PROMO_OVERLAY -> "\n注意：检测到营销/更新/广告弹窗，建议调用 dismiss_popups 关闭，不要用 tap 猜坐标。"
                OverlayDetector.OverlayKind.FUNCTIONAL_PICKER -> "\n注意：检测到功能性选择层（门店/商品等），请使用 tap_text 选择目标，不要关闭它。"
                OverlayDetector.OverlayKind.NONE -> ""
            }
            return success(
                summary + overlayNote,
                mapOf(
                    "ui" to summary,
                    "nodeCount" to uiNodes.size,
                    "bounds" to uiNodes.map { it.bounds },
                    "overlay" to overlayData,
                    "nodes" to uiNodes.map(::nodeMap),
                    "source" to "accessibility",
                    "foregroundPackage" to currentForegroundPackage(),
                    "timingMs" to mapOf("total_ms" to 0L),
                ),
            )
        }

        val timingMs = linkedMapOf<String, Long>()
        val totalStartMs = System.currentTimeMillis()

        val dumpFile = "/data/local/tmp/voiceconfig_ui.xml"
        val dumpStartMs = System.currentTimeMillis()
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        timingMs["dump_cmd_ms"] = System.currentTimeMillis() - dumpStartMs
        if (!dump.ok) {
            delay(300)
            val retryNodes = AgentAccessibilityService.currentNodes()
            if (retryNodes.isNotEmpty()) {
                return success(
                    "已通过无障碍服务读取当前界面（重试成功）",
                    mapOf(
                        "source" to "accessibility",
                        "foregroundPackage" to AgentAccessibilityService.currentPackageName(),
                        "ui" to UiDumpParser.summarizeNodes(retryNodes.map { it.toUiNode() }),
                    ),
                )
            }
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                return success(
                    "已通过无障碍服务读取当前界面（uiautomator dump 失败）:\n$a11y",
                    mapOf(
                        "source" to "accessibility",
                        "foregroundPackage" to AgentAccessibilityService.currentPackageName(),
                        "ui" to a11y,
                    ),
                )
            }
            return staleFallback() ?: ToolResult.failure("UI 层级获取失败：${dump.stderr.trim().ifBlank { "exit=${dump.exitCode}" }}")
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
                delay(300)
                val retryNodes = AgentAccessibilityService.currentNodes()
                if (retryNodes.isNotEmpty()) {
                    return success(
                        "已通过无障碍服务读取当前界面（无法读取 UI 文件，重试成功）",
                        mapOf(
                            "source" to "accessibility",
                            "foregroundPackage" to AgentAccessibilityService.currentPackageName(),
                            "ui" to UiDumpParser.summarizeNodes(retryNodes.map { it.toUiNode() }),
                        ),
                    )
                }
                val a11y = AgentAccessibilityService.currentSnapshot()
                if (!a11y.isNullOrBlank()) {
                    return success(
                        "已通过无障碍服务读取当前界面（无法读取 UI 文件）:\n$a11y",
                        mapOf(
                            "source" to "accessibility",
                            "foregroundPackage" to AgentAccessibilityService.currentPackageName(),
                            "ui" to a11y,
                        ),
                    )
                }
                return staleFallback() ?: ToolResult.failure("无法读取 UI 文件：${cat.stderr} ${fallback.stderr}")
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
                return success("已通过无障碍服务读取当前界面（uiautomator 窗口不一致）：\n$a11y", mapOf("source" to "accessibility", "foregroundPackage" to currentPackageFinal))
            }
            return staleFallback() ?: ToolResult.failure("UI 树与当前前台窗口不一致：dump=$dumpedPackageFinal foreground=$currentPackageFinal，请改用 read_screen 或重试")
        }
        val parseStartMs = System.currentTimeMillis()
        val allNodes = UiDumpParser.parse(finalXml)
        val summary = UiDumpParser.summarize(finalXml, maxNodes, maxChars = (args["maxChars"] as? Number)?.toInt()?.coerceIn(500, 20_000) ?: 4_000)
        val nodes = allNodes.filter { it.enabled && (it.hasLabel || it.clickable || it.focusable) }.take(maxNodes)
        val overlay = OverlayDetector.analyze(allNodes)
        val overlayData = buildOverlayData(overlay)
        val overlayNote = when (overlay.kind) {
            OverlayDetector.OverlayKind.PROMO_OVERLAY -> "\n注意：检测到营销/更新/广告弹窗，建议调用 dismiss_popups 关闭，不要用 tap 猜坐标。"
            OverlayDetector.OverlayKind.FUNCTIONAL_PICKER -> "\n注意：检测到功能性选择层（门店/商品等），请使用 tap_text 选择目标，不要关闭它。"
            OverlayDetector.OverlayKind.NONE -> ""
        }
        timingMs["parse_ms"] = System.currentTimeMillis() - parseStartMs
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        return success(
            summary + overlayNote,
            mapOf(
                "ui" to summary,
                "nodeCount" to nodes.size,
                "bounds" to nodes.map { it.bounds },
                "overlay" to overlayData,
                "nodes" to nodes.map(::nodeMap),
                "foregroundPackage" to currentForegroundPackage(),
                "timingMs" to timingMs,
            ),
        )
    }

    private fun nodeMap(node: UiDumpParser.UiNode): Map<String, Any?> = mapOf(
        "text" to node.text,
        "contentDesc" to node.contentDesc,
        "resourceId" to node.resourceId,
        "className" to node.className,
        "bounds" to node.bounds,
        "center" to OverlayDetector.center(node.bounds)?.let { listOf(it.first, it.second) },
        "clickable" to node.clickable,
        "focusable" to node.focusable,
    )

    private fun buildOverlayData(overlay: OverlayDetector.OverlayAnalysis): Map<String, Any?> = mapOf(
        "kind" to overlay.kind.name,
        "evidence" to overlay.evidence,
        "dismissCandidates" to overlay.candidates.take(5).map { candidate ->
            mapOf(
                "text" to candidate.node.text,
                "contentDesc" to candidate.node.contentDesc,
                "id" to candidate.node.resourceId,
                "class" to candidate.node.className,
                "bounds" to candidate.node.bounds,
                "center" to OverlayDetector.center(candidate.node.bounds)?.let { listOf(it.first, it.second) },
                "reason" to candidate.reason,
            )
        },
    )

    private fun currentForegroundPackage(): String? {
        AgentAccessibilityService.currentPackageName()?.let { return it }
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

    companion object {
        private const val STALE_MAX_AGE_MS = 60_000L
    }
}
