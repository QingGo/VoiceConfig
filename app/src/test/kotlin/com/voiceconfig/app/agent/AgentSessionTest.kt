package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoOpTrace : AgentTrace {
    override fun log(type: String, data: Map<String, Any?>) {}
    override fun saveScreenshot(base64: String, label: String): String = ""
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
        ): AgentChatResponse? = responses.getOrNull(callCount++)

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
        val session = AgentSession(registry, client, NoOpTrace).apply {
            argumentParser = { mapOf("text" to "hi") }
        }

        val result = session.send("说 hi")
        assertTrue(result.ok)
        assertEquals(1, result.toolCalls.size)
        assertTrue(result.message.contains("完成"))
        assertTrue(result.history.any { it.role == "tool" && it.toolName == "echo" })
        assertEquals(4, result.history.size) // user + assistant + tool + assistant final
        assertTrue(result.history.any { it.role == "assistant" && it.reasoningContent == "need echo" })
        val secondRequest = client.requestMessages.getOrNull(1)
        assertTrue(
            "第二轮请求必须回传 reasoning_content",
            secondRequest?.any { it.role == "assistant" && it.reasoningContent == "need echo" } == true,
        )
    }

    @Test
    fun `session returns text when no tool calls`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(
            listOf(AgentChatResponse(content = "你好", reasoningContent = null, toolCalls = emptyList())),
        )
        val session = AgentSession(registry, client, NoOpTrace)

        val result = session.send("你好")
        assertTrue(result.ok)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("你好", result.message)
        assertEquals(2, result.history.size)
    }

    @Test
    fun `session reports chat failure`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val client = FakeToolChatClient(listOf(null))
        val session = AgentSession(registry, client, NoOpTrace)
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
        val session = AgentSession(registry, client, NoOpTrace).apply {
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
}
