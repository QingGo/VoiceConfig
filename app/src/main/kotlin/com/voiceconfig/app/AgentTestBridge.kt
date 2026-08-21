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
