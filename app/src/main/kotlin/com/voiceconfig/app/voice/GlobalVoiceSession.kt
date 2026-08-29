package com.voiceconfig.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局语音会话状态机：
 *
 * 聆听、超时、确认、提交命令都在这里编排。
 * 不依赖 Android Service，具体识别能力通过 [GlobalSpeechInputFactory] 注入。
 */
@Singleton
class GlobalVoiceSession @Inject constructor(
    private val voiceCommandCenter: VoiceCommandCenter,
    private val stateMachine: GlobalVoiceStateMachine,
    private val speechInputFactory: GlobalSpeechInputFactory,
    private val powerPolicy: GlobalPowerPolicy,
) {
    val state: StateFlow<GlobalVoiceUiState> = stateMachine.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var input: GlobalSpeechInput? = null
    private var timeoutJob: Job? = null
    private var currentSource: VoiceCommandSource? = null

    fun show() {
        stateMachine.show()
    }

    fun hide() {
        stop()
        stateMachine.hide()
    }

    fun startListening(source: VoiceCommandSource): Boolean {
        if (!powerPolicy.canListen()) return false
        if (!stateMachine.startListening(source)) return false
        currentSource = source
        val created = speechInputFactory.create()
        val callbacks = GlobalSpeechInput.Callbacks(
            onResult = { text ->
                onResult(text)
            },
            onPartial = { text ->
                onPartial(text)
            },
            onError = { _ ->
                onError()
            },
        )
        val started = runCatching { created.start(callbacks) }.getOrDefault(false)
        if (!started) {
            runCatching { created.stop() }
            stateMachine.timeout()
            return false
        }
        input = created
        scheduleTimeout()
        return true
    }

    fun onPartial(text: String) {
        if (text.isBlank()) return
        stateMachine.updatePartial(text.take(4))
    }

    fun onResult(text: String) {
        cancelTimeout()
        input?.stop()
        input = null
        stateMachine.finishListening(text)
    }

    fun onError() {
        cancelTimeout()
        input?.stop()
        input = null
        stateMachine.timeout()
    }

    fun onTimeout() {
        cancelTimeout()
        input?.stop()
        input = null
        stateMachine.timeout()
    }

    fun confirm(): String? {
        val text = stateMachine.confirm() ?: return null
        val source = currentSource ?: VoiceCommandSource.GLOBAL_BALL
        voiceCommandCenter.submit(
            text = text,
            source = source,
            autoSend = true,
            confirmationToken = UUID.randomUUID().toString(),
        )
        return text
    }

    fun cancel() {
        stateMachine.cancel()
    }

    fun stop() {
        cancelTimeout()
        input?.stop()
        input = null
        currentSource = null
        when (stateMachine.state.value.phase) {
            GlobalVoicePhase.LISTENING -> stateMachine.timeout()
            GlobalVoicePhase.CONFIRMING -> stateMachine.cancel()
            else -> Unit
        }
    }

    private fun scheduleTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            onTimeout()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    companion object {
        const val TIMEOUT_MS = 12_000L
    }
}
