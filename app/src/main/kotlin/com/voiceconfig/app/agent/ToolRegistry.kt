package com.voiceconfig.app.agent

/**
 * 工具注册表：按名字查找工具。
 */
class ToolRegistry {

    companion object {
        val DEFAULT_MODEL_GROUPS: Set<ToolGroup> = setOf(
            ToolGroup.CORE,
            ToolGroup.PHONE,
            ToolGroup.REMOTE,
            ToolGroup.HOME,
            ToolGroup.RESEARCH,
            ToolGroup.APP_SKILL,
        )
    }

    private val tools = LinkedHashMap<String, AgentTool>()

    fun register(tool: AgentTool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun registerAll(vararg tool: AgentTool): ToolRegistry {
        tool.forEach { register(it) }
        return this
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun clear() {
        tools.clear()
    }

    fun get(name: String): AgentTool? = tools.get(name)

    fun names(): List<String> = tools.keys.toList()

    fun tools(): List<AgentTool> = tools.values.toList()

    fun tools(group: ToolGroup): List<AgentTool> =
        tools.values.filter { it.metadata.group == group }

    fun coreTools(): List<AgentTool> = tools(ToolGroup.CORE)

    fun modelTools(policy: ToolVisibilityPolicy = ToolVisibilityPolicy()): List<AgentTool> {
        var externalCount = 0
        return tools.values
            .filter { it.metadata.group in DEFAULT_MODEL_GROUPS || (policy.includeAdvanced && it.metadata.group == ToolGroup.ADVANCED) }
            .filter { !it.metadata.requiresOptIn || it.name in policy.enabledOptInTools }
            .filter { policy.includeDebugTools || it.metadata.group != ToolGroup.DEBUG }
            .sortedWith(compareBy<AgentTool>({ it.metadata.requiresOptIn }, { it.name }))
            .mapNotNull { tool ->
                val external = tool.name.startsWith("ext_") || tool.name.startsWith("mcp_")
                if (external) {
                    if (externalCount >= policy.maxExternalTools) null else { externalCount++; tool }
                } else tool
            }
    }
    fun modelTools(): List<AgentTool> = modelTools(ToolVisibilityPolicy())

    fun descriptions(): String = tools.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    fun descriptions(group: ToolGroup): String =
        tools(group).joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun coreDescriptions(): String = descriptions(ToolGroup.CORE)

    fun modelDescriptions(policy: ToolVisibilityPolicy = ToolVisibilityPolicy()): String =
        modelTools(policy).joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun size(): Int = tools.size
}

data class ToolVisibilityPolicy(
    val includeAdvanced: Boolean = false,
    val enabledOptInTools: Set<String> = emptySet(),
    val includeDebugTools: Boolean = false,
    val maxExternalTools: Int = 12,
)
