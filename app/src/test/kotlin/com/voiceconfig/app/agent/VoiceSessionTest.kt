package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionTest {

    @Test
    fun `voice session lifecycle`() {
        val manager = VoiceSessionManager()
        assertEquals(VoiceSessionState.IDLE, manager.current().state)

        val executing = manager.begin("关空调")
        assertEquals(VoiceSessionState.EXECUTING, executing.state)
        assertEquals("关空调", executing.goal)

        manager.markClarifying("您想调到多少度？")
        assertEquals(VoiceSessionState.CLARIFYING, manager.current().state)

        manager.markExecuting()
        assertEquals(VoiceSessionState.EXECUTING, manager.current().state)

        manager.waitUser("请确认调至26度")
        assertEquals(VoiceSessionState.AWAITING_USER, manager.current().state)
        assertEquals("请确认调至26度", manager.current().pendingConfirmation)

        manager.complete()
        assertEquals(VoiceSessionState.COMPLETED, manager.current().state)
        manager.reset()
        assertEquals(VoiceSessionState.IDLE, manager.current().state)
    }
}
