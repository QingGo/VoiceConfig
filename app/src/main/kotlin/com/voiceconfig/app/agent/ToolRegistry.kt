package com.voiceconfig.app.agent

/**
 * 工具注册表：按名字查找工具。
 */
class ToolRegistry {
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

    fun descriptions(): String = tools.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    fun size(): Int = tools.size
}
