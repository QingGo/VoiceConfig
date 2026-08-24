package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentVerificationMatrixTest {

    @Test
    fun `open app requires foreground verification`() {
        assertEquals(
            VerificationRequirement.FOREGROUND,
            AgentVerificationMatrix.specFor("open_app").requirement,
        )
    }

    @Test
    fun `reminder and scheduled tasks require persistence verification`() {
        assertEquals(
            VerificationRequirement.TASK_CREATED,
            AgentVerificationMatrix.specFor("create_reminder").requirement,
        )
        assertEquals(
            VerificationRequirement.TASK_CREATED,
            AgentVerificationMatrix.specFor("create_scheduled_task").requirement,
        )
    }

    @Test
    fun `unknown tools default to none`() {
        assertEquals(
            VerificationRequirement.NONE,
            AgentVerificationMatrix.specFor("nope").requirement,
        )
    }
}
