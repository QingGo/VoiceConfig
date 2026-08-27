package com.voiceconfig.app.agent

/**
 * 工具注册表：按名字查找工具。
 */
class ToolRegistry {

    companion object {
        /**
         * 默认给模型看的工具分组。
         *
         * CORE 是计划/等待/感知/确定性单步工具；
         * PHONE 是手机 UI 操作工具；
         * REMOTE 是远程设备与代码工具。
         * 其他分组（HOME/RESEARCH/APP_SKILL/DEBUG）按产品阶段逐步展开。
         */
        val DEFAULT_MODEL_GROUPS: Set<ToolGroup> = setOf(
            ToolGroup.CORE,
            ToolGroup.PHONE,
            ToolGroup.REMOTE,
            ToolGroup.HOME,
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

    fun clear() {
        tools.clear()
    }

    fun get(name: String): AgentTool? = tools[name]

    fun names(): List<String> = tools.keys.toList()

    fun tools(): List<AgentTool> = tools.values.toList()

    fun tools(group: ToolGroup): List<AgentTool> =
        tools.values.filter { it.metadata.group == group }

    fun coreTools(): List<AgentTool> = tools(ToolGroup.CORE)

    fun modelTools(): List<AgentTool> =
        tools.values.filter { it.metadata.group in DEFAULT_MODEL_GROUPS }

    fun descriptions(): String = tools.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    fun descriptions(group: ToolGroup): String =
        tools(group).joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun coreDescriptions(): String = descriptions(ToolGroup.CORE)

    fun modelDescriptions(): String =
        modelTools().joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun size(): Int = tools.size
}
