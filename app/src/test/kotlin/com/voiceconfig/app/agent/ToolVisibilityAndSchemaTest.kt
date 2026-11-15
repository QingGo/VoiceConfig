package com.voiceconfig.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolVisibilityAndSchemaTest {
    private class FakeRemote : AgentTool {
        override val name = "remote_node"
        override val description = "remote"
        override val metadata = AgentToolMetadata(
            group = ToolGroup.REMOTE,
            risk = ToolRisk.SENSITIVE,
            sensitive = true,
            requiresOptIn = true,
        )
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    private class ExternalSchemaTool : AgentTool {
        override val name = "ext_test_echo"
        override val description = "echo"
        override val inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
            put("required", org.json.JSONArray().put("text"))
        }
        override val metadata = AgentToolMetadata(group = ToolGroup.REMOTE)
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    @Test
    fun `opt in tool hidden by default and visible after grant`() {
        val registry = ToolRegistry().register(FakeRemote())
        assertFalse(registry.modelTools().any { it.name == "remote_node" })
        val enabled = registry.modelTools(ToolVisibilityPolicy(enabledOptInTools = setOf("remote_node")))
        assertTrue(enabled.any { it.name == "remote_node" })
    }

    @Test
    fun `dynamic input schema is sent to model`() {
        val tools = AgentToolSchemas.build(listOf(ExternalSchemaTool()))
        val schema = tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("text").getString("type"))
        assertTrue(schema.getJSONArray("required").length() == 1)
    }

    @Test
    fun `accepted and confirmation results are not terminal failures`() {
        val accepted = ToolResult.accepted("accepted", "task_1")
        assertFalse(accepted.isTerminalFailure)
        assertTrue(accepted.requiresExternalAction)
        val confirmation = ToolResult.confirmationRequired(
            "approve",
            ToolConfirmation("apr_1", "sha256:x", "确认"),
        )
        assertFalse(confirmation.isTerminalFailure)
        assertTrue(confirmation.toModelContent().contains("confirmation_required"))
    }
}
