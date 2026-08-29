package com.voiceconfig.app.ai

import com.voiceconfig.app.ai.SpeechTextCleaner
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeakerTest {
    @Test
    fun `removes markdown symbols`() {
        val input = "**订单已确认**：\n- 生椰拿铁\n- ¥10.9"
        val output = SpeechTextCleaner.cleanForSpeech(input)
        assertEquals("订单已确认：\n生椰拿铁\n¥10.9", output)
    }

    @Test
    fun `removes inline code and links`() {
        val input = "Use `tap_text` and [文档](https://example.com)"
        val output = SpeechTextCleaner.cleanForSpeech(input)
        assertEquals("Use tap_text and 文档", output)
    }
}
