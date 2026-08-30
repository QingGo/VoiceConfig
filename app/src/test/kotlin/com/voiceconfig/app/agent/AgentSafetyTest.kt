package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
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

    @Test
    fun `safety levels classify read prepare confirm and irreversible`() {
        assertEquals(SafetyLevel.READ_ONLY, safety.decide("read_ui", emptyMap()).level)
        assertEquals(SafetyLevel.PREPARE, safety.decide("tap", mapOf("x" to 100, "y" to 200)).level)
        assertEquals(SafetyLevel.CONFIRM, safety.decide("run_shell", mapOf("command" to "getprop")).level)
        assertEquals(SafetyLevel.CONFIRM, safety.decide("file_write", mapOf("path" to "a.txt")).level)
        assertEquals(SafetyLevel.IRREVERSIBLE, safety.decide("tap_text", mapOf("text" to "确认支付")).level)
    }

    @Test
    fun `hard blocked decisions cannot be bypassed by auto confirm`() {
        val decision = safety.decide("tap_text", mapOf("text" to "确认支付"))
        assertTrue(decision.blocked)
        assertTrue(decision.requiresConfirmation)
        assertTrue(safety.isAlwaysBlocked("tap_text", mapOf("text" to "确认支付")))
        assertTrue(safety.isAlwaysBlocked("input_text", mapOf("text" to "发送")))
    }

    @Test
    fun `home security devices require confirmation`() {
        assertTrue(safety.requiresConfirmation("home_control", mapOf("domain" to "lock", "service" to "unlock")))
        assertTrue(safety.requiresConfirmation("home_control", mapOf("domain" to "camera", "service" to "turn_on")))
        assertFalse(safety.requiresConfirmation("home_control", mapOf("domain" to "climate", "service" to "set_temperature")))
        assertFalse(safety.requiresConfirmation("home_control", mapOf("domain" to "light", "service" to "turn_on")))
    }

    @Test
    fun `sensitive metadata tools confirm without keyword hit`() {
        val tool = object : AgentTool {
            override val name: String = "remote_ssh_exec"
            override val description: String = "remote exec"
            override val metadata: AgentToolMetadata
                get() = AgentToolMetadata(risk = ToolRisk.SENSITIVE, sensitive = true)
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("ok", emptyMap())
        }
        val decision = safety.decide(tool, mapOf("command" to "ls"))
        assertEquals(SafetyLevel.CONFIRM, decision.level)
        assertTrue(decision.requiresConfirmation)
        assertFalse(decision.blocked)
    }

    @Test
    fun `personal wechat automation is blocked by default`() {
        WechatRiskGuard.setAutomationAllowed(false)
        try {
            val open = safety.decide("wechat_open", emptyMap())
            assertTrue(open.blocked)
            assertFalse(open.requiresConfirmation)

            val genericOpen = safety.decide("open_app", mapOf("package" to "com.tencent.mm"))
            assertTrue(genericOpen.blocked)

            val tapInWechat = safety.decide("tap", mapOf("x" to 1, "y" to 2), foregroundPackage = "com.tencent.mm")
            assertTrue(tapInWechat.blocked)

            val readInWechat = safety.decide("read_screen", emptyMap(), foregroundPackage = "com.tencent.mm")
            assertTrue(readInWechat.blocked)

            val wework = safety.decide("wework_open", emptyMap(), foregroundPackage = "com.tencent.wework")
            assertFalse(wework.blocked)
        } finally {
            WechatRiskGuard.setAutomationAllowed(false)
        }
    }

    @Test
    fun `personal wechat automation can be enabled for explicit test accounts`() {
        WechatRiskGuard.setAutomationAllowed(true)
        try {
            val open = safety.decide("wechat_open", emptyMap())
            assertFalse(open.blocked)
            assertFalse(open.requiresConfirmation)
        } finally {
            WechatRiskGuard.setAutomationAllowed(false)
        }
    }


}
