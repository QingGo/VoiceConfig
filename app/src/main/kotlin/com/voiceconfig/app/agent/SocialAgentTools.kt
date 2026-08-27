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
