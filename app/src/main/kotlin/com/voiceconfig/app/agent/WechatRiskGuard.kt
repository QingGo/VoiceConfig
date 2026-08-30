package com.voiceconfig.app.agent

/**
 * 个人微信自动化风控守卫。
 *
 * 微信官方禁止使用非官方客户端、模拟器、插件、脚本工具自动操作个人微信。
 * 为避免触发账号风控/封号，默认关闭所有个人微信 UI 自动化。
 *
 * 仅当用户明确开启“微信小号风险模式”后才允许；企业微信不受此守卫影响。
 */
object WechatRiskGuard {

    /**
     * 是否允许个人微信 UI 自动化。
     *
     * 默认 false：任何涉及个人微信的代理操作都会被硬拦截。
     */
    @Volatile
    var automationAllowed: Boolean = false
        private set

    fun setAutomationAllowed(allowed: Boolean) {
        automationAllowed = allowed
    }

    private val WECHAT_UI_TOOLS = setOf(
        "wechat_open",
        "wechat_read_messages",
        "wechat_send_reply",
        "open_app",
        "tap",
        "tap_text",
        "input_text",
        "swipe",
        "press_key",
        "dismiss_popups",
        "read_ui",
        "read_screen",
        "get_screen_state",
        "ui_assert",
    )

    private val PLANNING_TOOLS = setOf(
        "task_plan",
        "wait_user",
        "wechat_draft_reply",
    )

    /**
     * 返回阻断原因；null 表示允许。
     */
    fun blockReason(
        toolName: String,
        args: Map<String, Any?>,
        foregroundPackage: String?,
    ): String? {
        if (automationAllowed) return null
        val targetWeChat = toolName in setOf("wechat_open", "wechat_read_messages", "wechat_send_reply") ||
            (args["package"]?.toString() == "com.tencent.mm") ||
            args.values.any { it?.toString()?.contains("com.tencent.mm") == true }
        val inWeChatForeground = foregroundPackage == "com.tencent.mm"
        if (!targetWeChat && !inWeChatForeground) return null
        // 纯规划/草稿工具不触碰微信界面，允许。
        if (toolName in PLANNING_TOOLS) return null
        // 所有可能触碰个人微信 UI 的工具默认禁用。
        if (toolName in WECHAT_UI_TOOLS || inWeChatForeground) {
            return "个人微信自动化已关闭（微信风控保护）。如需测试，请使用专属小号并显式开启微信小号风险模式；企业微信不受影响。"
        }
        return null
    }
}
