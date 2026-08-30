package com.voiceconfig.app.agent

import com.voiceconfig.app.service.AccessibilityKeepAlive
import com.voiceconfig.app.service.AccessibilityKeepAliveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityKeepAliveTest {

    private fun keepAlive(probe: () -> Boolean): AccessibilityKeepAlive =
        AccessibilityKeepAlive(ShizukuCommandRunner()).apply {
            instanceProbe = probe
        }

    @Test
    fun `connected probe moves state to connected and records success`() {
        val keepAlive = keepAlive { true }
        val state = keepAlive.refresh()
        assertEquals(AccessibilityKeepAliveState.CONNECTED, state)
        assertTrue(keepAlive.lastSuccessAtMs > 0)
        assertEquals(0, keepAlive.consecutiveFailures)
        assertEquals(1, keepAlive.refreshCount)
    }

    @Test
    fun `shizuku unavailable keeps disconnected and records error`() {
        val keepAlive = keepAlive { false }
        val state = keepAlive.refresh()
        assertEquals(AccessibilityKeepAliveState.DISCONNECTED, state)
        assertNotNull(keepAlive.lastError)
        assertEquals(1, keepAlive.refreshCount)
    }
}
