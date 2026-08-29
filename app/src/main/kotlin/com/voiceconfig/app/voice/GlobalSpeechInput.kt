package com.voiceconfig.app.voice

/**
 * 全局语音实际的识别输入抽象。
 *
 * Android Service 不再直接持有 SpeechRecognizer / LocalAsrManager，
 * 而是由 GlobalVoiceSession 通过该接口驱动识别，便于替换和测试。
 */
interface GlobalSpeechInput {
    data class Callbacks(
        val onResult: (String) -> Unit,
        val onPartial: (String) -> Unit,
        val onError: (String) -> Unit,
    )

    /** 开始识别；返回是否成功启动。 */
    fun start(callbacks: Callbacks): Boolean

    fun stop()
}

interface GlobalSpeechInputFactory {
    fun create(): GlobalSpeechInput
}
