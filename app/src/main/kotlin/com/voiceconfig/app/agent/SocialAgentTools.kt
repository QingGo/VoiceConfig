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
