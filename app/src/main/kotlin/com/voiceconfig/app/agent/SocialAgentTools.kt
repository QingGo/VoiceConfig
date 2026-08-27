package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WechatOpenTool @Inject constructor(
    private val openAppTool: OpenAppTool,
) : AgentTool {
    override val name: String = "wechat_open"
    override val description: String = "打开个人微信"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        openAppTool.execute(mapOf("package" to "com.tencent.mm"))
}

@Singleton
class WeworkOpenTool @Inject constructor(
    private val openAppTool: OpenAppTool,
) : AgentTool {
    override val name: String = "wework_open"
    override val description: String = "打开企业微信"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        openAppTool.execute(mapOf("package" to "com.tencent.wework"))
}

@Singleton
class WechatReadMessagesTool @Inject constructor(
    private val wechatOpenTool: WechatOpenTool,
    private val readUiTool: ReadUiTool,
) : AgentTool {
    override val name: String = "wechat_read_messages"
    override val description: String = "打开微信并读取当前会话页消息文本（需要无障碍或 Shizuku 读屏能力）"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.READ_ONLY,
        needsShizuku = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val open = wechatOpenTool.execute(emptyMap())
        if (!open.ok) return open
        kotlinx.coroutines.delay(800)
        val ui = readUiTool.execute(emptyMap())
        if (!ui.ok) return ui
        val text = ui.message.take(6000)
        return ToolResult.success(
            "已读取微信当前页面：\n$text",
            mapOf("ui" to text, "source" to "wechat"),
        )
    }
}

@Singleton
class WechatSendReplyTool @Inject constructor(
    private val inputTextTool: InputTextTool,
    private val pressKeyTool: PressKeyTool,
) : AgentTool {
    override val name: String = "wechat_send_reply"
    override val description: String =
        "发送微信回复（仅当 humanConfirmed=true 且经过系统确认后才执行）。参数：{\"draft\":\"回复内容\",\"humanConfirmed\":true}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.HIGH,
        mutatesUi = true,
        sensitive = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val draft = args["draft"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 draft（回复内容）")
        val confirmed = args["humanConfirmed"] == true ||
            args["humanConfirmed"]?.toString()?.equals("true", ignoreCase = true) == true
        if (!confirmed) {
            return ToolResult.failure("发送微信消息需要 humanConfirmed=true 且必须经用户确认")
        }
        val input = inputTextTool.execute(mapOf("text" to draft))
        if (!input.ok) return input
        val send = pressKeyTool.execute(mapOf("key" to "enter"))
        if (!send.ok) return send
        return ToolResult.success(
            "已发送微信回复（需在真实微信界面验证）",
            mapOf("draft" to draft, "sent" to true),
        )
    }
}
