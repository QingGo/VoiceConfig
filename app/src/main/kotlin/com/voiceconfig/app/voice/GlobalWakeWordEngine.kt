package com.voiceconfig.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.ai.WakeWordDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 唤醒词引擎。
 *
 * 根据 [GlobalVoiceSession] 的状态自动启停：
 * - 聆听/确认时暂停，避免与全局识别抢麦克风；
 * - 回到 IDLE/HIDDEN 时恢复（除非屏幕关闭被 pause）。
 */
@Singleton
class GlobalWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val wakeWordDetector: WakeWordDetector,
    private val session: GlobalVoiceSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var paused = false

    init {
        scope.launch {
            session.state.collect { state ->
                if (paused) {
                    stop()
                    return@collect
                }
                when (state.phase) {
                    GlobalVoicePhase.HIDDEN,
                    GlobalVoicePhase.IDLE,
                    -> startIfEnabled()
                    GlobalVoicePhase.LISTENING,
                    GlobalVoicePhase.CONFIRMING,
                    -> stop()
                }
            }
        }
    }

    fun pause() {
        paused = true
        stop()
    }

    fun resume() {
        paused = false
        startIfEnabled()
    }

    fun startIfEnabled() {
        if (paused) {
            stop()
            return
        }
        if (!apiKeyStore.wakeWordEnabled) {
            stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stop()
            return
        }
        wakeWordDetector.start(
            listener = object : WakeWordDetector.Listener {
                override fun onWakeWord(text: String) {
                    session.startListening(VoiceCommandSource.WAKE_WORD)
                }

                override fun onError(error: Int) {
                    // WakeWordDetector 内部会自动重启。
                }
            },
            keywords = listOf("言控", "语音助手", "你好言控"),
        )
    }

    fun stop() {
        wakeWordDetector.stop()
    }
}
