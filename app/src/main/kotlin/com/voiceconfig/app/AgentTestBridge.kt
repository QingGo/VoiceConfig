package com.voiceconfig.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仅供调试/自动化测试使用的桥接入口。
 *
 * 通过 adb broadcast 可以注入中文指令并触发 Agent 执行：
 * adb shell am broadcast -a com.voiceconfig.app.DEBUG_AGENT_INPUT \
 *   --es text "打开瑞幸咖啡点一杯冰美式" --ez send true --ez newSession true
 */
object AgentTestBridge {
    /**
     * 模拟“本地 ASR 识别完成”后的首页闭环：
     * 填入创建面板并（默认）自动解析/创建。
     */
    data class HomeSpeech(
        val text: String,
        val parse: Boolean = true,
    )

    private val _homeSpeech = MutableStateFlow<HomeSpeech?>(null)
    val homeSpeech: StateFlow<HomeSpeech?> = _homeSpeech.asStateFlow()

    fun submitHomeSpeech(speech: HomeSpeech) {
        _homeSpeech.value = speech
    }

    fun clearHomeSpeech() {
        _homeSpeech.value = null
    }

    data class Command(
        val text: String,
        val send: Boolean = false,
        val newSession: Boolean = false,
    )

    private val _command = MutableStateFlow<Command?>(null)
    val command: StateFlow<Command?> = _command.asStateFlow()

    fun submit(command: Command) {
        _command.value = command
    }

    fun clear() {
        _command.value = null
    }
}
