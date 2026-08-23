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

    var agentAutoVerifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_AUTO_VERIFY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_AUTO_VERIFY_ENABLED, value).apply()
        }

    var agentImageDetailLow: Boolean
        get() = prefs.getBoolean(KEY_AGENT_IMAGE_DETAIL_LOW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_IMAGE_DETAIL_LOW, value).apply()
        }

    var agentMaxAutoVerifies: Int
        get() = prefs.getInt(KEY_AGENT_MAX_AUTO_VERIFY, DEFAULT_MAX_AUTO_VERIFY)
        set(value) {
            prefs.edit().putInt(KEY_AGENT_MAX_AUTO_VERIFY, value.coerceIn(0, 20)).apply()
        }

    var agentVoiceAutoSend: Boolean
        get() = prefs.getBoolean(KEY_AGENT_VOICE_AUTO_SEND, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_VOICE_AUTO_SEND, value).apply()
        }

    companion object {
        private const val KEY_API_KEY = "deepseek_api_key"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_THINKING_ENABLED = "deepseek_thinking_enabled"
        private const val KEY_REASONING_EFFORT = "deepseek_reasoning_effort"
        private const val KEY_AGENT_THINKING_ENABLED = "agent_deepseek_thinking_enabled"
        private const val KEY_AGENT_REASONING_EFFORT = "agent_deepseek_reasoning_effort"
        private const val KEY_AGENT_AUTO_CONFIRM = "agent_auto_confirm_sensitive_actions"
        private const val KEY_AGENT_AUTO_VERIFY_ENABLED = "agent_auto_verify_enabled"
        private const val KEY_AGENT_MAX_AUTO_VERIFY = "agent_max_auto_verifies"
        private const val KEY_AGENT_IMAGE_DETAIL_LOW = "agent_image_detail_low"
        private const val KEY_AGENT_VOICE_AUTO_SEND = "agent_voice_auto_send"
        const val DEFAULT_MAX_AUTO_VERIFY = 2
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
        const val DEFAULT_AGENT_REASONING_EFFORT = "medium"
        private const val LEGACY_MODEL = "deepseek-chat"
        private const val LEGACY_FLASH_MODEL = "deepseek-v4-flash"
    }
}
