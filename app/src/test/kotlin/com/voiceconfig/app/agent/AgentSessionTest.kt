package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoOpTrace : AgentTrace {
    override fun startRun(userText: String): String = "test-run"
    override fun log(runId: String, type: String, data: Map<String, Any?>) {}
    override fun saveScreenshot(runId: String, base64: String, label: String): String = ""
}

class AgentSessionTest {

    private class EchoTool : AgentTool {
        override val name: String = "echo"
        override val description: String = "echo test"
        override suspend fun execute(args: Map<String, Any?>): ToolResult {
            val text = args["text"]?.toString() ?: ""
            return ToolResult.success("echo:$text", mapOf("text" to text))
        }
    }

    private class FakeToolChatClient(
        private val responses: List<AgentChatResponse?>,
    ) : AgentToolChat {
        override var lastError: String? = null
        var callCount = 0
        val requestMessages = mutableListOf<List<AgentMessage>>()
        override suspend fun completeWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
        ): AgentChatResponse? {
            val index = callCount++
            if (index < responses.size) return responses[index]
            return responses.lastOrNull { it != null }
        }

        override suspend fun streamWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
            onEvent: (AgentStreamEvent) -> Unit,
        ): AgentChatResponse? {
            requestMessages += messages
            val response = completeWithTools(systemPrompt, messages, tools)
            onEvent(AgentStreamEvent.Done(response))
            return response
        }
    }

    @Test
    fun `session executes native tool calls and continues loop`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = "need echo",
                    toolCalls = listOf(AgentToolCall("call1", "echo", """{"text":"hi"}""")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { mapOf("text" to "hi") }
        }

        val result = session.send("说 hi")
        assertTrue(result.ok)
        assertEquals(1, result.toolCalls.size)
        assertTrue(result.message.contains("完成"))
        assertTrue(result.history.any { it.role == "tool" && it.toolName == "echo" })
        assertEquals(6, result.history.size) // user + assistant + tool + assistant final + 1 completion check

        assertTrue(result.history.any { it.role == "assistant" && it.reasoningContent == "need echo" })
        val secondRequest = client.requestMessages.getOrNull(1)
        assertTrue(
            "第二轮请求必须回传 reasoning_content",
            secondRequest?.any { it.role == "assistant" && it.reasoningContent == "need echo" } == true,
        )
    }

    @Test
    fun `llm timing ttft is propagated to assistant history`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = "完成",
                    reasoningContent = null,
                    toolCalls = emptyList(),
                    thinkingMs = 400,
                    outputMs = 600,
                    ttftMs = 800,
                ),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger())
        val result = session.send("你好")
        assertTrue(result.ok)
        val assistant = result.history.lastOrNull { it.role == "assistant" }
        assertTrue(assistant != null)
        assertEquals(800L, assistant?.ttftMs)
        assertEquals(400L, assistant?.thinkingMs)
        assertEquals(600L, assistant?.outputMs)
    }

    @Test
    fun `session returns text when no tool calls`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(AgentChatResponse(content = "你好", reasoningContent = null, toolCalls = emptyList())),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger())

        val result = session.send("你好")
        assertTrue(result.ok)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("你好", result.message)
        assertEquals(4, result.history.size) // user + assistant + 1 completion check
    }

    @Test
    fun `session reports chat failure`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(listOf(null))
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger())
        val result = session.send("hi")
        assertFalse(result.ok)

    }
    @Test
    fun `vision screenshot is injected as user image after tool result`() = runBlocking {
        val screenTool = object : AgentTool {
            override val name: String = "read_screen"
            override val description: String = "screen"
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("已截屏", mapOf("image_base64" to "iVBORw0KGgo="))
        }
        val registry = ToolRegistry().register(screenTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "read_screen", "{}")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }

        val result = session.send("看屏幕并操作")
        assertTrue(result.ok)
        val imageUser = result.history.lastOrNull { it.role == "user" && it.imageBase64 != null }
        assertTrue(imageUser != null)
        assertEquals("iVBORw0KGgo=", imageUser?.imageBase64)
        val secondRequest = client.requestMessages.getOrNull(1)
        assertTrue(
            "第二轮请求应包含屏幕截图 user 消息",
            secondRequest?.any { it.role == "user" && it.imageBase64 == "iVBORw0KGgo=" } == true,
        )
    }

    @Test
    fun `auto verification respects max per run and runId is propagated`() = runBlocking {
        var screenExecutions = 0
        val screenTool = object : AgentTool {
            override val name: String = "read_ui"
            override val description: String = "screen"
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                screenExecutions++
                return ToolResult.success("ok", mapOf("image_base64" to "iVBORw0KGgo="))
            }
        }
        val tapTool = object : AgentTool {
            override val name: String = "tap"
            override val description: String = "tap"
            override val metadata: AgentToolMetadata
                get() = AgentToolMetadata(risk = ToolRisk.MEDIUM, mutatesUi = true, requiresAutoVerify = true)
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("tapped", emptyMap())
        }
        val registry = ToolRegistry().register(tapTool).register(screenTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(
                        AgentToolCall("call1", "tap", "{}"),
                        AgentToolCall("call2", "tap", "{}"),
                    ),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        var steps = mutableListOf<AgentStepUi>()
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }
        val result = session.send(
            "点两次",
            verifyPolicy = AgentVerificationPolicy(enabled = true, maxPerRun = 1, minIntervalMs = 0),
            onStep = { steps += it },
        )
        assertTrue(result.ok)
        assertEquals(2, result.toolCalls.size)
        assertEquals(1, screenExecutions)
        assertEquals("test-run", result.runId)
        assertTrue(steps.all { it.runId == "test-run" })
    }

    @Test
    fun `auto verification can be disabled`() = runBlocking {
        var screenExecutions = 0
        val screenTool = object : AgentTool {
            override val name: String = "read_screen"
            override val description: String = "screen"
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                screenExecutions++
                return ToolResult.success("ok", mapOf("image_base64" to "iVBORw0KGgo="))
            }
        }
        val tapTool = object : AgentTool {
            override val name: String = "tap"
            override val description: String = "tap"
            override val metadata: AgentToolMetadata
                get() = AgentToolMetadata(mutatesUi = true, requiresAutoVerify = true)
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("tapped", emptyMap())
        }
        val registry = ToolRegistry().register(tapTool).register(screenTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "tap", "{}")),
                ),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }
        val result = session.send(
            "点一次",
            verifyPolicy = AgentVerificationPolicy(enabled = false),
        )
        assertTrue(result.ok)
        assertEquals(0, screenExecutions)
    }

    @Test
    fun `repeat detection stops after too many identical actions`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(
                        AgentToolCall("call1", "echo", """{"text":"same"}"""),
                        AgentToolCall("call2", "echo", """{"text":"same"}"""),
                        AgentToolCall("call3", "echo", """{"text":"same"}"""),
                    ),
                ),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { mapOf("text" to "same") }
        }
        val result = session.send(
            "重复三次",
            runPolicy = AgentRunPolicy(maxSamePageRepeats = 2),
        )
        assertFalse(result.ok)
        assertTrue(result.message.contains("重复"))
        assertTrue(result.toolCalls.size < 3)
    }

    @Test
    fun `run state reaches DONE on successful finish`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList())),
        )
        val states = mutableListOf<AgentRunState>()
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger())
        val result = session.send("你好", onStateChange = { states += it })
        assertTrue(result.ok)
        assertTrue(states.contains(AgentRunState.RUNNING))
        assertTrue(states.contains(AgentRunState.DONE))
    }

    @Test
    fun `llm timeout returns failure`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = object : AgentToolChat {
            override var lastError: String? = null
            override suspend fun completeWithTools(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<AgentTool>,
            ): AgentChatResponse? = null
            override suspend fun streamWithTools(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<AgentTool>,
                onEvent: (AgentStreamEvent) -> Unit,
            ): AgentChatResponse? {
                kotlinx.coroutines.delay(10_000)
                return AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList())
            }
        }
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger())
        val result = session.send(
            "超时",
            runPolicy = AgentRunPolicy(llmTimeoutMs = 100, llmRetries = 0),
        )
        assertFalse(result.ok)
        assertTrue(result.message.contains("超时"))
    }
    @Test
    fun `vision history sent to llm keeps only last two screenshots`() = runBlocking {
        val screenTool = object : AgentTool {
            override val name: String = "read_screen"
            override val description: String = "screen"
            private var n = 0
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                n++
                return ToolResult.success("img$n", mapOf("image_base64" to "img$n"))
            }
        }
        val registry = ToolRegistry().register(screenTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c1", "read_screen", "{}"))),
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c2", "read_screen", "{}"))),
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c3", "read_screen", "{}"))),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }
        val result = session.send("连续看屏")
        assertTrue(result.ok)
        val lastRequest = client.requestMessages.last()
        val images = lastRequest.filter { it.role == "user" && it.imageBase64 != null }.map { it.imageBase64 }
        assertEquals(2, images.size)
        assertFalse(images.contains("img1"))
        assertTrue(images.contains("img2"))
        assertTrue(images.contains("img3"))
    }

    @Test
    fun `visual read budget stops runaway screenshot loops`() = runBlocking {
        var screenExecutions = 0
        val screenTool = object : AgentTool {
            override val name: String = "read_screen"
            override val description: String = "screen"
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                screenExecutions++
                return ToolResult.success("ok", mapOf("image_base64" to "img"))
            }
        }
        val registry = ToolRegistry().register(screenTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c1", "read_screen", "{}"))),
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c2", "read_screen", "{}"))),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }
        val result = session.send(
            "看屏",
            runPolicy = AgentRunPolicy(maxVisualReadsPerRun = 1),
        )
        assertTrue(result.ok)
        assertEquals(1, screenExecutions)
    }

    @Test
    fun `sensitive confirmation times out and is recorded as denied`() = runBlocking {
        val sensitiveTool = object : AgentTool {
            override val name: String = "sensitive_tool"
            override val description: String = "sensitive"
            override val metadata: AgentToolMetadata = AgentToolMetadata(
                category = "测试",
                group = ToolGroup.CORE,
                risk = ToolRisk.SENSITIVE,
                sensitive = true,
            )
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("executed", emptyMap())
        }
        val registry = ToolRegistry().register(sensitiveTool)
        val client = FakeToolChatClient(
            listOf(
                AgentChatResponse(content = null, reasoningContent = null, toolCalls = listOf(AgentToolCall("c1", "sensitive_tool", "{}"))),
                AgentChatResponse(content = "完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val session = AgentSession(registry, client, NoOpTrace, TaskPlanStore(InMemoryTaskPlanPersistence()), InMemoryAgentRunLedger()).apply {
            argumentParser = { emptyMap() }
        }
        val result = session.send(
            "执行敏感",
            runPolicy = AgentRunPolicy(sensitiveConfirmTimeoutMs = 100),
            onSensitiveAction = {
                kotlinx.coroutines.delay(10_000)
                true
            },
        )
        assertTrue(result.ok)
        assertEquals(1, result.safetyConfirmations)
        assertEquals(0, result.safetyApprovals)
        assertEquals(1, result.safetyDenials)
    }


}
