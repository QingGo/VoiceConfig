package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecordTest {

    private object RunTestTrace : AgentTrace {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        override fun startRun(userText: String): String = "run-${counter.incrementAndGet()}"
        override fun log(runId: String, type: String, data: Map<String, Any?>) {}
        override fun saveScreenshot(runId: String, base64: String, label: String): String = ""
    }

    private class FakeChat(
        private val responses: List<AgentChatResponse?>,
    ) : AgentToolChat {
        override var lastError: String? = null
        private var index = 0
        override suspend fun completeWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
        ): AgentChatResponse? {
            val r = if (index < responses.size) responses[index] else responses.lastOrNull { it != null }
            index++
            return r
        }

        override suspend fun streamWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
            onEvent: (AgentStreamEvent) -> Unit,
        ): AgentChatResponse? {
            val r = completeWithTools(systemPrompt, messages, tools)
            onEvent(AgentStreamEvent.Done(r))
            return r
        }
    }

    private class SimpleTool(
        override val name: String,
        private val data: Map<String, Any?> = mapOf("name" to name),
    ) : AgentTool {
        override val description: String = name
        override suspend fun execute(args: Map<String, Any?>): ToolResult =
            ToolResult.success("$name ok", data)
    }

    private fun sessionWith(tool: AgentTool, responses: List<AgentChatResponse?>, ledger: AgentRunLedger): AgentSession =
        AgentSession(
            ToolRegistry().register(tool),
            FakeChat(responses),
            RunTestTrace,
            TaskPlanStore(InMemoryTaskPlanPersistence()),
            ledger,
        ).apply { argumentParser = { emptyMap() } }

    @Test
    fun `run control is cleaned after finish and cancelAll can target live runs`() = runBlocking {
        val ledger = InMemoryAgentRunLedger()
        val session = sessionWith(
            SimpleTool("echo"),
            listOf(AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList())),
            ledger,
        )
        val first = session.send("第一次")
        assertTrue(first.ok)
        assertNull(session.currentRunId())

        val second = session.send("第二次")
        assertTrue(second.ok)
        assertNull(session.currentRunId())
        session.cancelAll()
        session.pauseAll()
    }

    @Test
    fun `verification enforcement rejects open_app without foreground evidence`() = runBlocking {
        val ledger = InMemoryAgentRunLedger()
        val tool = SimpleTool("open_app", mapOf("mode" to "intent"))
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "open_app", "{}")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            ledger,
        )
        val result = session.send("打开企业微信")
        assertFalse(result.toolResults.first().ok)
        assertTrue(result.toolResults.first().message.contains("验证失败"))
        assertFalse(computeVerified(result.toolCalls, result.toolResults) == true)
        assertFalse(ledger.latest()?.verified == true)
    }

    @Test
    fun `verification passes and run record verified is true when evidence exists`() = runBlocking {
        val ledger = InMemoryAgentRunLedger()
        val tool = SimpleTool("open_app", mapOf("verified" to true, "package" to "com.tencent.wework"))
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "open_app", "{}")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            ledger,
        )
        val result = session.send("打开企业微信")
        assertTrue(result.toolResults.first().ok)
        assertEquals(true, computeVerified(result.toolCalls, result.toolResults))
        assertEquals(true, ledger.latest()?.verified)
    }

    @Test
    fun `run record maps entity fields correctly`() {
        val record = AgentRunRecord(
            runId = "r1",
            userText = "打开企业微信",
            ok = true,
            state = AgentRunState.DONE,
            message = "ok",
            toolCalls = listOf("open_app"),
            durationMs = 12,
            startedAtMs = 100,
            finishedAtMs = 112,
            waitingForHuman = false,
            verified = true,
            capabilitySummary = "Shizuku=Y,Accessibility=N",
            safetyConfirmations = 3,
            safetyApprovals = 2,
            safetyDenials = 1,
            safetyBlocks = 1,
        )
        val entity = record.toEntity()
        val restored = entity.toRunRecord()
        assertEquals(record.runId, restored.runId)
        assertEquals(record.userText, restored.userText)
        assertEquals(record.toolCalls, restored.toolCalls)
        assertEquals(record.verified, restored.verified)
        assertEquals(record.capabilitySummary, restored.capabilitySummary)
        assertEquals(AgentRunState.DONE, restored.state)
        assertEquals(record.safetyConfirmations, restored.safetyConfirmations)
        assertEquals(record.safetyApprovals, restored.safetyApprovals)
        assertEquals(record.safetyDenials, restored.safetyDenials)
        assertEquals(record.safetyBlocks, restored.safetyBlocks)
    }

    private class SensitiveTool : AgentTool {
        override val name: String = "sensitive_tool"
        override val description: String = "sensitive"
        override val metadata: AgentToolMetadata = AgentToolMetadata(
            category = "测试",
            group = ToolGroup.CORE,
            risk = ToolRisk.SENSITIVE,
            sensitive = true,
        )
        override suspend fun execute(args: Map<String, Any?>): ToolResult =
            ToolResult.success("ok", emptyMap())
    }

    @Test
    fun `run record counts denied sensitive confirmation`() = runBlocking {
        val ledger = InMemoryAgentRunLedger()
        val session = sessionWith(
            SensitiveTool(),
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "sensitive_tool", "{}")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            ledger,
        ).apply { argumentParser = { emptyMap() } }
        val result = session.send(
            "执行敏感操作",
            onSensitiveAction = { false },
        )
        assertTrue(result.ok)
        val record = ledger.latest()
        assertEquals(1, record?.safetyConfirmations)
        assertEquals(0, record?.safetyApprovals)
        assertEquals(1, record?.safetyDenials)
        assertEquals(0, record?.safetyBlocks)
    }

    @Test
    fun `run record counts hard blocked irreversible action even when auto approved`() = runBlocking {
        val ledger = InMemoryAgentRunLedger()
        val tool = object : AgentTool {
            override val name: String = "tap_text"
            override val description: String = "tap text"
            override val metadata: AgentToolMetadata = AgentToolMetadata(
                category = "交互",
                group = ToolGroup.PHONE,
                risk = ToolRisk.MEDIUM,
                mutatesUi = true,
            )
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("tapped", emptyMap())
        }
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(
                        AgentToolCall("call1", "tap_text", """{"text":"确认支付"}"""),
                    ),
                ),
            ),
            ledger,
        ).apply { argumentParser = { mapOf("text" to "确认支付") } }
        val result = session.send(
            "确认支付",
            onSensitiveAction = { true },
        )
        assertFalse(result.ok)
        assertTrue(result.message.contains("安全拦截"))
        val record = ledger.latest()
        assertEquals(1, record?.safetyConfirmations)
        assertEquals(1, record?.safetyApprovals)
        assertEquals(0, record?.safetyDenials)
        assertEquals(1, record?.safetyBlocks)
    }
}
