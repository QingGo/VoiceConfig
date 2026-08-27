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

class LuckinOrderSessionTest {

    @Test
    fun `luckin order session lifecycle`() {
        val manager = LuckinOrderSessionManager()
        assertEquals(LuckinOrderStep.IDLE, manager.current().step)

        manager.selectStore("软件园店")
        assertEquals(LuckinOrderStep.STORE_SELECTED, manager.current().step)

        manager.selectDrink("生椰拿铁", "大杯", "少糖", "少冰")
        assertEquals(LuckinOrderStep.DRINK_SELECTED, manager.current().step)
        assertEquals("生椰拿铁", manager.current().drink)

        manager.addToCart(2)
        assertEquals(LuckinOrderStep.IN_CART, manager.current().step)
        assertEquals(2, manager.current().quantity)

        manager.confirm()
        assertEquals(LuckinOrderStep.CONFIRMED, manager.current().step)
        manager.cancel()
        assertEquals(LuckinOrderStep.CANCELLED, manager.current().step)
        manager.reset()
        assertEquals(LuckinOrderStep.IDLE, manager.current().step)
    }
}
