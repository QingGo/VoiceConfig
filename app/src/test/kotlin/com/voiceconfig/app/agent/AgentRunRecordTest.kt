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
        )
        val entity = record.toEntity()
        val restored = entity.toRunRecord()
        assertEquals(record.runId, restored.runId)
        assertEquals(record.userText, restored.userText)
        assertEquals(record.toolCalls, restored.toolCalls)
        assertEquals(record.verified, restored.verified)
        assertEquals(AgentRunState.DONE, restored.state)
    }
}
