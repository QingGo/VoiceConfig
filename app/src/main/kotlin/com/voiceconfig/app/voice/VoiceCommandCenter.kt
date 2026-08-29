package com.voiceconfig.app.voice

import android.util.Log
import com.voiceconfig.app.ai.ApiKeyStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局语音命令总线。
 *
 * 所有来源（App 内语音、全局悬浮球、唤醒词、调试广播）都通过 [submit] 进入
 * 同一条管道，再由明确的 ViewModel 订阅处理，避免 MainViewModel 中的可变桥接
 * 和“命令因注入时序丢失”的问题。
 *
 * - SharedFlow：App 内组件订阅实时命令。
 * - replay：保留最近命令，晚创建的订阅者也能拿到历史未确认命令。
 * - ack：处理完成后确认，防止 Activity/ViewModel 重建导致重复执行。
 * - 去重：同一语音会话的重复 final 结果只在窗口内进入一次。
 * - 超时：命令有有效期，过期后订阅者应跳过并 ack。
 */
@Singleton
class VoiceCommandCenter @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
) {
    private val _commands = MutableSharedFlow<GlobalVoiceCommand>(
        replay = REPLAY_SIZE,
        extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<GlobalVoiceCommand> = _commands.asSharedFlow()

    private val recentByDedupKey = ConcurrentHashMap<String, RecentCommand>()
    private val ackedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 所有语音命令的唯一入口。
     *
     * @param text 识别/输入文本
     * @param source 来源
     * @param target 期望路由；null 表示按“是否配置云模型”自动路由到 Agent/Automation
     * @param autoSend null 表示由中心按来源/目标自动决定
     */
    fun submit(
        text: String,
        source: VoiceCommandSource,
        target: VoiceCommandTarget? = null,
        autoSend: Boolean? = null,
        confirmationToken: String? = null,
        dedupKey: String? = null,
        autoParse: Boolean = true,
    ): GlobalVoiceCommand {
        val normalized = text.trim()
        val now = System.currentTimeMillis()
        val resolvedTarget = target ?: if (apiKeyStore.deepSeekApiKey.isNotBlank()) {
            VoiceCommandTarget.AGENT
        } else {
            VoiceCommandTarget.AUTOMATION
        }
        val resolvedAutoSend = when {
            autoSend != null -> autoSend
            target == VoiceCommandTarget.AGENT -> apiKeyStore.agentVoiceAutoSend
            else -> true
        }

        val key = dedupKey
        if (key != null) {
            recentByDedupKey[key]?.takeIf { !it.isExpired(now) }?.let {
                return it.command
            }
        }

        val command = GlobalVoiceCommand(
            commandId = UUID.randomUUID().toString(),
            text = normalized,
            source = source,
            timestamp = now,
            confirmationToken = confirmationToken,
            target = resolvedTarget,
            autoSend = resolvedAutoSend,
            dedupKey = key,
            autoParse = autoParse,
            expiresAt = now + COMMAND_TTL_MS,
        )
        if (key != null) {
            recentByDedupKey[key] = RecentCommand(command, now + DEDUP_WINDOW_MS)
        }
        Log.d(TAG, "submit id=${command.commandId} source=${command.source.name} target=${command.target.name} autoSend=${command.autoSend} text=${command.text}")
        // 保留在 replay 中，晚创建的订阅者也能收到；ack 后不会再执行。
        _commands.tryEmit(command)
        return command
    }

    fun ack(commandId: String) {
        ackedIds.add(commandId)
        Log.d(TAG, "ack id=$commandId")
    }

    fun isAcked(commandId: String): Boolean = commandId in ackedIds

    fun isExpired(command: GlobalVoiceCommand): Boolean = command.isExpired(System.currentTimeMillis())

    private data class RecentCommand(
        val command: GlobalVoiceCommand,
        val validUntil: Long,
    ) {
        fun isExpired(now: Long): Boolean = now >= validUntil
    }

    companion object {
        private const val TAG = "VoiceCommandCenter"
        private const val REPLAY_SIZE = 64
        private const val EXTRA_BUFFER_CAPACITY = 64
        private const val COMMAND_TTL_MS = 5 * 60 * 1000L
        private const val DEDUP_WINDOW_MS = 3_000L

        fun defaultCommandTtlMillis(): Long = COMMAND_TTL_MS
    }
}

enum class VoiceCommandSource {
    /** App 内语音按钮产生的识别结果。 */
    APP_INTERNAL,

    /** 系统级悬浮球聆听、确认后产生。 */
    GLOBAL_BALL,

    /** 本地/系统唤醒词触发后产生。 */
    WAKE_WORD,

    /** 调试广播/调试面板注入。 */
    DEBUG_BROADCAST,
}

enum class VoiceCommandTarget {
    AGENT,
    AUTOMATION,
}

data class GlobalVoiceCommand(
    val commandId: String,
    val text: String,
    val source: VoiceCommandSource,
    val timestamp: Long,
    val confirmationToken: String? = null,
    val target: VoiceCommandTarget,
    val autoSend: Boolean = true,
    val dedupKey: String? = null,
    val autoParse: Boolean = true,
    val expiresAt: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt
}

/** 兼容命名，与 [VoiceCommandCenter] 指向同一个单例总线。 */
typealias GlobalVoiceCommandBus = VoiceCommandCenter
