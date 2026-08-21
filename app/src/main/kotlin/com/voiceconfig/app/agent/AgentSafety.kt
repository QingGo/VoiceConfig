package com.voiceconfig.app.agent

/**
 * Agent 敏感操作判定。
 *
 * 普通只读操作不需要确认；涉及支付、发送、删除、提交、覆盖文件、shell 等
 * 高风险动作时需要确认。用户开启“敏感操作自动执行”后可以跳过所有确认。
 */
class AgentSafety {

    fun requiresConfirmation(toolName: String, args: Map<String, Any?>): Boolean {
        val text = buildString {
            args.values.forEach { value ->
                if (value != null) append(value.toString()).append(' ')
            }
        }
        return when (toolName) {
            "run_shell", "file_write", "press_key" -> true
            "tap_text", "tap", "press_key", "input_text", "swipe" ->
                SENSITIVE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
            else -> false
        }
    }

    fun describe(toolName: String, args: Map<String, Any?>): String {
        val argText = args.entries.joinToString(", ") { (k, v) -> "$k=$v" }
        return "工具 $toolName($argText)"
    }

    private companion object {
        val SENSITIVE_KEYWORDS = listOf(
            "支付", "立即支付", "确认支付", "免密支付", "付款",
            "提交订单", "确认订单", "下单", "购买", "结算",
            "发送", "删除", "清空", "退出登录", "注销",
            "同意", "授权", "确认", "提交", "转账", "汇款",
            "充值", "贷款", "签约", "安装", "卸载", "允许",
        )
    }
}

data class SensitiveActionRequest(
    val toolName: String,
    val args: Map<String, Any?>,
)
