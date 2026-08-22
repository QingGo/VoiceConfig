package com.voiceconfig.app.ai

interface AsrEngine {
    fun isModelAvailable(): Boolean

    fun warmUp()

    fun recognize(
        maxDurationMs: Long = 30_000,
        onPartialResult: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    )

    fun cancel()

    fun recognizeFile(
        wavPath: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        language: String? = null,
    )
}
