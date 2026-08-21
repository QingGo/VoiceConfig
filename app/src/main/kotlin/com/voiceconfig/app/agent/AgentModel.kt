package com.voiceconfig.app.agent

/**
 * 模型后端抽象。
 *
 * 当前默认实现是 DeepSeek（[AgentChatClient]）。
 * 后续可接入本地 GUI grounding 模型、专用 UI 定位模型或其他 provider，
 * 只要实现 AgentToolChat 即可被 AgentSession 使用。
 */
interface AgentModelBackend : AgentToolChat {
    val modelId: String
    val supportsVision: Boolean
}
