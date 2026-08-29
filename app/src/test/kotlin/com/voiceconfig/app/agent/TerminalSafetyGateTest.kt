package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSafetyGateTest {

    @Test
    fun `detects payment terminal page`() {
        val hit = TerminalSafetyGate.detect("当前页面包含 免密支付 按钮", "瑞幸下单")
        assertEquals(TerminalSafetyGate.TerminalKind.PAYMENT, hit.kind)
        assertTrue(TerminalSafetyGate.isTerminal("页面：确认订单", "买咖啡"))
    }

    @Test
    fun `detects send terminal only for communication goal`() {
        val sendHit = TerminalSafetyGate.detect(
            "输入框下方有 发送 按钮",
            "给张三发送微信消息",
        )
        assertEquals(TerminalSafetyGate.TerminalKind.SEND, sendHit.kind)

        assertFalse(
            TerminalSafetyGate.isTerminal("页面有 发送 按钮", "打开设置"),
        )
    }

    @Test
    fun `detects explicit send confirmation regardless of goal`() {
        val hit = TerminalSafetyGate.detect("确认发送消息？", "任意任务")
        assertEquals(TerminalSafetyGate.TerminalKind.SEND, hit.kind)
    }

    @Test
    fun `no terminal on ordinary page`() {
        assertFalse(TerminalSafetyGate.isTerminal("商品列表、门店选择、购物车", "买咖啡"))
    }
}
