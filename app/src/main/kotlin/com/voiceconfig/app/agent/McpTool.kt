package com.voiceconfig.app.agent

import org.json.JSONObject

class McpTool(
    private val client: McpHttpClient,
    private val serverId: String,
    private val def: McpToolDef,
) : AgentTool {
    override val name: String = "mcp_${sanitize(serverId)}_${sanitize(def.name)}".take(64)
    override val description: String = "[MCP/$serverId] ${def.description}"
    override val inputSchema: JSONObject get() = def.inputSchema
    override val metadata: AgentToolMetadata
        get() {
            val risk = when {
                def.destructiveHint -> ToolRisk.SENSITIVE
                def.readOnlyHint -> ToolRisk.READ_ONLY
                else -> ToolRisk.HIGH
            }
            val effect = when {
                def.destructiveHint -> ToolEffect.DESTRUCTIVE
                def.readOnlyHint -> ToolEffect.READ
                else -> ToolEffect.COMMIT
            }
            return AgentToolMetadata(
                category = "MCP/$serverId",
                group = ToolGroup.REMOTE,
                risk = risk,
                sensitive = risk != ToolRisk.READ_ONLY,
                autoConfirmable = risk == ToolRisk.READ_ONLY,
                effect = effect,
                reversibility = if (def.destructiveHint) ToolReversibility.IRREVERSIBLE else ToolReversibility.COMPENSATABLE,
                confirmationMode = when {
                    def.readOnlyHint -> ConfirmationMode.AUTO
                    def.destructiveHint -> ConfirmationMode.STRONG
                    else -> ConfirmationMode.PER_ACTION
                },
                executionTimeoutMs = 120_000L,
            )
        }

    override suspend fun execute(args: Map<String, Any?>): ToolResult = client.callTool(def.name, args)

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(20)
}
