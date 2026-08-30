package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取当前页面 UI 层级（XML + 绝对坐标）。
 *
 * 统一委托给 [UiActionLayer]；本工具只负责参数解析、结果缓存和过期快照兜底。
 */
@Singleton
class ReadUiTool @Inject constructor(
    private val uiActionLayer: UiActionLayer,
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
        val maxNodes = (args["maxNodes"] as? Number)?.toInt()?.coerceIn(10, 500) ?: 120
        val maxChars = (args["maxChars"] as? Number)?.toInt()?.coerceIn(500, 20_000) ?: 4_000
        val result = uiActionLayer.readUi(maxNodes, maxChars)

        if (!result.ok) {
            return staleFallback() ?: ToolResult.failure(result.error ?: result.message)
        }

        val data = buildData(result)
        lastUiMessage = result.message
        lastUiData = data
        lastUiAtMs = System.currentTimeMillis()
        return ToolResult.success(result.message, data)
    }

    private fun buildData(result: UiReadResult): Map<String, Any?> = linkedMapOf(
        "ui" to result.summary,
        "nodeCount" to result.nodes.size,
        "bounds" to result.nodes.map { it.bounds },
        "overlay" to result.overlay?.let(::buildOverlayData),
        "nodes" to result.nodes.map(::nodeMap),
        "source" to result.source,
        "foregroundPackage" to result.foregroundPackage,
        "timingMs" to result.timingMs,
    ).also { map ->
        if (result.stale) map["stale"] = true
    }

    private fun staleFallback(): ToolResult? {
        val msg = lastUiMessage ?: return null
        val ageMs = System.currentTimeMillis() - lastUiAtMs
        if (ageMs > STALE_MAX_AGE_MS) return null
        // 如果已经切换到另一个 App，不能再返回旧 App 的快照，否则会严重误导模型。
        val lastPkg = (lastUiData ?: emptyMap())["foregroundPackage"] as? String
        val currentPkg = uiActionLayer.currentForegroundPackage()
        if (lastPkg != null && currentPkg != null && lastPkg != currentPkg) return null
        val staleData = (lastUiData ?: emptyMap()) + mapOf("stale" to true)
        return ToolResult.success(
            "UI 读取失败，返回 ${ageMs / 1000}s 前的上一次成功界面快照（可能已过期，请谨慎使用）：" + "\n" + "$msg",
            staleData,
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

    companion object {
        private const val STALE_MAX_AGE_MS = 60_000L
    }
}
