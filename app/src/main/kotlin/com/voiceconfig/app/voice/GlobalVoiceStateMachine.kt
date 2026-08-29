package com.voiceconfig.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 全局悬浮球/语音会话的纯状态机。
 *
 * 不依赖 Android Service、WindowManager 或 SpeechRecognizer，
 * 可以直接在 JVM 单元测试中验证：
 *
 *   show -> startListening -> partial -> result -> confirm
 *   show -> startListening -> timeout -> idle
 */
enum class GlobalVoicePhase {
    HIDDEN,
    IDLE,
    LISTENING,
    CONFIRMING,
}

data class GlobalVoiceUiState(
    val phase: GlobalVoicePhase = GlobalVoicePhase.HIDDEN,
    val partialText: String = "",
    val recognizedText: String? = null,
    val source: VoiceCommandSource? = null,
)

class GlobalVoiceStateMachine @Inject constructor() {
    private val _state = MutableStateFlow(GlobalVoiceUiState())
    val state: StateFlow<GlobalVoiceUiState> = _state.asStateFlow()

    fun show(): Boolean {
        val current = _state.value
        if (current.phase == GlobalVoicePhase.HIDDEN) {
            _state.value = GlobalVoiceUiState(phase = GlobalVoicePhase.IDLE, source = current.source)
            return true
        }
        return false
    }

    fun hide(): Boolean {
        if (_state.value.phase == GlobalVoicePhase.HIDDEN) return false
        _state.value = GlobalVoiceUiState()
        return true
    }

    fun startListening(source: VoiceCommandSource): Boolean {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.IDLE && current.phase != GlobalVoicePhase.HIDDEN) return false
        _state.value = GlobalVoiceUiState(
            phase = GlobalVoicePhase.LISTENING,
            source = source,
        )
        return true
    }

    fun updatePartial(text: String): Boolean {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.LISTENING) return false
        _state.value = current.copy(partialText = text)
        return true
    }

    fun finishListening(text: String): Boolean {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.LISTENING) return false
        val normalized = text.trim()
        _state.value = if (normalized.isBlank()) {
            current.copy(
                phase = GlobalVoicePhase.IDLE,
                partialText = "",
                recognizedText = null,
            )
        } else {
            current.copy(
                phase = GlobalVoicePhase.CONFIRMING,
                partialText = "",
                recognizedText = normalized,
            )
        }
        return true
    }

    fun timeout(): Boolean {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.LISTENING) return false
        _state.value = current.copy(
            phase = GlobalVoicePhase.IDLE,
            partialText = "",
            recognizedText = null,
        )
        return true
    }

    fun confirm(): String? {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.CONFIRMING) return null
        val text = current.recognizedText
        _state.value = current.copy(
            phase = GlobalVoicePhase.IDLE,
            partialText = "",
            recognizedText = null,
        )
        return text
    }

    fun cancel(): Boolean {
        val current = _state.value
        if (current.phase != GlobalVoicePhase.CONFIRMING) return false
        _state.value = current.copy(
            phase = GlobalVoicePhase.IDLE,
            partialText = "",
            recognizedText = null,
        )
        return true
    }
}
