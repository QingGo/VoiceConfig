package com.voiceconfig.app.agent

/**
 * 一个可加载/卸载的 Agent 插件。
 *
 * 借鉴 DeepSeek Harness“一切皆插件”的思想：每个能力包（工具集）都是一个插件，
 * 插件向 [ToolRegistry] 提供 [AgentTool]。
 */
interface AgentPlugin {
    val id: String
    val name: String
    val version: String

    /** 该插件提供的工具列表。 */
    fun provideTools(): List<AgentTool>

    fun onLoad() {}

    fun onUnload() {}
}

/**
 * 最简单的插件实现：直接包装一组工具。
 */
class SimpleAgentPlugin(
    override val id: String,
    override val name: String,
    override val version: String,
    private val tools: List<AgentTool>,
) : AgentPlugin {
    override fun provideTools(): List<AgentTool> = tools
}
