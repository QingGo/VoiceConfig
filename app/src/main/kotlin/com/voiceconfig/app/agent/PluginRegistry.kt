package com.voiceconfig.app.agent

/**
 * 插件注册表：加载插件并汇总所有工具到 [ToolRegistry]。
 */
class PluginRegistry {
    private val plugins = LinkedHashMap<String, AgentPlugin>()
    private val toolRegistry = ToolRegistry()

    fun load(plugin: AgentPlugin): PluginRegistry {
        if (plugins.containsKey(plugin.id)) return this
        plugin.onLoad()
        plugins[plugin.id] = plugin
        plugin.provideTools().forEach { toolRegistry.register(it) }
        return this
    }

    fun unload(pluginId: String) {
        val plugin = plugins.remove(pluginId) ?: return
        plugin.onUnload()
        // 重新构建工具表，移除该插件提供的工具。
        toolRegistry.clear()
        plugins.values.forEach { it.provideTools().forEach { tool -> toolRegistry.register(tool) } }
    }

    fun plugins(): List<AgentPlugin> = plugins.values.toList()

    fun toolRegistry(): ToolRegistry = toolRegistry
}
