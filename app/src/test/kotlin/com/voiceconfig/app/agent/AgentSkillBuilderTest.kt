package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillBuilderTest {

    @Test
    fun `build creates pending structured skill from verified run`() {
        val skill = AgentSkillBuilder.build(
            goal = "打开企业微信",
            toolCalls = listOf(ToolCall("open_app", mapOf("package" to "com.tencent.wework"))),
            toolResults = listOf(
                ToolResult.success("已打开", mapOf("verified" to true, "package" to "com.tencent.wework")),
            ),
            runId = "run_1",
            verified = true,
        )

        assertNotNull(skill)
        assertEquals(AgentSkillStatus.PENDING, skill!!.status)
        assertEquals("run_1", skill.sourceRunId)
        assertEquals(true, skill.sourceVerified)
        assertEquals(1, skill.steps.size)
        assertEquals("open_app", skill.steps[0].toolName)
        assertTrue(skill.steps[0].purpose.isNotBlank())
        assertTrue(skill.steps[0].expected.contains("verified=true"))
        assertTrue(skill.steps[0].verification.contains("FOREGROUND"))
        assertTrue(skill.steps[0].uiEvidence.contains("verified=true"))
        assertTrue(skill.steps[0].fallback.isNotBlank())
        assertTrue(skill.requiredCapabilities.isNotEmpty())
    }

    @Test
    fun `build omits blank goal or empty steps`() {
        assertEquals(null, AgentSkillBuilder.build("  ", emptyList(), emptyList()))
        assertEquals(null, AgentSkillBuilder.build("目标", emptyList(), emptyList()))
    }

    @Test
    fun `buildFromTrace reconstructs calls and results`() {
        val trace = listOf(
            mapOf<String, Any?>("type" to "tool_call", "tool" to "open_app", "args" to mapOf("package" to "com.tencent.wework")),
            mapOf<String, Any?>("type" to "tool_result", "tool" to "open_app", "ok" to true, "message" to "已打开", "data_keys" to listOf("verified", "package")),
        )
        val skill = AgentSkillBuilder.buildFromTrace(
            goal = "打开企业微信",
            runId = "run_trace",
            traceEvents = trace,
            verified = true,
        )

        assertNotNull(skill)
        assertEquals(1, skill!!.steps.size)
        assertEquals("open_app", skill.steps[0].toolName)
        assertTrue(skill.steps[0].args.contains("com.tencent.wework"))
        assertTrue(skill.steps[0].uiEvidence.contains("verified"))
    }

    @Test
    fun `args to string is stable and compact`() {
        val text = AgentSkillBuilder.argsToString(mapOf("text" to "hello", "count" to 2))
        assertTrue(text.contains("text=hello"))
        assertTrue(text.contains("count=2"))
    }
}
