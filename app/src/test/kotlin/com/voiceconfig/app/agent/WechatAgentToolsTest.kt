package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatAgentToolsTest {

    @Test
    fun `draft reply never sends`() = runBlocking {
        val tool = WechatDraftReplyTool()
        val result = tool.execute(
            mapOf(
                "receiver" to "老板",
                "context" to "明天开会",
                "reply" to "好的，我明天准时参加。",
            ),
        )
        assertTrue(result.ok)
        val draft = result.data["draft"] as? WechatReplyDraft
        assertEquals("老板", draft?.receiver)
        assertEquals(true, result.data["requiresConfirmation"])
        assertEquals(true, result.data["safe"])
        assertTrue(result.message.contains("未发送"))
    }

    @Test
    fun `draft reply requires content`() = runBlocking {
        val tool = WechatDraftReplyTool()
        val result = tool.execute(mapOf("receiver" to "老板"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("reply"))
    }
}
