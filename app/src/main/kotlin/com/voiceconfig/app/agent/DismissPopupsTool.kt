package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 通用弹窗关闭工具。
 *
 * 与 app 无关：根据 UI 树的资源 id、文案、容器类型识别“营销/更新/广告”弹窗，
 * 使用真实节点中心坐标点击关闭，而不是让 LLM 从截图猜像素。
 *
 * 对功能性选择层（门店选择、商品选择等）不会自动关闭，避免误操作。
 */
@Singleton
class DismissPopupsTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "dismiss_popups"
    override val description: String = "检测并关闭当前界面的广告/更新/营销弹窗（通用，不依赖具体App），参数：{\"maxAttempts\":3}；功能性选择层不会自动关闭"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val maxAttempts = (args["maxAttempts"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 3
        val allowBack = (args["allowBack"] as? Boolean) ?: true

        // 先试无障碍直接点常见关闭文案，避免每次 uiautomator dump。
        val fastCloseTexts = listOf("关闭", "取消", "跳过", "我知道了", "知道了", "以后再说", "暂不", "稍后", "忽略")
        for (text in fastCloseTexts) {
            if (AgentAccessibilityService.clickText(text) == true) {
                return ToolResult.success(
                    "已通过无障碍服务关闭弹窗（点击“$text”）",
                    mapOf("source" to "accessibility", "text" to text),
                )
            }
        }

        if (!shizuku.isAvailable()) {
            return dismissWithAccessibility()
        }

        val actions = mutableListOf<String>()
        val usedKeys = mutableSetOf<String>()
        var usedBack = false
        val a11yNodes = accessibilityUiNodes()
        var currentNodes: List<UiDumpParser.UiNode> =
            if (a11yNodes.isNotEmpty()) {
                a11yNodes
            } else {
                readNodesShizuku() ?: run {
                    if (!allowBack) {
                        return ToolResult.failure("无法读取当前 UI 树，且已禁用返回键兜底，无法检测弹窗")
                    }
                    // UI 树获取失败时退化为按一次返回键，很多弹窗可被关闭。
                    val back = shizuku.execute("input", "keyevent", "4")
                    if (back.ok) {
                        return ToolResult.success(
                            "UI 树获取失败，已尝试按返回键关闭弹窗",
                            mapOf("actions" to listOf("back_fallback"), "source" to "back_fallback"),
                        )
                    }
                    return ToolResult.failure("无法读取当前 UI 树，无法检测弹窗")
                }
            }

        for (attempt in 1..maxAttempts) {
            val analysis = OverlayDetector.analyze(currentNodes)
            when (analysis.kind) {
                OverlayDetector.OverlayKind.NONE -> {
                    return if (actions.isEmpty()) {
                        ToolResult.success("当前未检测到需要关闭的广告/更新弹窗", mapOf("actions" to actions))
                    } else {
                        ToolResult.success(
                            "弹窗已关闭：${actions.joinToString("；")}",
                            mapOf("actions" to actions, "detected" to true),
                        )
                    }
                }

                OverlayDetector.OverlayKind.FUNCTIONAL_PICKER -> {
                    val evidence = analysis.evidence.joinToString("；")
                    return ToolResult.success(
                        "当前是功能性选择层（如门店/商品选择），不会自动关闭。请使用 tap_text 选择目标。$evidence",
                        mapOf("kind" to "functional_picker", "actions" to actions),
                    )
                }

                OverlayDetector.OverlayKind.PROMO_OVERLAY -> {
                    if (analysis.candidates.isNotEmpty()) {
                        val candidate = analysis.candidates.firstOrNull { candidate ->
                            val key = candidate.node.resourceId + "|" + candidate.node.bounds
                            key !in usedKeys && OverlayDetector.center(candidate.node.bounds) != null
                        } ?: return ToolResult.failure("检测到弹窗但已尝试所有可解析关闭按钮，仍未关闭")
                        val center = OverlayDetector.center(candidate.node.bounds)!!
                        usedKeys += candidate.node.resourceId + "|" + candidate.node.bounds
                        val tapped =
                            if (AgentAccessibilityService.gestureTap(center.first, center.second) == true) {
                                true
                            } else {
                                shizuku.execute("input", "tap", center.first.toString(), center.second.toString()).ok
                            }
                        if (!tapped) {
                            return ToolResult.failure("点击关闭按钮失败：无法通过无障碍或 Shizuku 点击")
                        }
                        actions += "关闭(${candidate.reason}) at ${center.first},${center.second}"
                        delay(450)
                        val nextNodes = readNodesShizuku()
                        if (nextNodes == null) break
                        val next = OverlayDetector.analyze(nextNodes)
                        if (next.kind != OverlayDetector.OverlayKind.PROMO_OVERLAY || next.candidates.isEmpty()) {
                            currentNodes = nextNodes
                            continue
                        }
                        currentNodes = nextNodes
                    } else {
                        if (usedBack || !allowBack) {
                            return ToolResult.success("检测到可关闭浮层但未找到可点击关闭按钮，未按返回键", mapOf("actions" to actions, "skipped" to true))
                        }
                        val back = shizuku.execute("input", "keyevent", "4")
                        if (!back.ok) {
                            return ToolResult.failure("按返回键失败：${back.stderr.ifBlank { "exit=${back.exitCode}" }}")
                        }
                        usedBack = true
                        actions += "按返回键"
                        delay(450)
                        val nextNodes = readNodesShizuku()
                        if (nextNodes == null) break
                        currentNodes = nextNodes
                    }
                }
            }
        }

        return ToolResult.success(
            if (actions.isEmpty()) "未检测到可关闭弹窗" else "尝试关闭弹窗：${actions.joinToString("；")}",
            mapOf("actions" to actions),
        )
    }

    /**
     * 快速模式：只尝试无障碍点击常见关闭文案，不做 uiautomator dump。
     * 用于 open_app 之后的自动清理，避免拖慢打开 App 的速度。
     */
    suspend fun dismissFast(): ToolResult {
        val texts = listOf("关闭", "取消", "跳过", "我知道了", "知道了", "以后再说", "暂不", "稍后", "忽略")
        for (text in texts) {
            if (AgentAccessibilityService.clickText(text) == true) {
                return ToolResult.success(
                    "已通过无障碍服务快速关闭弹窗（点击“$text”）",
                    mapOf("source" to "accessibility", "text" to text, "fast" to true),
                )
            }
        }
        return ToolResult.success(
            "未发现需要快速关闭的弹窗",
            mapOf("fast" to true, "actions" to emptyList<String>()),
        )
    }

    private suspend fun dismissWithAccessibility(): ToolResult {
        val texts = listOf("关闭", "取消", "跳过", "我知道了", "知道了", "以后再说", "暂不", "稍后", "忽略")
        for (text in texts) {
            if (AgentAccessibilityService.clickText(text) == true) {
                return ToolResult.success(
                    "已通过无障碍服务关闭弹窗（点击“$text”）",
                    mapOf("source" to "accessibility", "text" to text),
                )
            }
        }
        return ToolResult.failure("未检测到可关闭弹窗；请确认是否需要 Shizuku 授权")
    }

    private fun accessibilityUiNodes(): List<UiDumpParser.UiNode> =
        AgentAccessibilityService.currentNodes().map {
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

    private fun readNodesShizuku(): List<UiDumpParser.UiNode>? {
        val dumpFile = "/data/local/tmp/voiceconfig_dismiss.xml"
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        if (!dump.ok) return null
        val output = dump.stdout + "\n" + dump.stderr
        val dumpedPath = Regex("""dumped to:\s*(\S+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1) ?: dumpFile
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) null else fallback.stdout
        } ?: return null
        return UiDumpParser.parse(xml)
    }
}
