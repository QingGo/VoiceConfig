package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AgentAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 统一 UI 操作层。
 *
 * 所有手机 UI 自动化原语都收敛到这里：
 * - 优先使用 AccessibilityService 的节点语义（resource-id / text / content-desc）；
 * - 其次使用真实手势（dispatchGesture）；
 * - 坐标只作为最后兜底。
 *
 * LLM Agent 工具不应再各自实现一套“读树 + 点击”逻辑。
 */
@Singleton
class UiActionLayer @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) {

    /** 按资源 id 点击。 */
    suspend fun tapById(resourceId: String): UiActionResult {
        val id = resourceId.trim()
        if (id.isBlank()) return UiActionResult.failure("resourceId 不能为空")

        if (AgentAccessibilityService.clickResourceId(id) == true) {
            return UiActionResult.success("已通过无障碍点击 id=$id", mapOf("id" to id, "source" to "accessibility"))
        }

        val nodes = readNodes()
        val node = nodes.firstOrNull { it.resourceId == id || it.resourceId.endsWith("/$id") }
            ?: return UiActionResult.failure("未找到 id=$id 的节点")
        return tapNode(node, "id=$id")
    }

    /** 按文字点击，支持多个候选。 */
    suspend fun tapByText(vararg texts: String): UiActionResult {
        val candidates = texts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return UiActionResult.failure("缺少要点击的文字")

        for (text in candidates) {
            if (AgentAccessibilityService.clickText(text) == true) {
                return UiActionResult.success("已通过无障碍点击文字“$text”", mapOf("text" to text, "source" to "accessibility"))
            }
        }

        val nodes = readNodes()
        var matched: UiDumpParser.UiNode? = null
        var hitText = ""
        for (candidate in candidates) {
            val node = nodes.firstOrNull { n ->
                n.text.contains(candidate, ignoreCase = true) || n.contentDesc.contains(candidate, ignoreCase = true)
            }
            if (node != null) {
                matched = node
                hitText = candidate
                break
            }
        }
        val node = matched ?: return UiActionResult.failure("未找到包含“${candidates.joinToString("/")}”的节点")
        return tapNode(node, "text=$hitText")
    }

    /** 按 content-description 点击。 */
    suspend fun tapByDesc(desc: String): UiActionResult {
        val d = desc.trim()
        if (d.isBlank()) return UiActionResult.failure("contentDesc 不能为空")
        val nodes = readNodes()
        val node = nodes.firstOrNull { it.contentDesc.contains(d, ignoreCase = true) }
            ?: return UiActionResult.failure("未找到 contentDesc=$d 的节点")
        return tapNode(node, "desc=$d")
    }

    /** 按绝对坐标点击，仅作最后兜底。 */
    suspend fun tapCenter(x: Int, y: Int): UiActionResult {
        if (x < 0 || y < 0) return UiActionResult.failure("坐标不能为负数")
        if (AgentAccessibilityService.clickPoint(x, y) == true) {
            return UiActionResult.success("已通过无障碍节点点击 ($x, $y)", mapOf("x" to x, "y" to y, "source" to "accessibility"))
        }
        if (AgentAccessibilityService.gestureTap(x, y) == true) {
            return UiActionResult.success("已通过无障碍手势点击 ($x, $y)", mapOf("x" to x, "y" to y, "source" to "accessibility_gesture"))
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法点击 ($x, $y)：需要无障碍服务或 Shizuku")
        }
        val result = shizuku.execute("input", "tap", x.toString(), y.toString())
        return if (result.ok) {
            UiActionResult.success("已通过 shell 点击 ($x, $y)", mapOf("x" to x, "y" to y, "source" to "shizuku"))
        } else {
            UiActionResult.failure("点击失败：${result.output}")
        }
    }

    /** 滑动/长按。 */
    suspend fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Int = 300,
    ): UiActionResult {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return UiActionResult.failure("坐标不能为负数")
        val duration = durationMs.coerceIn(1, 10_000)
        if (AgentAccessibilityService.gestureSwipe(x1, y1, x2, y2, duration) == true) {
            return UiActionResult.success(
                "已通过无障碍手势滑动",
                mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to duration, "source" to "accessibility_gesture"),
            )
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法滑动：需要无障碍服务或 Shizuku")
        }
        val result = shizuku.execute("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString())
        return if (result.ok) {
            UiActionResult.success("已滑动", mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to duration))
        } else {
            UiActionResult.failure("滑动失败：${result.output}")
        }
    }

    /** 返回键。 */
    fun back(): UiActionResult {
        if (AgentAccessibilityService.pressBack() == true) {
            return UiActionResult.success("已通过无障碍发送返回键", mapOf("source" to "accessibility"))
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法按返回键：需要无障碍服务或 Shizuku")
        }
        val result = shizuku.execute("input", "keyevent", "4")
        return if (result.ok) {
            UiActionResult.success("已发送返回键", mapOf("source" to "shizuku"))
        } else {
            UiActionResult.failure("返回失败：${result.output}")
        }
    }

    /** Home 键。 */
    fun home(): UiActionResult {
        if (AgentAccessibilityService.pressHome() == true) {
            return UiActionResult.success("已通过无障碍发送 Home 键", mapOf("source" to "accessibility"))
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法发送 Home 键：需要无障碍服务或 Shizuku")
        }
        val result = shizuku.execute("input", "keyevent", "3")
        return if (result.ok) {
            UiActionResult.success("已发送 Home 键", mapOf("source" to "shizuku"))
        } else {
            UiActionResult.failure("Home 失败：${result.output}")
        }
    }

    /** 通用按键码。 */
    fun keycode(code: Int): UiActionResult {
        if (code < 0) return UiActionResult.failure("keycode 不能为负数")
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法发送按键 $code：需要 Shizuku")
        }
        val result = shizuku.execute("input", "keyevent", code.toString())
        return if (result.ok) {
            UiActionResult.success("已发送按键 $code", mapOf("keycode" to code, "source" to "shizuku"))
        } else {
            UiActionResult.failure("按键失败：${result.output}")
        }
    }

    /** 输入文本（复用 TextInputManager 的能力，但这里保持原语简洁）。 */
    suspend fun input(text: String): UiActionResult {
        if (text.isBlank()) return UiActionResult.failure("文本不能为空")
        if (AgentAccessibilityService.inputText(text) == true) {
            return UiActionResult.success("已通过无障碍输入文本", mapOf("text" to text, "source" to "accessibility_set_text"))
        }
        if (AgentAccessibilityService.paste() == true) {
            return UiActionResult.success("已通过无障碍粘贴输入文本", mapOf("text" to text, "source" to "accessibility_paste"))
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法输入文本：需要无障碍服务或 Shizuku")
        }
        val escaped = text
            .replace("%", "%s")
            .replace(" ", "%s")
            .replace("\"", "\\\"")
        val result = shizuku.execute("input", "text", escaped)
        return if (result.ok) {
            UiActionResult.success("已通过 shell 输入文本", mapOf("text" to text, "source" to "shizuku"))
        } else {
            UiActionResult.failure("输入失败：${result.output}")
        }
    }

    /** 轮询等待某个节点出现。 */
    suspend fun waitFor(
        selector: UiSelector,
        timeoutMs: Long = 5_000,
        intervalMs: Long = 300,
    ): UiActionResult {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val nodes = readNodes()
            if (findNode(nodes, selector) != null) {
                return UiActionResult.success("等待成功：${selector.describe()}", mapOf("selector" to selector.toMap(), "waitedMs" to (System.currentTimeMillis() - start)))
            }
            delay(intervalMs)
        }
        return UiActionResult.failure("等待超时：${selector.describe()}")
    }

    /** 断言节点可见。 */
    suspend fun assertVisible(selector: UiSelector): UiActionResult {
        val nodes = readNodes()
        val node = findNode(nodes, selector)
            ?: return UiActionResult.failure("断言失败：未找到 ${selector.describe()}", mapOf("selector" to selector.toMap(), "visible" to false))
        return UiActionResult.success("断言通过：${selector.describe()} 可见", mapOf("selector" to selector.toMap(), "visible" to true, "bounds" to node.bounds, "text" to node.text, "resourceId" to node.resourceId))
    }

    /** 断言节点不可见。 */
    suspend fun assertNotVisible(selector: UiSelector): UiActionResult {
        val nodes = readNodes()
        if (nodes.isEmpty()) {
            return UiActionResult.failure("断言失败：无法读取当前 UI，不能确认 ${selector.describe()} 不可见", mapOf("selector" to selector.toMap(), "visible" to null))
        }
        val node = findNode(nodes, selector)
        if (node == null) {
            return UiActionResult.success("断言通过：${selector.describe()} 不可见", mapOf("selector" to selector.toMap(), "visible" to false))
        }
        return UiActionResult.failure("断言失败：${selector.describe()} 仍然可见", mapOf("selector" to selector.toMap(), "visible" to true, "bounds" to node.bounds, "text" to node.text, "resourceId" to node.resourceId))
    }
    /** 读取当前 UI 树，返回统一快照（UI 摘要、节点、弹窗分析、来源、耗时）。 */
    suspend fun readUi(
        maxNodes: Int = 120,
        maxChars: Int = 4000,
    ): UiReadResult {
        val timingMs = linkedMapOf<String, Long>()
        val totalStartMs = System.currentTimeMillis()
        val maxN = maxNodes.coerceIn(10, 500)
        val maxC = maxChars.coerceIn(500, 20_000)

        // 优先使用无障碍服务：不需要 uiautomator dump，速度快很多。
        val a11yStartMs = System.currentTimeMillis()
        val a11ySnapshots = AgentAccessibilityService.currentNodes()
        timingMs["a11y_read_ms"] = System.currentTimeMillis() - a11yStartMs
        val foreground = currentForegroundPackage()
        val a11yMatchesForeground = a11ySnapshots.isNotEmpty() &&
            (foreground == null || a11ySnapshots.any { it.packageName == foreground })
        if (a11yMatchesForeground) {
            val uiNodes = a11ySnapshots.map { it.toUiNode() }
            val summary = UiDumpParser.summarizeNodes(uiNodes, maxN, maxC)
            val overlay = OverlayDetector.analyze(uiNodes)
            timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
            return UiReadResult(
                ok = true,
                message = "已通过无障碍服务读取当前界面：\n$summary${overlayNote(overlay)}",
                summary = summary,
                nodes = uiNodes,
                source = "accessibility",
                foregroundPackage = foreground,
                overlay = overlay,
                timingMs = timingMs,
            )
        }

        // 无 Shizuku 时只保留无障碍文本兜底。
        if (!shizuku.isAvailable()) {
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return UiReadResult(
                    ok = true,
                    message = "已通过无障碍服务读取当前界面：\n$a11y",
                    summary = a11y,
                    nodes = emptyList(),
                    source = "accessibility",
                    foregroundPackage = foreground,
                    overlay = null,
                    timingMs = timingMs,
                )
            }
            timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
            return UiReadResult(
                ok = false,
                message = "read_ui 需要 Shizuku 授权或开启无障碍服务",
                error = "read_ui 需要 Shizuku 授权或开启无障碍服务",
                foregroundPackage = foreground,
                timingMs = timingMs,
            )
        }

        // Shizuku uiautomator dump 路径。
        val dumpFile = "/data/local/tmp/voiceconfig_ui.xml"
        val dumpStartMs = System.currentTimeMillis()
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        timingMs["dump_cmd_ms"] = System.currentTimeMillis() - dumpStartMs
        if (!dump.ok) {
            delay(300)
            val retryNodes = AgentAccessibilityService.currentNodes()
            if (retryNodes.isNotEmpty()) {
                val uiNodes = retryNodes.map { it.toUiNode() }
                val summary = UiDumpParser.summarizeNodes(uiNodes, maxN, maxC)
                val overlay = OverlayDetector.analyze(uiNodes)
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return UiReadResult(
                    ok = true,
                    message = "已通过无障碍服务读取当前界面（重试成功）\n$summary${overlayNote(overlay)}",
                    summary = summary,
                    nodes = uiNodes,
                    source = "accessibility",
                    foregroundPackage = currentForegroundPackage(),
                    overlay = overlay,
                    timingMs = timingMs,
                )
            }
            val a11y = AgentAccessibilityService.currentSnapshot()
            if (!a11y.isNullOrBlank()) {
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return UiReadResult(
                    ok = true,
                    message = "已通过无障碍服务读取当前界面（uiautomator dump 失败）:\n$a11y",
                    summary = a11y,
                    nodes = emptyList(),
                    source = "accessibility",
                    foregroundPackage = currentForegroundPackage(),
                    overlay = null,
                    timingMs = timingMs,
                )
            }
            timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
            return UiReadResult(
                ok = false,
                message = "UI 层级获取失败：${dump.stderr.trim().ifBlank { "exit=${dump.exitCode}" }}",
                error = "UI 层级获取失败：${dump.stderr.trim().ifBlank { "exit=${dump.exitCode}" }}",
                foregroundPackage = currentForegroundPackage(),
                timingMs = timingMs,
            )
        }

        // 解析 dump 实际路径并读取 XML。
        val dumpOutput = dump.stdout + "\n" + dump.stderr
        val dumpedPath = Regex("""dumped to:\s*(\S+)""", RegexOption.IGNORE_CASE)
            .find(dumpOutput)?.groupValues?.getOrNull(1) ?: dumpFile
        val catStartMs = System.currentTimeMillis()
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) {
                delay(300)
                val retryNodes = AgentAccessibilityService.currentNodes()
                if (retryNodes.isNotEmpty()) {
                    val uiNodes = retryNodes.map { it.toUiNode() }
                    val summary = UiDumpParser.summarizeNodes(uiNodes, maxN, maxC)
                    val overlay = OverlayDetector.analyze(uiNodes)
                    timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                    return UiReadResult(
                        ok = true,
                        message = "已通过无障碍服务读取当前界面（无法读取 UI 文件，重试成功）\n$summary${overlayNote(overlay)}",
                        summary = summary,
                        nodes = uiNodes,
                        source = "accessibility",
                        foregroundPackage = currentForegroundPackage(),
                        overlay = overlay,
                        timingMs = timingMs,
                    )
                }
                val a11y = AgentAccessibilityService.currentSnapshot()
                if (!a11y.isNullOrBlank()) {
                    timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                    return UiReadResult(
                        ok = true,
                        message = "已通过无障碍服务读取当前界面（无法读取 UI 文件）:\n$a11y",
                        summary = a11y,
                        nodes = emptyList(),
                        source = "accessibility",
                        foregroundPackage = currentForegroundPackage(),
                        overlay = null,
                        timingMs = timingMs,
                    )
                }
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return UiReadResult(
                    ok = false,
                    message = "无法读取 UI 文件：${cat.stderr} ${fallback.stderr}",
                    error = "无法读取 UI 文件：${cat.stderr} ${fallback.stderr}",
                    foregroundPackage = currentForegroundPackage(),
                    timingMs = timingMs,
                )
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
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return UiReadResult(
                    ok = true,
                    message = "已通过无障碍服务读取当前界面（uiautomator 窗口不一致）：\n$a11y",
                    summary = a11y,
                    nodes = emptyList(),
                    source = "accessibility",
                    foregroundPackage = currentPackageFinal,
                    overlay = null,
                    timingMs = timingMs,
                )
            }
            timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
            return UiReadResult(
                ok = false,
                message = "UI 树与当前前台窗口不一致：dump=$dumpedPackageFinal foreground=$currentPackageFinal，请改用 read_screen 或重试",
                error = "UI 树与当前前台窗口不一致：dump=$dumpedPackageFinal foreground=$currentPackageFinal，请改用 read_screen 或重试",
                foregroundPackage = currentPackageFinal,
                timingMs = timingMs,
            )
        }

        val parseStartMs = System.currentTimeMillis()
        val allNodes = UiDumpParser.parse(finalXml)
        val summary = UiDumpParser.summarize(finalXml, maxN, maxC)
        val nodes = allNodes.filter { it.enabled && (it.hasLabel || it.clickable || it.focusable) }.take(maxN)
        val overlay = OverlayDetector.analyze(allNodes)
        timingMs["parse_ms"] = System.currentTimeMillis() - parseStartMs
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        return UiReadResult(
            ok = true,
            message = summary + overlayNote(overlay),
            summary = summary,
            nodes = nodes,
            source = "uiautomator",
            foregroundPackage = currentPackageFinal,
            overlay = overlay,
            timingMs = timingMs,
        )
    }

    /** 当前前台包名（优先无障碍，其次 Shizuku dumpsys）。 */
    fun currentForegroundPackage(): String? {
        AgentAccessibilityService.currentPackageName()?.let { return it }
        if (!shizuku.isAvailable()) return null
        val result = shizuku.execute("dumpsys", "activity", "activities")
        if (!result.ok) return null
        val match = Regex("""topResumedActivity=.*?\s([A-Za-z0-9_.]+)/""")
            .find(result.stdout)
            ?: Regex("""mResumedActivity=.*?\s([A-Za-z0-9_.]+)/""")
                .find(result.stdout)
        return match?.groupValues?.getOrNull(1)
    }

    private fun overlayNote(overlay: OverlayDetector.OverlayAnalysis?): String = when (overlay?.kind) {
        OverlayDetector.OverlayKind.PROMO_OVERLAY -> "\n注意：检测到营销/更新/广告弹窗，建议调用 dismiss_popups 关闭，不要用 tap 猜坐标。"
        OverlayDetector.OverlayKind.FUNCTIONAL_PICKER -> "\n注意：检测到功能性选择层（门店/商品等），请使用 tap_text 选择目标，不要关闭它。"
        OverlayDetector.OverlayKind.PERMISSION_OVERLAY -> "\n注意：检测到系统权限弹窗，任务需要时选择“允许/仅在使用期间允许”，不要点击拒绝或关闭。"
        OverlayDetector.OverlayKind.TERMINAL_CONFIRM -> "\n注意：检测到终端确认页（支付/发送/删除/配置等），不要 dismiss，请调用 wait_user 停在最后一步等待真人确认。"
        else -> ""
    }

    private fun dumpUiXml(dumpedPath: String?): String? {
        val path = dumpedPath ?: return null
        val cat = shizuku.execute("cat", path)
        if (cat.ok && cat.stdout.isNotBlank()) return cat.stdout
        val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
        return if (fallback.ok && fallback.stdout.isNotBlank()) fallback.stdout else null
    }



    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private suspend fun tapNode(node: UiDumpParser.UiNode, label: String): UiActionResult {
        val center = parseCenter(node.bounds) ?: return UiActionResult.failure("节点坐标无法解析：${node.bounds}")
        // 如果能拿到无障碍原始节点，优先 performAction(ACTION_CLICK)。
        if (node.resourceId.isNotBlank() && AgentAccessibilityService.clickResourceId(node.resourceId) == true) {
            return UiActionResult.success(
                "已通过无障碍点击 $label（${node.resourceId}）",
                mapOf("id" to node.resourceId, "x" to center.first, "y" to center.second, "source" to "accessibility"),
            )
        }
        if (AgentAccessibilityService.clickPoint(center.first, center.second) == true) {
            return UiActionResult.success("已通过无障碍节点点击 $label", mapOf("x" to center.first, "y" to center.second, "source" to "accessibility"))
        }
        if (AgentAccessibilityService.gestureTap(center.first, center.second) == true) {
            return UiActionResult.success("已通过无障碍手势点击 $label", mapOf("x" to center.first, "y" to center.second, "source" to "accessibility_gesture"))
        }
        if (!shizuku.isAvailable()) {
            return UiActionResult.failure("无法点击 $label：需要无障碍服务或 Shizuku")
        }
        val result = shizuku.execute("input", "tap", center.first.toString(), center.second.toString())
        return if (result.ok) {
            UiActionResult.success("已通过 shell 点击 $label", mapOf("x" to center.first, "y" to center.second, "source" to "shizuku"))
        } else {
            UiActionResult.failure("点击失败：${result.output}")
        }
    }

    private fun readNodes(): List<UiDumpParser.UiNode> {
        val a11y = AgentAccessibilityService.currentNodes()
        if (a11y.isNotEmpty()) {
            return a11y.map {
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
        }
        if (!shizuku.isAvailable()) return emptyList()
        val dumpFile = "/data/local/tmp/voiceconfig_uiaction.xml"
        val dump = shizuku.execute("uiautomator", "dump", dumpFile)
        if (!dump.ok) return emptyList()
        val output = dump.stdout + "\n" + dump.stderr
        val dumpedPath = Regex("""dumped to:\s*(\S+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1) ?: dumpFile
        val cat = shizuku.execute("cat", dumpedPath)
        val xml = if (cat.ok && cat.stdout.isNotBlank()) cat.stdout else {
            val fallback = shizuku.execute("cat", "/sdcard/window_dump.xml")
            if (!fallback.ok || fallback.stdout.isBlank()) return emptyList()
            fallback.stdout
        }
        return UiDumpParser.parse(xml)
    }

    private fun findNode(nodes: List<UiDumpParser.UiNode>, selector: UiSelector): UiDumpParser.UiNode? {
        if (selector.resourceId.isNotBlank()) {
            return nodes.firstOrNull { it.resourceId == selector.resourceId || it.resourceId.endsWith("/${selector.resourceId}") }
        }
        if (selector.text.isNotBlank()) {
            return nodes.firstOrNull { it.text == selector.text || it.text.contains(selector.text, ignoreCase = true) }
        }
        if (selector.desc.isNotBlank()) {
            return nodes.firstOrNull { it.contentDesc == selector.desc || it.contentDesc.contains(selector.desc, ignoreCase = true) }
        }
        return null
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

/** 通用 UI 选择器。 */
data class UiSelector(
    val resourceId: String = "",
    val text: String = "",
    val desc: String = "",
) {
    fun describe(): String = when {
        resourceId.isNotBlank() -> "id=$resourceId"
        text.isNotBlank() -> "text=$text"
        desc.isNotBlank() -> "desc=$desc"
        else -> "空选择器"
    }

    fun toMap(): Map<String, String> = mapOf(
        "resourceId" to resourceId,
        "text" to text,
        "desc" to desc,
    )
}

/** UiActionLayer 的统一返回结构。 */
data class UiActionResult(
    val ok: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun success(message: String, data: Map<String, Any?> = emptyMap()): UiActionResult =
            UiActionResult(true, message, data)

        fun failure(message: String, data: Map<String, Any?> = emptyMap()): UiActionResult =
            UiActionResult(false, message, data)
    }
}

/** UiActionLayer 的统一 UI 树读取结果。 */
data class UiReadResult(
    val ok: Boolean,
    val message: String,
    val summary: String = "",
    val nodes: List<UiDumpParser.UiNode> = emptyList(),
    val source: String = "",
    val foregroundPackage: String? = null,
    val overlay: OverlayDetector.OverlayAnalysis? = null,
    val timingMs: Map<String, Long> = emptyMap(),
    val stale: Boolean = false,
    val error: String? = null,
)

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

