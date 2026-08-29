package com.voiceconfig.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voiceconfig.app.ai.LocalAsrManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局语音识别路由：
 * - 本地 ASR 已就绪时优先使用本地模型；
 * - 否则回退到 Android 系统 SpeechRecognizer。
 */
@Singleton
class GlobalSpeechRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localAsrManager: LocalAsrManager,
) : GlobalSpeechInputFactory {

    override fun create(): GlobalSpeechInput {
        return if (localAsrManager.isModelAvailable()) {
            LocalAsrSpeechInput(localAsrManager)
        } else {
            SystemSpeechInput(context)
        }
    }
}

private class LocalAsrSpeechInput(
    private val localAsrManager: LocalAsrManager,
) : GlobalSpeechInput {

    override fun start(callbacks: GlobalSpeechInput.Callbacks): Boolean {
        if (!localAsrManager.isModelAvailable()) return false
        localAsrManager.recognize(
            maxDurationMs = GlobalVoiceSession.TIMEOUT_MS,
            onPartialResult = callbacks.onPartial,
            onResult = callbacks.onResult,
            onError = callbacks.onError,
        )
        return true
    }

    override fun stop() {
        localAsrManager.cancel()
    }
}

private class SystemSpeechInput(
    private val context: Context,
) : GlobalSpeechInput {

    private var recognizer: SpeechRecognizer? = null
    private var started = false

    override fun start(callbacks: GlobalSpeechInput.Callbacks): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return false
        recognizer = created
        started = true
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                callbacks.onError("speech_error_$error")
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                callbacks.onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    callbacks.onPartial(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        return runCatching {
            created.startListening(intent)
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
