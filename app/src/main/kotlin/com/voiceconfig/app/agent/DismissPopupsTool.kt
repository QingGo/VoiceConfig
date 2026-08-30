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
    private val uiActionLayer: UiActionLayer,
) : AgentTool {

    override val name: String = "dismiss_popups"
    override val description: String = "检测并关闭当前界面的广告/更新/营销弹窗（通用，不依赖具体App），参数：{\"maxAttempts\":3}；功能性选择层不会自动关闭"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val maxAttempts = (args["maxAttempts"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 3
        val allowBack = (args["allowBack"] as? Boolean) ?: true

        // 先试无障碍直接点常见关闭文案，避免每次 uiautomator dump。
        val fastCloseTexts = listOf("关闭", "取消", "跳过", "我知道了", "知道了", "以后再说", "暂不", "稍后", "忽略")
        for (text in fastCloseTexts) {
            if (uiActionLayer.tapByText(text).ok) {
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

                OverlayDetector.OverlayKind.PERMISSION_OVERLAY -> {
                    val evidence = analysis.evidence.joinToString("；")
                    return ToolResult.success(
                        "当前是系统权限弹窗，不会自动关闭；请按任务需要选择允许/仅在使用期间允许。$evidence",
                        mapOf("kind" to "permission_overlay", "actions" to actions),
                    )
                }

                OverlayDetector.OverlayKind.TERMINAL_CONFIRM -> {
                    val evidence = analysis.evidence.joinToString("；")
                    return ToolResult.success(
                        "当前是终端确认页/操作（支付/发送/删除/配置等），不会自动关闭；请调用 wait_user 停在最后一步等真人确认。$evidence",
                        mapOf("kind" to "terminal_confirm", "actions" to actions),
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
                        val tapped = uiActionLayer.tapCenter(center.first, center.second).ok
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
            if (uiActionLayer.tapByText(text).ok) {
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
            if (uiActionLayer.tapByText(text).ok) {
                return ToolResult.success(
                    "已通过无障碍服务关闭弹窗（点击“$text”）",
                    mapOf("source" to "accessibility", "text" to text),
                )
            }
        }

        // 优先直接按资源 id 点击已知关闭 X（如确认订单页一键换购浮层）。
        for (resourceId in listOf(
            "com.lucky.luckyclient:id/close_iv",
            "com.lucky.luckyclient:id/close",
            "android:id/close",
            "android:id/close_iv",
        )) {
            if (uiActionLayer.tapById(resourceId).ok) {
                return ToolResult.success(
                    "已通过无障碍服务点击关闭浮层（$resourceId）",
                    mapOf("source" to "accessibility_resource", "resourceId" to resourceId),
                )
            }
        }

        // 优先识别 X/关闭图标（如确认订单页一键换购浮层的 close_iv）。
        val nodes = AgentAccessibilityService.currentNodes()
        val closeNode = nodes.firstOrNull { node ->
            val id = node.resourceId.lowercase()
            node.clickable && (
                id.contains("close_iv") || id.contains("iv_close") ||
                    id.contains("btn_close") || id.contains("close") ||
                    id.contains("dismiss") || id.contains("cancel")
                )
        }
        if (closeNode != null) {
            val center = parseBoundsCenter(closeNode.bounds)
            if (center != null && uiActionLayer.tapCenter(center.first, center.second).ok) {
                return ToolResult.success(
                    "已通过无障碍手势点击关闭浮层（${closeNode.resourceId}）",
                    mapOf("source" to "accessibility_gesture", "resourceId" to closeNode.resourceId, "x" to center.first, "y" to center.second),
                )
            }
        }
        return ToolResult.success(
            "未检测到需要关闭的广告/更新弹窗（无障碍快速模式）",
            mapOf("actions" to emptyList<String>(), "fast" to true),
        )
    }

    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return null
        return ((m.groupValues[1].toIntOrNull() ?: return null) + (m.groupValues[3].toIntOrNull() ?: return null)) / 2 to
            ((m.groupValues[2].toIntOrNull() ?: return null) + (m.groupValues[4].toIntOrNull() ?: return null)) / 2
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
