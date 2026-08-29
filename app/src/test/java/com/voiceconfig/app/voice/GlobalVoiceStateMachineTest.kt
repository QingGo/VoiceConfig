package com.voiceconfig.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalVoiceStateMachineTest {

    @Test
    fun `initial state is hidden`() {
        val machine = GlobalVoiceStateMachine()
        assertEquals(GlobalVoicePhase.HIDDEN, machine.state.value.phase)
    }

    @Test
    fun `happy path show listen result confirm`() {
        val machine = GlobalVoiceStateMachine()
        assertTrue(machine.show())
        assertTrue(machine.startListening(VoiceCommandSource.GLOBAL_BALL))
        assertTrue(machine.updatePartial("打开"))
        machine.updatePartial("打开设置")
        assertEquals("打开设置", machine.state.value.partialText)
        assertTrue(machine.finishListening("打开设置"))
        assertEquals(GlobalVoicePhase.CONFIRMING, machine.state.value.phase)
        assertEquals("打开设置", machine.state.value.recognizedText)
        val confirmed = machine.confirm()
        assertEquals("打开设置", confirmed)
        assertEquals(GlobalVoicePhase.IDLE, machine.state.value.phase)
    }

    @Test
    fun `timeout returns to idle and clears partial`() {
        val machine = GlobalVoiceStateMachine()
        machine.show()
        machine.startListening(VoiceCommandSource.WAKE_WORD)
        machine.updatePartial("你好")
        assertTrue(machine.timeout())
        assertEquals(GlobalVoicePhase.IDLE, machine.state.value.phase)
        assertEquals("", machine.state.value.partialText)
        assertNull(machine.state.value.recognizedText)
    }

    @Test
    fun `cancel from confirming returns to idle`() {
        val machine = GlobalVoiceStateMachine()
        machine.show()
        machine.startListening(VoiceCommandSource.GLOBAL_BALL)
        machine.finishListening("打开微信")
        assertTrue(machine.cancel())
        assertEquals(GlobalVoicePhase.IDLE, machine.state.value.phase)
        assertNull(machine.state.value.recognizedText)
    }

    @Test
    fun `hide clears everything`() {
        val machine = GlobalVoiceStateMachine()
        machine.show()
        machine.startListening(VoiceCommandSource.GLOBAL_BALL)
        machine.finishListening("打开设置")
        assertTrue(machine.hide())
        assertEquals(GlobalVoicePhase.HIDDEN, machine.state.value.phase)
        assertNull(machine.state.value.source)
    }

    @Test
    fun `cannot listen when confirming but can listen from hidden`() {
        val machine = GlobalVoiceStateMachine()
        assertTrue(machine.startListening(VoiceCommandSource.GLOBAL_BALL))
        machine.hide()
        machine.show()
        machine.startListening(VoiceCommandSource.GLOBAL_BALL)
        machine.finishListening("测试")
        assertFalse(machine.startListening(VoiceCommandSource.WAKE_WORD))
    }

    @Test
    fun `start listening from hidden is allowed for wake word`() {
        val machine = GlobalVoiceStateMachine()
        assertTrue(machine.startListening(VoiceCommandSource.WAKE_WORD))
        assertEquals(GlobalVoicePhase.LISTENING, machine.state.value.phase)
    }
}
