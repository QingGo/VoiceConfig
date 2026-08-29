package com.voiceconfig.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalPowerPolicyTest {

    @Test
    fun `allows listening by default`() {
        assertTrue(GlobalPowerPolicy().canListen())
    }

    @Test
    fun `screen off blocks listening`() {
        val policy = GlobalPowerPolicy()
        policy.setScreenOff(true)
        assertFalse(policy.canListen())
        policy.setScreenOff(false)
        assertTrue(policy.canListen())
    }

    @Test
    fun `low battery blocks listening until charging`() {
        val policy = GlobalPowerPolicy()
        policy.setLowBattery(true)
        assertFalse(policy.canListen())
        policy.setLowBattery(false)
        assertTrue(policy.canListen())
    }
}
