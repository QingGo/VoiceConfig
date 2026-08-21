package com.voiceconfig.app.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_config", Context.MODE_PRIVATE)

    var deepSeekApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var deepSeekModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, null)
            return normalizeModel(stored)
        }
        set(value) {
            prefs.edit().putString(KEY_MODEL, normalizeModel(value)).apply()
        }

    var deepSeekThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_THINKING_ENABLED, value).apply()
        }

    var deepSeekReasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT)
            ?: DEFAULT_REASONING_EFFORT
        set(value) {
            prefs.edit().putString(KEY_REASONING_EFFORT, value).apply()
        }

    var agentDeepSeekThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_THINKING_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_THINKING_ENABLED, value).apply()
        }

    var agentDeepSeekReasoningEffort: String
        get() = prefs.getString(KEY_AGENT_REASONING_EFFORT, DEFAULT_AGENT_REASONING_EFFORT)
            ?: DEFAULT_AGENT_REASONING_EFFORT
        set(value) {
            prefs.edit().putString(KEY_AGENT_REASONING_EFFORT, value).apply()
        }

    var agentAutoConfirmSensitiveActions: Boolean
        get() = prefs.getBoolean(KEY_AGENT_AUTO_CONFIRM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_AUTO_CONFIRM, value).apply()
        }

    companion object {
        private const val KEY_API_KEY = "deepseek_api_key"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_THINKING_ENABLED = "deepseek_thinking_enabled"
        private const val KEY_REASONING_EFFORT = "deepseek_reasoning_effort"
        private const val KEY_AGENT_THINKING_ENABLED = "agent_deepseek_thinking_enabled"
        private const val KEY_AGENT_REASONING_EFFORT = "agent_deepseek_reasoning_effort"
        private const val KEY_AGENT_AUTO_CONFIRM = "agent_auto_confirm_sensitive_actions"
        const val DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"

        fun normalizeModel(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            return if (trimmed.isBlank() || trimmed == LEGACY_MODEL || trimmed == LEGACY_FLASH_MODEL) {
                DEFAULT_MODEL
            } else {
                trimmed
            }
        }
        const val DEFAULT_REASONING_EFFORT = "low"
        const val DEFAULT_AGENT_REASONING_EFFORT = "max"
        private const val LEGACY_MODEL = "deepseek-chat"
        private const val LEGACY_FLASH_MODEL = "deepseek-v4-flash"
    }
}
