package com.voiceconfig.app.ai

/**
 * 统一的语音识别结果模型。
 *
 * 所有 ASR 引擎的输出都会先包装成 VoiceIntent，再进入：
 *   ASR -> Intent -> Planner -> Executor -> Verifier -> Feedback
 */
enum class VoiceIntentType {
    SIMPLE_TASK,
    AGENT,
    UNKNOWN,
}

data class VoiceIntent(
    val rawText: String,
    val asrEngine: String,
    val language: String? = null,
    val confidence: Float? = null,
    val normalizedText: String = rawText.trim(),
    val intentType: VoiceIntentType = VoiceIntentType.UNKNOWN,
    val slots: Map<String, String> = emptyMap(),
) {
    val isBlank: Boolean get() = rawText.isBlank()
    val normalized: String get() = normalizedText.ifBlank { rawText.trim() }

    companion object {
        fun fromText(
            rawText: String,
            asrEngine: String,
            language: String? = null,
            confidence: Float? = null,
        ): VoiceIntent = VoiceIntent(
            rawText = rawText.trim(),
            asrEngine = asrEngine,
            language = language,
            confidence = confidence,
            normalizedText = rawText.trim(),
        )
    }
}
