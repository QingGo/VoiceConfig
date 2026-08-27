package com.voiceconfig.app.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class HomeAssistantConfig(
    val baseUrl: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.startsWith("http") && token.isNotBlank()
}

@Singleton
class HomeAssistantConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("home_assistant", Context.MODE_PRIVATE)

    fun load(): HomeAssistantConfig = HomeAssistantConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
    )

    fun save(config: HomeAssistantConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "ha_base_url"
        private const val KEY_TOKEN = "ha_token"
    }
}
