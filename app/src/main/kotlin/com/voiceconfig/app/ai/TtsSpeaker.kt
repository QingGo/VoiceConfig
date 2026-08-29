package com.voiceconfig.app.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 系统 TTS 封装，用于 Agent 结果播报（Phase H1）。
 */
@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINA)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun speak(text: String) {
        val cleaned = SpeechTextCleaner.cleanForSpeech(text)
        if (cleaned.isBlank() || !ready) return
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/**
 * 纯文本化，供 TTS 使用，避免朗读 Markdown 符号。
 */
object SpeechTextCleaner {
    fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
            .replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace("*", "")
            .replace("~~", "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
