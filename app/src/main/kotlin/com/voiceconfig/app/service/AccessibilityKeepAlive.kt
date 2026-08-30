package com.voiceconfig.app.service

import android.util.Log
import com.voiceconfig.app.agent.ShizukuCommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动保持“言控”无障碍服务连接。
 *
 * 绝大多数场景下无障碍服务一旦被系统启用会持续运行。但在应用被 force-stop、
 * 系统回收或重新安装后，服务可能不再在 enabled_accessibility_services 中。
 * 这里在 App 启动/前台服务存活期间，利用 Shizuku 自动重新写回系统安全设置，
 * 避免每次中文输入都要手动去系统设置开启。
 */

enum class AccessibilityKeepAliveState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CRASHED,
}

@Singleton
class AccessibilityKeepAlive @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) {

    private val _state = MutableStateFlow(AccessibilityKeepAliveState.DISCONNECTED)
    val state: StateFlow<AccessibilityKeepAliveState> = _state.asStateFlow()

    @Volatile
    var consecutiveFailures = 0
        private set

    @Volatile
    var lastSuccessAtMs: Long = 0L
        private set
    @Volatile
    var lastAttemptAtMs: Long = 0L
        private set
    @Volatile
    var lastError: String? = null
        private set
    @Volatile
    var refreshCount: Long = 0L
        private set


    /** 便于单测注入的连接探针；生产环境默认检查真实服务实例。 */
    @Volatile
    var instanceProbe: () -> Boolean = { AgentAccessibilityService.instance != null }

    fun currentState(): AccessibilityKeepAliveState = _state.value

    fun lastFailureCount(): Int = consecutiveFailures

    fun markConnected() {
        consecutiveFailures = 0
        if (_state.value != AccessibilityKeepAliveState.CONNECTED) {
            _state.value = AccessibilityKeepAliveState.CONNECTED
            logI("state -> CONNECTED")
        }
    }

    fun markDisconnected(reason: String = "") {
        if (_state.value != AccessibilityKeepAliveState.DISCONNECTED) {
            _state.value = AccessibilityKeepAliveState.DISCONNECTED
            logW("state -> DISCONNECTED $reason")
        }
    }

    fun markCrashed(reason: String) {
        _state.value = AccessibilityKeepAliveState.CRASHED
        logE("state -> CRASHED: $reason")
    }

    /** 非挂起式刷新：检测实例、尝试写回系统设置，并更新状态机。 */
    fun refresh(): AccessibilityKeepAliveState {
        refreshCount++
        lastAttemptAtMs = System.currentTimeMillis()
        if (instanceProbe()) {
            lastSuccessAtMs = System.currentTimeMillis()
            lastError = null
            markConnected()
            return _state.value
        }
        if (!shizuku.isAvailable()) {
            lastError = "Shizuku 不可用，无法写回无障碍开关"
            return if (_state.value == AccessibilityKeepAliveState.CRASHED) {
                _state.value
            } else {
                _state.value = AccessibilityKeepAliveState.DISCONNECTED
                _state.value
            }
        }
        _state.value = AccessibilityKeepAliveState.CONNECTING
        val ok = ensureEnabled()
        return if (ok) {
            if (instanceProbe()) {
                lastSuccessAtMs = System.currentTimeMillis()
                lastError = null
                markConnected()
            } else {
                _state.value = AccessibilityKeepAliveState.CONNECTING
                lastError = "已写回系统设置，等待无障碍服务实例连接"
            }
            _state.value
        } else {
            consecutiveFailures++
            lastError = "write failed (${consecutiveFailures})"
            if (consecutiveFailures >= CRASH_THRESHOLD) {
                markCrashed("${consecutiveFailures} consecutive write failures")
            } else {
                markDisconnected("write failed (${consecutiveFailures})")
            }
            _state.value
        }
    }

    /**
     * 确保无障碍服务已被系统启用并尽量让当前进程连接。
     * 返回 true 表示命令执行成功或服务已连接；不代表一定立即完成连接。
     */
    fun ensureEnabled(): Boolean {
        if (instanceProbe()) {
            markConnected()
            logD("ensureEnabled: already connected")
            return true
        }
        val shizukuAvailable = shizuku.isAvailable()
        logD("ensureEnabled: shizukuAvailable=$shizukuAvailable")
        if (!shizukuAvailable) {
            _state.value = AccessibilityKeepAliveState.DISCONNECTED
            return false
        }
        _state.value = AccessibilityKeepAliveState.CONNECTING

        val currentResult = shizuku.execute("settings", "get", "secure", "enabled_accessibility_services")
        logD("ensureEnabled: current=${currentResult.stdout.trim()} ok=${currentResult.ok} err=${currentResult.stderr.trim()}")
        val current = currentResult.stdout.trim().removeSurrounding("\"").ifBlank { "" }
        val needsAdd = !current.contains(COMPONENT)

        val updated = if (needsAdd) {
            val prefix = current
                .split(':')
                .filter { it.isNotBlank() && it != "null" }
                .joinToString(":")
            if (prefix.isBlank()) COMPONENT else "$prefix:$COMPONENT"
        } else {
            current
        }

        val putResult = if (needsAdd) {
            shizuku.execute("settings", "put", "secure", "enabled_accessibility_services", updated)
        } else {
            // 即使已包含，也再确认 accessibility_enabled=1，防止系统开关被重置。
            shizuku.execute("settings", "put", "secure", "accessibility_enabled", "1")
        }

        if (putResult.ok) {
            // 部分设备需要把总开关也置 1。
            val enableResult = shizuku.execute("settings", "put", "secure", "accessibility_enabled", "1")
            logI("AccessibilityKeepAlive: enabled service written ok (needsAdd=$needsAdd, enableOk=${enableResult.ok}, enableErr=${enableResult.stderr.trim()})")
            return true
        }

        logW("AccessibilityKeepAlive: failed to write secure settings: ${putResult.stderr.trim()} ok=${putResult.ok}")
        return false
    }

    private fun logI(message: String) = runCatching { Log.i(TAG, message) }
    private fun logW(message: String) = runCatching { Log.w(TAG, message) }
    private fun logD(message: String) = runCatching { Log.d(TAG, message) }
    private fun logE(message: String) = runCatching { Log.e(TAG, message) }

    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        const val COMPONENT = "com.voiceconfig.app/com.voiceconfig.app.service.AgentAccessibilityService"
        const val CRASH_THRESHOLD = 3
    }
}
