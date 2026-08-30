package com.voiceconfig.app.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_config", Context.MODE_PRIVATE)

    var deepSeekApiKey: String
        get() = readApiKey()
        set(value) {
            writeApiKey(value.trim())
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

    var wechatUiAutomationEnabled: Boolean
        get() = prefs.getBoolean(KEY_WECHAT_UI_AUTOMATION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WECHAT_UI_AUTOMATION, value).apply()
        }

    var agentMockLlmEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_MOCK_LLM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_MOCK_LLM, value).apply()
        }

    var wecomCorpId: String
        get() = prefs.getString(KEY_WECOM_CORP_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WECOM_CORP_ID, value).apply()
        }

    var wecomAgentId: String
        get() = prefs.getString(KEY_WECOM_AGENT_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WECOM_AGENT_ID, value).apply()
        }

    var wecomSecret: String
        get() = prefs.getString(KEY_WECOM_SECRET, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WECOM_SECRET, value).apply()
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

    var agentTtsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_TTS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_TTS_ENABLED, value).apply()
        }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()
        }

    var overlayBallEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_BALL_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_BALL_ENABLED, value).apply()
        }

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
        }

    private fun readApiKey(): String {
        val encrypted = prefs.getString(KEY_API_KEY_ENCRYPTED, null)
        if (!encrypted.isNullOrBlank()) {
            decrypt(encrypted)?.let { return it }
        }
        // 迁移旧的明文 Key；读取后立即加密并删除明文。
        val legacy = prefs.getString(KEY_API_KEY, "").orEmpty()
        if (legacy.isNotBlank()) {
            encrypt(legacy)?.let { cipherText ->
                prefs.edit()
                    .putString(KEY_API_KEY_ENCRYPTED, cipherText)
                    .remove(KEY_API_KEY)
                    .apply()
            }
        }
        return legacy
    }

    private fun writeApiKey(value: String) {
        val cipherText = encrypt(value)
        if (cipherText != null) {
            prefs.edit()
                .putString(KEY_API_KEY_ENCRYPTED, cipherText)
                .remove(KEY_API_KEY)
                .apply()
        } else {
            // Keystore 异常时保留原行为，避免用户完全丢失 Key。
            prefs.edit()
                .remove(KEY_API_KEY_ENCRYPTED)
                .putString(KEY_API_KEY, value)
                .apply()
        }
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generator.generateKey()
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(data: String): String? = runCatching {
        val parts = data.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "voiceconfig_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_API_KEY_ENCRYPTED = "deepseek_api_key_encrypted_v1"
        private const val KEY_API_KEY = "deepseek_api_key"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_THINKING_ENABLED = "deepseek_thinking_enabled"
        private const val KEY_REASONING_EFFORT = "deepseek_reasoning_effort"
        private const val KEY_AGENT_THINKING_ENABLED = "agent_deepseek_thinking_enabled"
        private const val KEY_AGENT_REASONING_EFFORT = "agent_deepseek_reasoning_effort"
        private const val KEY_AGENT_AUTO_CONFIRM = "agent_auto_confirm_sensitive_actions"
        private const val KEY_WECHAT_UI_AUTOMATION = "wechat_ui_automation_enabled"
        private const val KEY_AGENT_MOCK_LLM = "agent_mock_llm_enabled"
        private const val KEY_WECOM_CORP_ID = "wecom_corp_id"
        private const val KEY_WECOM_AGENT_ID = "wecom_agent_id"
        private const val KEY_WECOM_SECRET = "wecom_secret"
        private const val KEY_AGENT_AUTO_VERIFY_ENABLED = "agent_auto_verify_enabled"
        private const val KEY_AGENT_MAX_AUTO_VERIFY = "agent_max_auto_verifies"
        private const val KEY_AGENT_IMAGE_DETAIL_LOW = "agent_image_detail_low"
        private const val KEY_AGENT_VOICE_AUTO_SEND = "agent_voice_auto_send"
        private const val KEY_AGENT_TTS_ENABLED = "agent_tts_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_OVERLAY_BALL_ENABLED = "overlay_ball_enabled"
        private const val KEY_THEME_MODE = "theme_mode"
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
