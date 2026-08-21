package com.voiceconfig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentNavigationTest {

    @Test
    fun `new session should switch to conversation tab`() {
        assertEquals(
            AgentNavigation.TAB_CONVERSATION,
            AgentNavigation.tabAfterNewSession(),
        )
    }

    @Test
    fun `selecting a session should switch to conversation tab`() {
        assertEquals(
            AgentNavigation.TAB_CONVERSATION,
            AgentNavigation.tabAfterSelectSession(),
        )
    }
}
