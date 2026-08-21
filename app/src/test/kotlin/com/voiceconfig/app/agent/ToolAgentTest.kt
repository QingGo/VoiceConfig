package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAgentTest {

    private class EchoTool : AgentTool {
        override val name: String = "echo"
        override val description: String = "echo test"
        override suspend fun execute(args: Map<String, Any?>): ToolResult {
            val text = args["text"]?.toString() ?: ""
            return ToolResult.success("echo:$text", mapOf("text" to text))
        }
    }

    @Test
    fun `executes sequence in order`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val executor = ActionSequenceExecutor(registry)
        val sequence = listOf(
            ToolCall("echo", mapOf("text" to "a")),
            ToolCall("echo", mapOf("text" to "b")),
        )
        val results = executor.execute(sequence)
        assertEquals(2, results.size)
        assertTrue(results[0].result.ok)
        assertEquals("echo:a", results[0].result.message)
        assertEquals("echo:b", results[1].result.message)
    }

    @Test
    fun `unknown tool returns failure`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val executor = ActionSequenceExecutor(registry)
        val results = executor.execute(listOf(ToolCall("nope", emptyMap())))
        assertFalse(results[0].result.ok)
        assertTrue(results[0].result.message.contains("未知工具"))
    }

    @Test
    fun `respects max steps`() = runBlocking {
        val registry = ToolRegistry().register(EchoTool())
        val executor = ActionSequenceExecutor(registry, maxSteps = 1)
        val results = executor.execute(
            listOf(
                ToolCall("echo", mapOf("text" to "a")),
                ToolCall("echo", mapOf("text" to "b")),
            ),
        )
        assertEquals(1, results.size)
        assertFalse(results[0].result.ok)
        assertTrue(results[0].result.message.contains("上限"))
    }

    @Test
    fun `wait tool waits`() = runBlocking {
        val tool = WaitTool()
        val result = tool.execute(mapOf("ms" to 1))
        assertTrue(result.ok)
        assertEquals(1L, result.data["waitedMs"])
    }

    @Test
    fun `plugin registry aggregates tools and unload removes them`() = runBlocking {
        val plugin = SimpleAgentPlugin(
            id = "test-plugin",
            name = "Test Plugin",
            version = "1.0",
            tools = listOf(EchoTool(), WaitTool()),
        )
        val registry = PluginRegistry().load(plugin)
        assertEquals(listOf("echo", "wait"), registry.toolRegistry().names().sorted())

        val executor = ActionSequenceExecutor(registry.toolRegistry())
        val result = executor.execute(listOf(ToolCall("echo", mapOf("text" to "x"))))
        assertTrue(result[0].result.ok)

        registry.unload("test-plugin")
        assertEquals(0, registry.toolRegistry().size())
    }
}
