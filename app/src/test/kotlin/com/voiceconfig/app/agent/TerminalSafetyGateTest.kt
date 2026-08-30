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

    @Test
    fun `ignores terminal keywords when foreground is voiceconfig itself`() {
        // 言控会话页可能显示任务描述“免密支付/发送”，这不是真实终端页。
        assertFalse(
            TerminalSafetyGate.isTerminal(
                "未完成任务：在瑞幸点一杯冰美式，到达免密支付页后停下",
                "瑞幸下单",
                TerminalSafetyGate.SELF_PACKAGE,
            ),
        )
        assertFalse(
            TerminalSafetyGate.isTerminal(
                "任务：给微信联系人发送消息",
                "微信回复",
                TerminalSafetyGate.SELF_PACKAGE,
            ),
        )
    }

    @Test
    fun `detects english send button for wechat terminal`() {
        assertTrue(
            TerminalSafetyGate.isTerminal(
                "输入框已填入消息，下方有 Send 按钮",
                "微信发送消息",
                "com.tencent.mm",
            ),
        )
    }

    @Test
    fun `still detects terminal keywords for external app foreground`() {
        val hit = TerminalSafetyGate.detect(
            "确认订单 免密支付",
            "瑞幸下单",
            "com.lucky.luckyclient",
        )
        assertEquals(TerminalSafetyGate.TerminalKind.PAYMENT, hit.kind)
    }
}
