package com.voiceconfig.app.agent

import com.voiceconfig.data.local.repository.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillDistributorTest {

    private val node = RemoteNode(
        id = 1,
        nodeId = "node_test",
        name = "pi",
        host = "100.0.0.1",
        port = 8787,
        scheme = "http",
        token = "secret",
        allowedCommands = listOf("hostname", "uptime"),
        enabled = true,
        paused = false,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    private fun skill(
        id: String,
        status: AgentSkillStatus,
        enabled: Boolean = true,
        required: List<String> = emptyList(),
    ) = AgentSkill(
        id = id,
        name = id,
        description = "",
        text = "test",
        steps = listOf(
            AgentSkillStep(
                toolName = "hostname",
                args = "{}",
                purpose = "获取主机名",
                expected = "返回主机名",
            ),
        ),
        createdAt = 0,
        updatedAt = 0,
        successCount = 1,
        status = status,
        enabled = enabled,
        requiredCapabilities = required,
    )

    @Test
    fun `only approved enabled skills matching node capabilities are distributed`() {
        val distributor = AgentSkillDistributor()
        val result = distributor.distribute(
            listOf(
                skill("approved", AgentSkillStatus.APPROVED, required = listOf("cmd:hostname")),
                skill("pending", AgentSkillStatus.PENDING),
                skill("disabled", AgentSkillStatus.APPROVED, enabled = false),
                skill("unsupported", AgentSkillStatus.APPROVED, required = listOf("Shizuku")),
            ),
            node,
        )
        assertEquals(listOf("approved"), result.map { it.skill.id })
    }

    @Test
    fun `node capabilities include allowed command prefixes and remote read-only`() {
        val distributor = AgentSkillDistributor()
        val caps = distributor.nodeCapabilities(node)
        assertTrue("cmd:hostname" in caps)
        assertTrue("cmd:uptime" in caps)
        assertTrue("RemoteReadOnly" in caps)
        assertFalse("cmd:network" in caps)
    }

    @Test
    fun `remote skill payload keeps only node-allowed command steps`() {
        val distributor = AgentSkillDistributor()
        val skill = skill("skill", AgentSkillStatus.APPROVED).copy(
            steps = listOf(
                AgentSkillStep(toolName = "hostname", args = "{}", purpose = "主机名"),
                AgentSkillStep(toolName = "network", args = "{}", purpose = "网络"),
            ),
        )
        val payload = distributor.toRemoteSkillPayload(skill, node)
        @Suppress("UNCHECKED_CAST")
        val steps = payload["steps"] as List<Map<String, Any?>>
        assertEquals(1, steps.size)
        assertEquals("hostname", steps[0]["command"])
    }
}
