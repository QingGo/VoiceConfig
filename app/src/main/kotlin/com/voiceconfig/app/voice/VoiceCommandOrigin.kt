package com.voiceconfig.app.voice

/**
 * 全局语音命令的执行来源元数据。
 *
 * AgentTrace 会在 run_start 后写入这些字段，使任何一次 Agent 执行都能
 * 从“语音命令”完整回放到最终工具执行。
 */
data class VoiceCommandOrigin(
    val commandId: String? = null,
    val source: String? = null,
    val confirmationToken: String? = null,
    val timestamp: Long? = null,
) {
    companion object {
        fun from(command: GlobalVoiceCommand): VoiceCommandOrigin = VoiceCommandOrigin(
            commandId = command.commandId,
            source = command.source.name,
            confirmationToken = command.confirmationToken,
            timestamp = command.timestamp,
        )
    }
}
