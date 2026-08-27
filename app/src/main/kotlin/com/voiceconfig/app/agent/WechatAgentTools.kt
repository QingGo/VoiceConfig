package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

data class WechatReplyDraft(
    val receiver: String,
    val context: String,
    val reply: String,
    val requiresConfirmation: Boolean = true,
)

@Singleton
class WechatDraftReplyTool @Inject constructor() : AgentTool {
    override val name: String = "wechat_draft_reply"
    override val description: String =
        "生成微信/企业微信回复草稿（不会自动发送）。参数：{\"receiver\":\"对方\",\"context\":\"上下文或原消息\",\"reply\":\"草稿内容\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.LOW,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val receiver = args["receiver"]?.toString()?.trim()?.ifBlank { null } ?: "对方"
        val context = args["context"]?.toString()?.trim().orEmpty()
        val reply = args["reply"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 reply（回复草稿内容）")
        val draft = WechatReplyDraft(
            receiver = receiver,
            context = context,
            reply = reply,
        )
        return ToolResult.success(
            "已生成回复草稿（未发送）给 $receiver：$reply",
            mapOf(
                "draft" to draft,
                "requiresConfirmation" to true,
                "safe" to true,
            ),
        )
    }
}
