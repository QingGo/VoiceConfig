package com.voiceconfig.app.agent

/**
 * 通用弹窗/覆盖层识别器。
 *
 * 目标：不针对某个 App 写死，而是基于 Android UI 树的通用特征判断：
 * - dialog/popup/modal/bottom_sheet 等容器
 * - 关闭/跳过/取消/知道了 等文案
 * - close/cancel/dismiss/skip 等资源 id
 * - 是否属于功能性选择层（如门店选择、商品选择），避免误关
 */
object OverlayDetector {

    enum class OverlayKind {
        NONE,
        PROMO_OVERLAY,
        FUNCTIONAL_PICKER,
        PERMISSION_OVERLAY,
        TERMINAL_CONFIRM,
    }

    data class DismissCandidate(
        val node: UiDumpParser.UiNode,
        val reason: String,
        val score: Int,
    )

    data class OverlayAnalysis(
        val kind: OverlayKind,
        val candidates: List<DismissCandidate>,
        val evidence: List<String>,
    )

    private val dismissTexts = listOf(
        "关闭", "取消", "跳过", "我知道了", "知道了", "以后再说", "暂不",
        "稍后", "忽略", "不用了", "不需要", "不感兴趣", "关闭广告",
        "close", "dismiss", "skip", "cancel", "no thanks", "not now",
        "×", "✕", "x",
    )

    private val promoTexts = listOf(
        "更新", "升级", "广告", "活动", "优惠", "福利", "领取", "推广",
        "营销", "红包", "弹窗", "立即更新", "马上更新", "新版本",
        "限时", "促销", "推荐", "惊喜", "会员", "邀请",
    )

    private val permissionTexts = listOf(
        "权限", "允许", "拒绝", "仅在使用期间允许", "仅在使用应用时允许",
        "始终允许", "使用应用时允许", "相机", "定位", "通知", "存储",
        "麦克风", "通讯录", "照片", "文件", "电话", "传感器",
    )

    private val terminalTexts = listOf(
        "支付", "付款", "确认支付", "立即支付", "提交订单", "确认订单",
        "去支付", "购买", "下单", "免密支付", "发送", "确认发送", "删除",
        "确认删除", "格式化", "清除数据", "恢复出厂", "解除绑定", "退出登录",
        "关机", "重启", "修改配置", "保存配置", "提交", "确认",
    )

    private val allowActionTexts = listOf(
        "允许", "仅在使用期间允许", "仅在使用应用时允许", "始终允许", "使用应用时允许",
    )

    private val denyActionTexts = listOf(
        "拒绝", "不允许", "禁止", "取消",
    )


    fun analyze(nodes: List<UiDumpParser.UiNode>): OverlayAnalysis {
        val evidence = mutableListOf<String>()

        val dialogLike = nodes.filter { node ->
            val hay = (node.resourceId + " " + node.className).lowercase()
            listOf("dialog", "popup", "modal", "alert", "bottom_sheet", "sheet", "webview_dialog", "portal-modal", "parentpanel", "custompanel")
                .any { hay.contains(it) }
        }
        if (dialogLike.isNotEmpty()) {
            evidence += "检测到弹窗/覆盖层容器：${dialogLike.take(3).joinToString { it.resourceId.ifBlank { it.className } }}"
        }

        val dismissNodes = nodes.filter { node ->
            isDismissCandidate(node)
        }
        val promoNodes = nodes.filter { node ->
            val text = (node.text + " " + node.contentDesc).lowercase()
            promoTexts.any { text.contains(it.lowercase()) }
        }

        val candidates = dismissNodes
            .mapNotNull { node -> toCandidate(node) }
            .sortedByDescending { it.score }

        val hasPromoSignal = dialogLike.isNotEmpty() && (promoNodes.isNotEmpty() || candidates.isNotEmpty())
            || promoNodes.any { it.text.contains("立即更新") || it.text.contains("马上更新") }
            || nodes.any { it.resourceId.lowercase().contains("webview_dialog") && candidates.isNotEmpty() }

        val allText = nodes.map { (it.text + " " + it.contentDesc).lowercase() }.joinToString(" ")

        // 系统权限弹窗：不能关闭，应选择“允许/仅在使用期间允许”。
        val hasPermissionSignal = permissionTexts.any { allText.contains(it.lowercase()) } &&
            (dialogLike.isNotEmpty() || allowActionTexts.any { allText.contains(it.lowercase()) } || denyActionTexts.any { allText.contains(it.lowercase()) })

        // 终端确认（支付/发送/删除/配置/远程破坏性）：只能停在确认页等真人。
        val hasTerminalSignal = terminalTexts.any { allText.contains(it.lowercase()) }

        // 通用“可关闭浮层”：即使没有典型 dialog/promo 文案，只要存在明显 X/关闭图标，
        // 也优先视为需要关闭的额外浮层（例如确认订单页的换购/免密浮层）。
        val hasCloseableOverlay = dismissNodes.any { isCloseIcon(it) }

        // 功能性选择层：有多个可点击文本选项，且没有明显营销/更新/权限/终端信号。
        val clickableOptionRows = nodes.filter { node ->
            node.clickable && node.text.isNotBlank()
        }
        val functionalPicker = dialogLike.isNotEmpty() &&
            clickableOptionRows.size >= 2 &&
            !hasPromoSignal &&
            !hasPermissionSignal &&
            !hasTerminalSignal &&
            !hasCloseableOverlay

        return when {
            hasPermissionSignal -> OverlayAnalysis(
                kind = OverlayKind.PERMISSION_OVERLAY,
                candidates = candidates,
                evidence = evidence + "检测到系统权限弹窗，任务需要时应选择允许/仅在使用期间允许，不要关闭",
            )
            hasCloseableOverlay || hasPromoSignal -> OverlayAnalysis(
                kind = OverlayKind.PROMO_OVERLAY,
                candidates = candidates,
                evidence = evidence + if (hasCloseableOverlay) "检测到可关闭浮层（存在关闭图标/按钮）" else "检测到需要关闭的营销/更新/广告弹窗",
            )
            functionalPicker -> OverlayAnalysis(
                kind = OverlayKind.FUNCTIONAL_PICKER,
                candidates = candidates,
                evidence = evidence + "检测到功能性选择层（含多个可选项），不应自动关闭",
            )
            hasTerminalSignal -> OverlayAnalysis(
                kind = OverlayKind.TERMINAL_CONFIRM,
                candidates = candidates,
                evidence = evidence + "检测到终端确认页/操作（支付/发送/删除/配置等），不应关闭，应调用 wait_user 停在最后一步",
            )
            else -> OverlayAnalysis(OverlayKind.NONE, emptyList(), evidence)
        }
    }
    fun isDismissCandidate(node: UiDumpParser.UiNode): Boolean {
        val text = (node.text + " " + node.contentDesc).trim().lowercase()
        if (dismissTexts.any { text == it.lowercase() || text.contains(it.lowercase()) }) return true
        val id = node.resourceId.lowercase()
        return listOf("close", "cancel", "dismiss", "skip", "btnbottomclose", "btn_close", "iv_close", "ivclose", "close_iv")
            .any { id.contains(it) }
    }

    private fun isCloseIcon(node: UiDumpParser.UiNode): Boolean {
        val id = node.resourceId.lowercase()
        return id.contains("close_iv") || id.contains("iv_close") || id.contains("btn_close") ||
            id.contains("close_btn") || id.contains("_close") || id == "close" || id.contains("btnclose")
    }

    private fun toCandidate(node: UiDumpParser.UiNode): DismissCandidate? {
        val text = (node.text + " " + node.contentDesc).trim()
        val id = node.resourceId.lowercase()
        var score = 0
        val reason: String
        when {
            id.contains("btnbottomclose") || id.contains("bottomclose") || id.contains("btn_close") || id.contains("iv_close") -> {
                score = 120
                reason = "底部关闭按钮(${node.resourceId})"
            }
            dismissTexts.any { text == it || text.contains(it) } && id.contains("close") -> {
                score = 110
                reason = "关闭文案+close id"
            }
            dismissTexts.any { text == it || text.contains(it) } -> {
                score = 100
                reason = "关闭/取消/跳过文案"
            }
            id.contains("close") || id.contains("dismiss") || id.contains("cancel") || id.contains("skip") -> {
                score = 90
                reason = "关闭类资源 id"
            }
            else -> return null
        }
        if (!node.clickable && !node.focusable) score -= 20
        return DismissCandidate(node, reason, score)
    }

    fun center(bounds: String): Pair<Int, Int>? {
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return null
        val x1 = m.groupValues[1].toIntOrNull() ?: return null
        val y1 = m.groupValues[2].toIntOrNull() ?: return null
        val x2 = m.groupValues[3].toIntOrNull() ?: return null
        val y2 = m.groupValues[4].toIntOrNull() ?: return null
        return (x1 + x2) / 2 to (y1 + y2) / 2
    }
}