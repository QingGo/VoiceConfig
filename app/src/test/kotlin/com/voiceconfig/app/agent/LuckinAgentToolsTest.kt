package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuckinAgentToolsTest {

    @Test
    fun `prepare order creates confirmation draft without ordering`() = runBlocking {
        val tool = LuckinPrepareOrderTool()
        val result = tool.execute(
            mapOf(
                "store" to "软件园店",
                "drink" to "生椰拿铁",
                "size" to "大杯",
                "sugar" to "少糖",
                "ice" to "少冰",
                "quantity" to 2,
                "price" to 19.9,
            ),
        )
        assertTrue(result.ok)
        val draft = result.data["draft"] as? LuckinOrderDraft
        assertEquals("生椰拿铁", draft?.drink)
        assertEquals(2, draft?.quantity)
        assertEquals(true, result.data["requiresConfirmation"])
        assertEquals(true, result.data["safe"])
        assertTrue(result.message.contains("未下单"))
    }

    @Test
    fun `prepare order requires drink`() = runBlocking {
        val tool = LuckinPrepareOrderTool()
        val result = tool.execute(mapOf("store" to "某门店"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("drink"))
    }
}
