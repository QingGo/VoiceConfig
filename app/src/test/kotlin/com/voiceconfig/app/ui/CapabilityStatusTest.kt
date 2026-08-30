package com.voiceconfig.app.ui

import com.voiceconfig.app.agent.AgentCapabilitySnapshot
import com.voiceconfig.app.agent.AgentExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityStatusTest {

    private val ready = AgentCapabilitySnapshot(
        shizukuAvailable = true,
        accessibilityEnabled = true,
        cloudLlmAvailable = true,
        exactAlarmAvailable = true,
        batteryOptimizationIgnored = true,
        networkAvailable = true,
        mediaProjectionAvailable = false,
    )

    @Test
    fun `ready snapshot maps to ready capability`() {
        val status = CapabilityStatusMapper.from(
            snapshot = ready,
            homeAssistantConfigured = true,
            remoteNodeCount = 2,
            wakeWordEnabled = true,
        )
        assertTrue(status.canRunAgent)
        assertTrue(status.canControlUi)
        assertTrue(status.homeAssistant)
        assertTrue(status.wakeWord)
        assertTrue(status.exactAlarm)
    }

    @Test
    fun `missing permissions are reflected`() {
        val status = CapabilityStatusMapper.from(
            snapshot = ready.copy(
                accessibilityEnabled = false,
                shizukuAvailable = false,
                cloudLlmAvailable = false,
            ),
            homeAssistantConfigured = false,
            remoteNodeCount = 0,
            wakeWordEnabled = false,
        )
        assertFalse(status.canRunAgent)
        assertFalse(status.canControlUi)
        assertFalse(status.accessibility)
        assertFalse(status.homeAssistant)
    }
    @Test
    fun `execution mode follows available channels`() {
        val assist = CapabilityStatusMapper.from(
            snapshot = ready,
            homeAssistantConfigured = false,
            remoteNodeCount = 0,
            wakeWordEnabled = false,
        )
        assertEquals(AgentExecutionMode.ASSIST, assist.executionMode)

        val ambient = CapabilityStatusMapper.from(
            snapshot = ready.copy(shizukuAvailable = false, accessibilityEnabled = true),
            homeAssistantConfigured = false,
            remoteNodeCount = 0,
            wakeWordEnabled = false,
        )
        assertEquals(AgentExecutionMode.AMBIENT, ambient.executionMode)

        val notify = CapabilityStatusMapper.from(
            snapshot = ready.copy(shizukuAvailable = false, accessibilityEnabled = false),
            homeAssistantConfigured = false,
            remoteNodeCount = 0,
            wakeWordEnabled = false,
        )
        assertEquals(AgentExecutionMode.NOTIFY, notify.executionMode)
    }


}
