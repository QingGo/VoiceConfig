package com.voiceconfig.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.voiceconfig.app.ai.AsrEngineStatus
import com.voiceconfig.app.ai.LocalAsrManager

class VoiceInputController(
    val isListening: State<Boolean>,
    val isPreparing: State<Boolean>,
    val onMicClick: () -> Unit,
    val onAgentMicClick: () -> Unit,
    val onParseClick: () -> Unit,
)

@Composable
fun rememberVoiceInputController(
    context: Context,
    localAsrManager: LocalAsrManager?,
    viewModel: MainViewModel,
    uiParsing: Boolean,
    isAgentBusy: Boolean,
): VoiceInputController {
    val isListening = remember { mutableStateOf(false) }
    val isPreparing = remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun ensureSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer == null) {
            speechRecognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        }
        return speechRecognizer
    }

    var asrEngineStatus by remember { mutableStateOf(AsrEngineStatus.COLD) }
    LaunchedEffect(localAsrManager) {
        localAsrManager?.engineStatus?.collect { status ->
            asrEngineStatus = status
            isPreparing.value = status == AsrEngineStatus.WARMING_UP || status == AsrEngineStatus.COLD
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    var activeSpeechConsumer by remember { mutableStateOf<(String) -> Unit>({ viewModel.onInputChange(it) }) }
    var pendingSpeechConsumer by remember { mutableStateOf<(String) -> Unit>({ viewModel.onInputChange(it) }) }
    var pendingSpeechPartialConsumer by remember { mutableStateOf<(String) -> Unit>({}) }
    var voiceSessionCounter by remember { mutableStateOf(0L) }
    var pendingVoiceSessionId by remember { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                activeSpeechConsumer(text)
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit = { viewModel.onInputChange(it) },
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = { viewModel.setParseMessage(it) },
    ) {
        activeSpeechConsumer = onResult
        if (asrEngineStatus == AsrEngineStatus.WARMING_UP || asrEngineStatus == AsrEngineStatus.COLD) {
            onError("语音模型准备中，请稍候")
            return
        }
        if (localAsrManager?.isModelAvailable() == true) {
            isPreparing.value = false
            isListening.value = true
            localAsrManager?.recognize(
                onPartialResult = onPartial,
                onResult = { text ->
                    isListening.value = false
                    onResult(text)
                },
                onError = { message ->
                    isListening.value = false
                    if (message != "已取消") {
                        onError(message)
                    }
                },
            )
            return
        }
        val recognizer = ensureSpeechRecognizer()
        if (recognizer == null || !SpeechRecognizer.isRecognitionAvailable(context)) {
            val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你要创建的自动化任务")
            }
            runCatching { speechLauncher.launch(fallbackIntent) }
                .onFailure { onError("无法启动系统语音识别") }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening.value = false
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要录音权限"
                    SpeechRecognizer.ERROR_NETWORK -> "语音识别网络错误"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别忙，请稍后再试"
                    else -> "语音识别失败（$error）"
                }
                onError(message)
            }
            override fun onResults(results: Bundle?) {
                isListening.value = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onResult(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onPartial(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        isListening.value = true
        recognizer.startListening(intent)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening(onResult = pendingSpeechConsumer, onPartial = pendingSpeechPartialConsumer)
        }
    }

    val onMicClick: () -> Unit = {
        if (!uiParsing && !isPreparing.value) {
            if (isListening.value) {
                localAsrManager?.cancel()
                speechRecognizer?.stopListening()
                isListening.value = false
            } else {
                voiceSessionCounter++
                val voiceSession = "home_voice_$voiceSessionCounter"
                pendingVoiceSessionId = voiceSession
                pendingSpeechConsumer = { text ->
                    viewModel.submitVoiceResult(
                        text = text,
                        asrEngine = if (localAsrManager?.isModelAvailable() == true) "local-asr" else "system",
                        toAgent = false,
                        voiceSessionId = voiceSession,
                    )
                }
                pendingSpeechPartialConsumer = { viewModel.onInputChange(it) }
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startListening(onResult = pendingSpeechConsumer, onPartial = pendingSpeechPartialConsumer)
                } else {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val onAgentMicClick: () -> Unit = {
        if (!isAgentBusy && !isPreparing.value) {
            if (isListening.value) {
                localAsrManager?.cancel()
                speechRecognizer?.stopListening()
                isListening.value = false
            } else {
                voiceSessionCounter++
                val voiceSession = "agent_voice_$voiceSessionCounter"
                pendingVoiceSessionId = voiceSession
                pendingSpeechConsumer = { text ->
                    viewModel.submitVoiceResult(
                        text = text,
                        asrEngine = if (localAsrManager?.isModelAvailable() == true) "local-asr" else "system",
                        toAgent = true,
                        voiceSessionId = voiceSession,
                    )
                }
                pendingSpeechPartialConsumer = { viewModel.onAgentInputChange(it) }
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startListening(
                        onResult = pendingSpeechConsumer,
                        onPartial = pendingSpeechPartialConsumer,
                        onError = { viewModel.setParseMessage(it) },
                    )
                } else {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val onParseClick: () -> Unit = {
        if (isListening.value) {
            localAsrManager?.cancel()
            speechRecognizer?.stopListening()
            isListening.value = false
        }
        viewModel.submitNaturalLanguageInput()
    }

    return VoiceInputController(
        isListening = isListening,
        isPreparing = isPreparing,
        onMicClick = onMicClick,
        onAgentMicClick = onAgentMicClick,
        onParseClick = onParseClick,
    )
}
