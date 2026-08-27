package com.voiceconfig.app.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量唤醒词检测器（远场唤醒可行性基础版）。
 *
 * 使用 Android SpeechRecognizer 持续识别并匹配关键词。
 * 注意：这只是可行性基础，正式版建议使用 Edge Impulse / 本地小模型降低功耗。
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface Listener {
        fun onWakeWord(text: String)
        fun onError(error: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var keywords: List<String> = emptyList()
    @Volatile private var running = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener, keywords: List<String>) {
        if (running) return
        if (!isAvailable) {
            listener.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        this.listener = listener
        this.keywords = keywords.map { it.lowercase(Locale.ROOT) }
        running = true
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listener.onError(error)
                if (running) restart()
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                val matched = this@WakeWordDetector.keywords.any { text.contains(it, ignoreCase = true) }
                if (matched) listener.onWakeWord(text)
                if (running) restart()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    fun stop() {
        running = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun restart() {
        if (!running) return
        recognizer?.cancel()
        android.os.Handler(context.mainLooper).postDelayed({
            if (running) startListening()
        }, 500)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }
}
