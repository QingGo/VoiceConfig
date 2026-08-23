package com.voiceconfig.app.agent

/**
 * Agent 敏感操作判定。
 *
 * 普通只读操作不需要确认；涉及支付、发送、删除、提交、覆盖文件、shell 等
 * 高风险动作时需要确认。用户开启“敏感操作自动执行”后可以跳过所有确认。
 */
class AgentSafety {

    fun requiresConfirmation(tool: AgentTool, args: Map<String, Any?>): Boolean {
        if (tool.metadata.sensitive) return true
        return requiresConfirmation(tool.name, args)
    }

    fun requiresConfirmation(toolName: String, args: Map<String, Any?>): Boolean {
        val text = buildString {
            args.values.forEach { value ->
                if (value != null) append(value.toString()).append(' ')
            }
        }
        return when (toolName) {
            "run_shell", "file_write" -> true
            "tap_text", "tap", "input_text", "swipe", "open_app", "open_file" ->
                // 进入下单/结算页的“立即购买/去结算”不是最终支付，允许执行；
                // 只有真正支付/提交/删除等最终动作才需要确认。
                FINAL_ACTION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
            else -> false
        }
    }

    fun describe(toolName: String, args: Map<String, Any?>): String {
        val argText = args.entries.joinToString(", ") { (k, v) -> "$k=$v" }
        return "工具 $toolName($argText)"
    }

    private companion object {
        val FINAL_ACTION_KEYWORDS = listOf(
            "提交订单", "确认支付", "立即支付", "确认付款", "免密支付", "付款",
            "支付", "删除", "清空", "退出登录", "注销",
            "同意", "授权", "确认订单", "确认下单", "提交", "转账", "汇款",
            "充值", "贷款", "签约", "安装", "卸载", "允许",
            "发送", "立即购买",
        )
    }
}

data class SensitiveActionRequest(
    val toolName: String,
    val args: Map<String, Any?>,
)
