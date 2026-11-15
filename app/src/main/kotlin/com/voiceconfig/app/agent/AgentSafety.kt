package com.voiceconfig.app.agent

import java.security.MessageDigest

enum class SafetyLevel { READ_ONLY, PREPARE, CONFIRM, IRREVERSIBLE }

data class SafetyDecision(
    val level: SafetyLevel,
    val requiresConfirmation: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val autoConfirmable: Boolean = true,
    val confirmationMode: ConfirmationMode = ConfirmationMode.AUTO,
    val humanSummary: String = "",
    val actionDigest: String = "",
)

class AgentSafety {
    fun decide(tool: AgentTool, args: Map<String, Any?>, foregroundPackage: String? = null): SafetyDecision =
        decide(tool.name, args, tool.metadata, foregroundPackage)

    fun decide(
        toolName: String,
        args: Map<String, Any?>,
        metadata: AgentToolMetadata = AgentToolMetadataRegistry.of(toolName),
        foregroundPackage: String? = null,
    ): SafetyDecision {
        val text = argsText(args)
        val summary = humanSummary(toolName, args)
        val digest = actionDigest(toolName, args)

        WechatRiskGuard.blockReason(toolName, args, foregroundPackage)?.let { reason ->
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = false,
                blocked = true,
                reason = reason,
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.BLOCKED,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        if (HIGH_SECURITY_BLOCK_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
            return SafetyDecision(
                level = SafetyLevel.IRREVERSIBLE,
                requiresConfirmation = false,
                blocked = true,
                reason = "命中安全凭证/自审批禁止动作",
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.BLOCKED,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        if (toolName == "remote_node") {
            val action = args["action"]?.toString()?.lowercase() ?: "list"
            if (action == "list" || action == "health") {
                return SafetyDecision(level = SafetyLevel.READ_ONLY, humanSummary = summary, actionDigest = digest)
            }
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "远程节点命令执行需要人工确认",
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.PER_ACTION,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        if (toolName == "home_control" && isHomeSecurityDomain(args)) {
            return SafetyDecision(
                level = SafetyLevel.IRREVERSIBLE,
                requiresConfirmation = true,
                reason = "智能家居安全域设备需要人工确认",
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.STRONG,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        if (!metadata.autoConfirmable ||
            metadata.confirmationMode == ConfirmationMode.STRONG ||
            metadata.confirmationMode == ConfirmationMode.PER_ACTION
        ) {
            return SafetyDecision(
                level = if (metadata.reversibility == ToolReversibility.IRREVERSIBLE) SafetyLevel.IRREVERSIBLE else SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "工具被标记为需要精确人工确认",
                autoConfirmable = metadata.autoConfirmable,
                confirmationMode = metadata.confirmationMode,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        if (toolName == "tap" || toolName == "tap_text") {
            if (STRONG_FINAL_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
                return SafetyDecision(
                    level = SafetyLevel.IRREVERSIBLE,
                    requiresConfirmation = true,
                    reason = "检测到支付/删除/卸载等不可逆最终动作",
                    autoConfirmable = false,
                    confirmationMode = ConfirmationMode.STRONG,
                    humanSummary = summary,
                    actionDigest = digest,
                )
            }
            if (COMMIT_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
                return SafetyDecision(
                    level = SafetyLevel.CONFIRM,
                    requiresConfirmation = true,
                    reason = "检测到发送/提交等外部提交动作",
                    autoConfirmable = false,
                    confirmationMode = ConfirmationMode.PER_ACTION,
                    humanSummary = summary,
                    actionDigest = digest,
                )
            }
        }

        if (metadata.sensitive || metadata.risk == ToolRisk.SENSITIVE || metadata.risk == ToolRisk.HIGH) {
            return SafetyDecision(
                level = SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "工具被标记为敏感/高风险",
                autoConfirmable = metadata.autoConfirmable,
                confirmationMode = ConfirmationMode.PER_ACTION,
                humanSummary = summary,
                actionDigest = digest,
            )
        }

        return when (metadata.effect) {
            ToolEffect.READ, ToolEffect.NAVIGATE, ToolEffect.PREPARE ->
                SafetyDecision(
                    if (metadata.risk == ToolRisk.MEDIUM) SafetyLevel.PREPARE else SafetyLevel.READ_ONLY,
                    humanSummary = summary,
                    actionDigest = digest,
                )
            ToolEffect.COMMIT -> SafetyDecision(
                SafetyLevel.CONFIRM,
                requiresConfirmation = true,
                reason = "外部提交动作需要确认",
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.PER_ACTION,
                humanSummary = summary,
                actionDigest = digest,
            )
            ToolEffect.DESTRUCTIVE, ToolEffect.SYSTEM -> SafetyDecision(
                SafetyLevel.IRREVERSIBLE,
                requiresConfirmation = true,
                reason = "不可逆/系统动作需要强确认",
                autoConfirmable = false,
                confirmationMode = ConfirmationMode.STRONG,
                humanSummary = summary,
                actionDigest = digest,
            )
        }
    }

    fun requiresConfirmation(tool: AgentTool, args: Map<String, Any?>): Boolean =
        decide(tool, args).requiresConfirmation

    fun requiresConfirmation(toolName: String, args: Map<String, Any?>): Boolean =
        decide(toolName, args).requiresConfirmation

    fun isAlwaysBlocked(toolName: String, args: Map<String, Any?>): Boolean =
        decide(toolName, args).blocked

    fun describe(toolName: String, args: Map<String, Any?>): String = humanSummary(toolName, args)

    fun humanSummary(toolName: String, args: Map<String, Any?>): String {
        fun value(key: String): String = args[key]?.toString()?.take(120).orEmpty()
        return when (toolName) {
            "run_shell" -> "执行 shell 命令：${value("command")}"
            "remote_node" -> "远程节点 ${value("node").ifBlank { "默认节点" }} 执行 ${value("command")}"
            "remote_ssh_exec" -> "SSH 执行命令：${value("command")}"
            "remote_ssh_write" -> "写入远程文件：${value("path")}"
            "file_write" -> "写入文件：${value("path").ifBlank { value("filename") }}"
            "wecom_send_message" -> "发送企业微信消息：${value("content")}"
            "wechat_send_reply" -> "发送微信回复：${value("draft")}"
            "home_control" -> "智能家居 ${value("domain")}.${value("service")} ${value("entityId")}"
            "tap_text" -> "点击界面文字：${value("text")}"
            "tap" -> "点击屏幕坐标 (${value("x")}, ${value("y")})"
            "input_text" -> "输入文本：${value("text")}"
            else -> "工具 $toolName(${args.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(240)})"
        }.ifBlank { "执行 $toolName" }
    }

    fun actionDigest(toolName: String, args: Map<String, Any?>): String {
        val canonical = buildString {
            append(toolName)
            args.entries.sortedBy { it.key }.forEach { (key, value) ->
                append('|').append(key).append('=').append(value?.toString().orEmpty())
            }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun argsText(args: Map<String, Any?>): String = args.values.joinToString(" ") { it?.toString().orEmpty() }

    private fun isHomeSecurityDomain(args: Map<String, Any?>): Boolean =
        (args["domain"]?.toString()?.lowercase() ?: "") in HOME_SECURITY_DOMAINS

    private companion object {
        val HOME_SECURITY_DOMAINS = setOf("lock", "camera", "alarm_control_panel", "siren", "security")
        val COMMIT_KEYWORDS = listOf("发送", "提交", "确认发送", "发布", "立即购买")
        val STRONG_FINAL_KEYWORDS = listOf(
            "确认支付", "立即支付", "付款", "免密支付", "转账", "汇款",
            "删除", "清空", "卸载", "安装", "授权", "修改密码",
        )
        val HIGH_SECURITY_BLOCK_KEYWORDS = listOf(
            "验证码", "otp", "密码", "私钥", "助记词", "token", "密钥", "批准审批", "fan_approve",
        )
    }
}

data class SensitiveActionRequest(
    val toolName: String,
    val args: Map<String, Any?>,
    val humanSummary: String = toolName,
    val actionDigest: String = "",
    val autoConfirmable: Boolean = true,
    val confirmationMode: ConfirmationMode = ConfirmationMode.PER_ACTION,
)
