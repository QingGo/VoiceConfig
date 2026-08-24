package com.voiceconfig.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSafetyTest {

    private val safety = AgentSafety()

    @Test
    fun `read tools are not sensitive`() {
        assertFalse(safety.requiresConfirmation("read_screen", emptyMap()))
        assertFalse(safety.requiresConfirmation("read_ui", emptyMap()))
        assertFalse(safety.requiresConfirmation("find_app", mapOf("keyword" to "微信")))
    }

    @Test
    fun `send and payment text triggers confirmation`() {
        assertTrue(safety.requiresConfirmation("tap_text", mapOf("text" to "发送")))
        assertTrue(safety.requiresConfirmation("tap_text", mapOf("texts" to listOf("确认支付"))))
        assertTrue(safety.requiresConfirmation("tap", mapOf("x" to 100, "y" to 200, "reason" to "立即购买")))
    }

    @Test
    fun `ordinary taps do not require confirmation`() {
        assertFalse(safety.requiresConfirmation("tap", mapOf("x" to 100, "y" to 200)))
        assertFalse(safety.requiresConfirmation("tap_text", mapOf("text" to "标准美式")))
        assertFalse(safety.requiresConfirmation("input_text", mapOf("text" to "hello")))
    }

    @Test
    fun `shell and file write require confirmation but navigation keys do not`() {
        assertTrue(safety.requiresConfirmation("run_shell", mapOf("command" to "getprop")))
        assertTrue(safety.requiresConfirmation("file_write", mapOf("path" to "a.txt")))
        assertFalse(safety.requiresConfirmation("press_key", mapOf("key" to "enter")))
        assertFalse(safety.requiresConfirmation("press_key", mapOf("key" to "back")))
    }


    @Test
    fun `hard safety gate blocks final pay and delete even with auto confirm`() {
        assertTrue(safety.isAlwaysBlocked("tap_text", mapOf("text" to "确认支付")))
        assertTrue(safety.isAlwaysBlocked("tap_text", mapOf("text" to "立即支付")))
        assertTrue(safety.isAlwaysBlocked("input_text", mapOf("text" to "发送")))
        assertTrue(safety.isAlwaysBlocked("tap_text", mapOf("text" to "删除")))
    }

    @Test
    fun `hard safety gate allows entering order page`() {
        assertFalse(safety.isAlwaysBlocked("tap_text", mapOf("text" to "立即购买")))
        assertFalse(safety.isAlwaysBlocked("tap_text", mapOf("text" to "去结算")))
        assertFalse(safety.isAlwaysBlocked("read_ui", emptyMap()))
    }

}
