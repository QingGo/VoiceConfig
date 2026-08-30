package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceReportTest {

    @Test
    fun `builds summary from trace events`() {
        val events = listOf(
            mapOf("type" to "run_start", "runId" to "run_1", "userText" to "帮我买咖啡"),
            mapOf("type" to "round_timing", "round" to 1, "total_ms" to 100),
            mapOf("type" to "tool_call", "tool" to "open_app"),
            mapOf("type" to "tool_result", "tool" to "open_app", "ok" to true),
            mapOf("type" to "tool_call", "tool" to "tap_text"),
            mapOf("type" to "tool_result", "tool" to "tap_text", "ok" to true),
            mapOf("type" to "image_seen", "tool" to "read_screen"),
            mapOf("type" to "auto_verify", "tool" to "input_text"),
            mapOf("type" to "run_finished", "ok" to true, "waiting" to true, "duration_ms" to 5_000, "message" to "已停在支付前"),
        )
        val report = AgentTraceReportBuilder.build(events)
        assertEquals("run_1", report.runId)
        assertEquals("帮我买咖啡", report.userText)
        assertTrue(report.ok)
        assertTrue(report.waitingForHuman)
        assertEquals(5_000L, report.durationMs)
        assertEquals(listOf("open_app", "tap_text"), report.toolSequence)
        assertEquals(1, report.screenshotCount)
        assertEquals(1, report.verificationCount)
        assertEquals(1, report.llmRounds)
    }

    @Test
    fun `captures failure cause and safety blocks`() {
        val events = listOf(
            mapOf("type" to "run_start", "runId" to "run_2", "userText" to "发消息"),
            mapOf("type" to "safety_blocked", "tool" to "wechat_send_reply", "reason" to "个人微信自动化禁用"),
            mapOf("type" to "run_finished", "ok" to false, "duration_ms" to 1_000, "message" to "被安全策略阻止"),
        )
        val report = AgentTraceReportBuilder.build(events)
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.contains("安全拦截") })
        assertEquals(1, report.safetyBlocks)
    }

    @Test
    fun `markdown contains key metrics`() {
        val report = AgentTraceReportBuilder.build(
            listOf(
                mapOf("type" to "run_start", "runId" to "run_3", "userText" to "远程验证"),
                mapOf("type" to "run_finished", "ok" to true, "duration_ms" to 2_000, "message" to "完成"),
            ),
        )
        val md = AgentTraceReportBuilder.toMarkdown(report)
        assertTrue(md.contains("远程验证"))
        assertTrue(md.contains("2000ms"))
    }
}
