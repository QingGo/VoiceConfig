package com.voiceconfig.app

import com.voiceconfig.app.home.HomeAssistantClient
import com.voiceconfig.app.home.HomeAssistantConfig
import com.voiceconfig.app.home.HomeAssistantConfigStore
import com.voiceconfig.app.home.HomeAssistantDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeAssistantFeature @Inject constructor(
    private val configStore: HomeAssistantConfigStore,
    private val client: HomeAssistantClient,
) {
    private val _baseUrl = MutableStateFlow(configStore.load().baseUrl)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow(configStore.load().token)
    val token: StateFlow<String> = _token.asStateFlow()

    private val _configured = MutableStateFlow(configStore.load().isConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _devices = MutableStateFlow<List<HomeAssistantDevice>?>(null)
    val devices: StateFlow<List<HomeAssistantDevice>?> = _devices.asStateFlow()

    private val _testMessage = MutableStateFlow<String?>(null)
    val testMessage: StateFlow<String?> = _testMessage.asStateFlow()

    private val _controlMessage = MutableStateFlow<String?>(null)
    val controlMessage: StateFlow<String?> = _controlMessage.asStateFlow()

    fun saveConfig(baseUrl: String, token: String) {
        val config = HomeAssistantConfig(baseUrl = baseUrl, token = token)
        configStore.save(config)
        _baseUrl.value = config.baseUrl
        _token.value = config.token
        _configured.value = config.isConfigured
    }

    suspend fun testConnection() {
        val config = configStore.load()
        val result = client.fetchStates(config)
        if (result.ok) {
            _devices.value = result.devices
            _testMessage.value = "已连接，读取到 ${result.devices.size} 个设备"
        } else {
            _devices.value = emptyList()
            _testMessage.value = result.message
        }
    }

    suspend fun control(entityId: String, domain: String) {
        controlService(entityId, domain, "toggle")
    }

    suspend fun controlService(
        entityId: String,
        domain: String,
        service: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val config = configStore.load()
        if (!config.isConfigured) {
            _controlMessage.value = "请先配置 Home Assistant"
            return
        }
        val supported = setOf("light", "switch", "fan", "media_player", "input_boolean")
        if (domain !in supported && service == "toggle") {
            _controlMessage.value = "该设备类型暂不支持页面上直接开关，请使用智能助手控制"
            return
        }
        val result = client.callService(
            config = config,
            domain = domain,
            service = service,
            entityId = entityId,
            data = data,
        )
        _controlMessage.value = if (result.ok) "已发送控制指令：$entityId" else result.message
    }
}
