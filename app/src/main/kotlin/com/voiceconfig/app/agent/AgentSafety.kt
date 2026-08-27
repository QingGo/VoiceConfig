package com.voiceconfig.app.agent

/**
 * 安全四级决策模型。
 *
 * 与 DESIGN.md 的 Phase A1 对应：
 * - READ_ONLY：只读/感知，自动执行
 * - PREPARE：会改变中间状态，但可自动并展示结果
 * - CONFIRM：发送/下单/修改重要配置等，必须由人类确认
 * - IRREVERSIBLE：支付/删除/覆盖/系统级不可逆操作，强制拦截，不可通过“自动确认”绕过
 */
enum class SafetyLevel {
    READ_ONLY,
    PREPARE,
    CONFIRM,
    IRREVERSIBLE,
}

/**
 * 单个工具调用的安全决策结果。
 */
data class SafetyDecision(
    val level: SafetyLevel,
    val requiresConfirmation: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
)

/**
 * Agent 敏感操作判定。
 *
 * 普通只读操作不需要确认；涉及支付、发送、删除、提交、覆盖文件、shell 等
 * 高风险动作时需要确认。用户开启“敏感操作自动执行”后可以跳过所有确认，
 * 但不能绕过 [SafetyLevel.IRREVERSIBLE] 的硬拦截。
 */
class AgentSafety {

    fun decide(tool: AgentTool, args: Map<String, Any?>): SafetyDecision =
        decide(tool.name, args, tool.metadata)

    fun decide(
        toolName: String,
        args: Map<String, Any?>,
        metadata: AgentToolMetadata = AgentToolMetadataRegistry.of(toolName),
    ): SafetyDecision {
        val text = argsText(args)

        // 1. 不可逆硬拦截：无论是否开启自动确认，都禁止直接执行最终操作。
        if (toolName in IRREVERSIBLE_CAPABLE_TOOLS &&
            HARD_BLOCK_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        ) {
            return SafetyDecision(
                level = SafetyLevel.IRREVERSIBLE,
                requiresConfirmation = true,
                blocked = true,
                reason = "命中不可逆最终操作关键词",
            )
        }

        // 2. 工具元数据明确标记敏感：必须先确认。
        if (metadata.sensitive || metadata.risk == ToolRisk.SENSITIVE || metadata.risk == ToolRisk.HIGH) {
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "工具被标记为敏感/高风险",
            )
        }

        // 3. 现有显式规则：shell / 文件写入进入确认。
        if (toolName == "run_shell" || toolName == "file_write") {
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "shell/文件写入需要用户确认",
            )
        }

        // 4. 交互类工具：若参数中出现支付/发送/删除等最终动作关键词，升级为确认。
        if (toolName in CONFIRMABLE_UI_TOOLS &&
            FINAL_ACTION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        ) {
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "检测到下单/发送/删除等最终动作关键词",
            )
        }

        // 5. 默认按风险分级：只读可直接执行，会改变 UI/系统状态但非不可逆按 PREPARE。
        return when (metadata.risk) {
            ToolRisk.READ_ONLY -> SafetyDecision(SafetyLevel.READ_ONLY)
            ToolRisk.LOW -> SafetyDecision(SafetyLevel.READ_ONLY)
            ToolRisk.MEDIUM -> SafetyDecision(
                SafetyLevel.PREPARE,
                reason = "会改变界面状态，但未命中需确认的关键词",
            )
            ToolRisk.HIGH, ToolRisk.SENSITIVE -> SafetyDecision(
                SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "高风险工具",
            )
        }
    }

    fun requiresConfirmation(tool: AgentTool, args: Map<String, Any?>): Boolean =
        decide(tool, args).requiresConfirmation

    fun requiresConfirmation(toolName: String, args: Map<String, Any?>): Boolean =
        decide(toolName, args).requiresConfirmation

    /**
     * 不可绕过的高危最终动作。
     *
     * 这里的闸门独立于用户的“自动确认敏感操作”开关：即使模型想执行，
     * 或者用户开启了自动确认，系统也不允许直接执行支付/发送/删除等最终动作。
     * 这保证“到确认页即停”不依赖模型自律。
     */
    fun isAlwaysBlocked(toolName: String, args: Map<String, Any?>): Boolean =
        decide(toolName, args).blocked

    fun describe(toolName: String, args: Map<String, Any?>): String {
        val argText = args.entries.joinToString(", ") { (k, v) -> "$k=$v" }
        return "工具 $toolName($argText)"
    }

    private fun argsText(args: Map<String, Any?>): String = buildString {
        args.values.forEach { value ->
            if (value != null) append(value.toString()).append(' ')
        }
    }

    private companion object {
        val CONFIRMABLE_UI_TOOLS = setOf(
            "tap_text", "tap", "input_text", "swipe", "open_app", "open_file",
        )

        val IRREVERSIBLE_CAPABLE_TOOLS = setOf(
            "tap", "tap_text", "input_text", "swipe", "open_app", "open_file",
            "run_shell", "file_write",
        )

        val FINAL_ACTION_KEYWORDS = listOf(
            "提交订单", "确认支付", "立即支付", "确认付款", "免密支付", "付款",
            "支付", "删除", "清空", "退出登录", "注销",
            "同意", "授权", "确认订单", "确认下单", "提交", "转账", "汇款",
            "充值", "贷款", "签约", "安装", "卸载", "允许",
            "发送", "立即购买",
        )

        /**
         * 最终不可逆/支付提交类关键词。
         * 刻意不包括“立即购买/去结算/去下单”，因为这些是进入确认页的允许步骤。
         */
        val HARD_BLOCK_KEYWORDS = listOf(
            "提交订单", "确认支付", "立即支付", "确认付款", "免密支付",
            "确认下单", "付款", "发送", "删除", "清空", "退出登录", "注销",
            "转账", "汇款", "贷款", "签约", "安装", "卸载",
        )
    }
}

data class SensitiveActionRequest(
    val toolName: String,
    val args: Map<String, Any?>,
)
