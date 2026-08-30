package com.voiceconfig.app.agent

/**
 * 终端安全闸门。
 *
 * 当 Agent 已经到达“最终确认/支付/发送”页面时，无论任务计划是否把剩余步骤
 * 标记为完成，都必须进入 WAITING_CONFIRM，而不是继续操作或把任务标记为 DONE。
 *
 * 该闸门不依赖 LLM 自律，是 StopVerifier 的确定性补充。
 */
object TerminalSafetyGate {

    /** 支付/订单类终端页面标识。 */
    val PAYMENT_TERMINAL_MARKERS = listOf(
        "确认订单",
        "确认下单",
        "免密支付",
        "免密",
        "提交订单",
        "确认支付",
        "立即支付",
        "确认付款",
        "付款",
    )

    /** 消息发送类终端页面标识。 */
    val SEND_TERMINAL_MARKERS = listOf(
        "确认发送",
        "发送消息确认",
        "确认发送消息",
        "发送",
        "send",
    )

    /** 删除/清空类终端页面标识。 */
    val DELETE_TERMINAL_MARKERS = listOf(
        "确认删除",
        "删除确认",
        "永久删除",
        "确认清空",
        "清空确认",
    )

    /** 配置修改类终端页面标识。 */
    val CONFIG_TERMINAL_MARKERS = listOf(
        "确认修改",
        "确认覆盖",
        "保存更改",
        "确认保存",
    )

    /** 智能家居安防域终端页面标识。 */
    val HOME_SECURITY_TERMINAL_MARKERS = listOf(
        "确认撤防",
        "确认解除",
        "关闭安防",
        "远程开门",
        "确认开门",
        "解锁确认",
    )

    /** 远程破坏性操作终端页面/命令标识。 */
    val REMOTE_DESTRUCTIVE_MARKERS = listOf(
        "确认删除文件",
        "确认覆盖文件",
        "rm -rf",
        "确认重启",
        "确认关机",
    )

    /** 目标文本中的通信意图，用于降低“发送”误判。 */
    private val SEND_GOAL_MARKERS = listOf(
        "微信",
        "企业微信",
        "wechat",
        "wework",
        "回复",
        "发送",
        "消息",
        "短信",
    )

    enum class TerminalKind {
        NONE,
        PAYMENT,
        SEND,
        DELETE,
        CONFIG,
        HOME_SECURITY,
        REMOTE_DESTRUCTIVE,
    }

    data class TerminalHit(
        val kind: TerminalKind,
        val marker: String,
        val reason: String,
    )

    /** 言控自身包名：在这些页面里出现“免密支付/发送”等字样只是任务描述或会话文本，不是真实终端页。 */
    const val SELF_PACKAGE = "com.voiceconfig.app"

    /**
     * 判断当前 UI 证据是否已到达终端确认页。
     *
     * @param uiEvidence 最近一次 read_ui/get_screen_state/read_screen 的文本。
     * @param goal 用户目标，用于区分支付与发送，并降低普通“发送”按钮的误判。
     * @param foregroundPackage 当前前台包名；当为言控自身时，不把会话/任务描述中的关键词当作终端页。
     */
    fun detect(uiEvidence: String, goal: String? = null, foregroundPackage: String? = null): TerminalHit {
        val text = uiEvidence.orEmpty()
        val goalText = goal.orEmpty()

        if (foregroundPackage == SELF_PACKAGE) {
            return TerminalHit(TerminalKind.NONE, "", "当前前台为言控自身，忽略任务描述中的终端关键词")
        }

        val payment = PAYMENT_TERMINAL_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }
        if (payment != null) {
            return TerminalHit(TerminalKind.PAYMENT, payment, "检测到支付/订单终端页：$payment")
        }

        val sendMarker = listOf(
            "确认发送",
            "发送消息确认",
            "确认发送消息",
        ).firstOrNull { text.contains(it, ignoreCase = true) }
        if (sendMarker != null) {
            return TerminalHit(TerminalKind.SEND, sendMarker, "检测到消息发送确认页：$sendMarker")
        }

        // “发送”按钮在很多页面都出现，只有用户目标是通信/回复时才视为终端。
        val sendButtonVisible = text.contains("发送", ignoreCase = true) || text.contains("send", ignoreCase = true)
        if (sendButtonVisible && isSendGoal(goalText)) {
            return TerminalHit(TerminalKind.SEND, if (text.contains("send", ignoreCase = true)) "send" else "发送", "检测到发送按钮，且用户目标为消息发送")
        }

        REMOTE_DESTRUCTIVE_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return TerminalHit(TerminalKind.REMOTE_DESTRUCTIVE, it, "检测到远程破坏性操作终端：$it")
        }
        DELETE_TERMINAL_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return TerminalHit(TerminalKind.DELETE, it, "检测到删除/清空确认页：$it")
        }
        CONFIG_TERMINAL_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return TerminalHit(TerminalKind.CONFIG, it, "检测到配置修改确认页：$it")
        }
        HOME_SECURITY_TERMINAL_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return TerminalHit(TerminalKind.HOME_SECURITY, it, "检测到智能家居安防终端：$it")
        }

        return TerminalHit(TerminalKind.NONE, "", "")
    }

    fun isTerminal(uiEvidence: String, goal: String? = null, foregroundPackage: String? = null): Boolean =
        detect(uiEvidence, goal, foregroundPackage).kind != TerminalKind.NONE

    private fun isSendGoal(goal: String): Boolean =
        SEND_GOAL_MARKERS.any { goal.contains(it, ignoreCase = true) }
}
