package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPreflightTest {

    private val fullCapability = AgentCapabilitySnapshot(
        shizukuAvailable = true,
        accessibilityEnabled = true,
        cloudLlmAvailable = true,
        exactAlarmAvailable = true,
        batteryOptimizationIgnored = true,
        networkAvailable = true,
    )

    @Test
    fun `full capability is ready`() {
        val result = AgentPreflight.evaluate(fullCapability, "打开企业微信")
        assertTrue(result.ready)
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `missing cloud llm blocks agent`() {
        val snapshot = fullCapability.copy(cloudLlmAvailable = false)
        val result = AgentPreflight.evaluate(snapshot, "打开企业微信")
        assertFalse(result.ready)
        assertTrue(result.blockers.any { it.code == "NO_CLOUD_LLM" })
    }

    @Test
    fun `missing ui control blocks interaction tasks but allows simple open and remote tasks`() {
        val snapshot = fullCapability.copy(
            shizukuAvailable = false,
            accessibilityEnabled = false,
        )
        val open = AgentPreflight.evaluate(snapshot, "打开企业微信")
        assertTrue(open.ready)
        assertTrue(open.warnings.any { it.code == "OPEN_APP_MAY_NOT_VERIFY" })

        val interact = AgentPreflight.evaluate(snapshot, "点击发送")
        assertFalse(interact.ready)
        assertTrue(interact.blockers.any { it.code == "NO_UI_CONTROL" })

        val remote = AgentPreflight.evaluate(snapshot, "在树莓派上创建一个 Web 项目")
        assertTrue(remote.ready)
    }

    @Test
    fun `missing exact alarm and battery optimization produce warnings`() {
        val snapshot = fullCapability.copy(
            exactAlarmAvailable = false,
            batteryOptimizationIgnored = false,
        )
        val result = AgentPreflight.evaluate(snapshot, "每天早上8点打开企业微信")
        assertTrue(result.ready)
        assertTrue(result.warnings.any { it.code == "NO_EXACT_ALARM" })
        assertTrue(result.warnings.any { it.code == "BATTERY_OPTIMIZATION" })
    }
}
