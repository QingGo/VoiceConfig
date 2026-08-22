package com.voiceconfig.app.service

import android.util.Log
import com.voiceconfig.app.agent.ShizukuCommandRunner
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
@Singleton
class AccessibilityKeepAlive @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) {

    /**
     * 确保无障碍服务已被系统启用并尽量让当前进程连接。
     * 返回 true 表示命令执行成功或服务已连接；不代表一定立即完成连接。
     */
    fun ensureEnabled(): Boolean {
        if (AgentAccessibilityService.instance != null) {
            Log.d(TAG, "ensureEnabled: already connected")
            return true
        }
        val shizukuAvailable = shizuku.isAvailable()
        Log.d(TAG, "ensureEnabled: shizukuAvailable=$shizukuAvailable")
        if (!shizukuAvailable) return false

        val currentResult = shizuku.execute("settings", "get", "secure", "enabled_accessibility_services")
        Log.d(TAG, "ensureEnabled: current=${currentResult.stdout.trim()} ok=${currentResult.ok} err=${currentResult.stderr.trim()}")
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
            Log.i(TAG, "AccessibilityKeepAlive: enabled service written ok (needsAdd=$needsAdd, enableOk=${enableResult.ok}, enableErr=${enableResult.stderr.trim()})")
            return true
        }

        Log.w(TAG, "AccessibilityKeepAlive: failed to write secure settings: ${putResult.stderr.trim()} ok=${putResult.ok}")
        return false
    }

    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        const val COMPONENT = "com.voiceconfig.app/com.voiceconfig.app.service.AgentAccessibilityService"
    }
}
