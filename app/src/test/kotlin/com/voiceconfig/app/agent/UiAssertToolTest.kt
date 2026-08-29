package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAssertToolTest {

    @Test
    fun `ui assert requires a selector`() = runBlocking {
        val tool = UiAssertTool(UiActionLayer(ShizukuCommandRunner()))
        val result = tool.execute(mapOf("action" to "visible"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("resourceId/text/desc"))
    }
}
