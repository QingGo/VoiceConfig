package com.voiceconfig.app.agent

import com.voiceconfig.app.home.HomeAssistantClient
import com.voiceconfig.app.home.HomeAssistantConfigStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeDevicesTool @Inject constructor(
    private val client: HomeAssistantClient,
    private val configStore: HomeAssistantConfigStore,
) : AgentTool {
    override val name: String = "home_devices"
    override val description: String =
        "列出 Home Assistant 中的设备与状态。参数：{},\"filter\":\"可选，按域名/名称过滤\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "智能家居",
        group = ToolGroup.HOME,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val config = configStore.load()
        if (!config.isConfigured) {
            return ToolResult.failure("未配置 Home Assistant，请在设置中填写 Base URL 和长期访问令牌")
        }
        val result = client.fetchStates(config)
        if (!result.ok) return ToolResult.failure(result.message)
        val filter = args["filter"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val devices = result.devices.filter { device ->
            filter == null ||
                device.entityId.contains(filter, ignoreCase = true) ||
                device.friendlyName.contains(filter, ignoreCase = true) ||
                device.domain.contains(filter, ignoreCase = true)
        }
        val lines = devices.take(100).joinToString("\n") { d ->
            "${d.entityId} [${d.state}] ${d.friendlyName}"
        }
        return ToolResult.success(
            "已读取 ${devices.size} 个设备",
            mapOf(
                "count" to devices.size,
                "devices" to lines,
                "raw" to devices.take(100),
            ),
        )
    }
}

@Singleton
class HomeControlTool @Inject constructor(
    private val client: HomeAssistantClient,
    private val configStore: HomeAssistantConfigStore,
) : AgentTool {
    override val name: String = "home_control"
    override val description: String =
        "控制 Home Assistant 设备。参数：{\"domain\":\"climate|light|cover|media_player|switch\",\"service\":\"set_temperature|turn_on|turn_off|open_cover|close_cover|play_media\",\"entityId\":\"climate.x\",\"data\":{...}}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "智能家居",
        group = ToolGroup.HOME,
        risk = ToolRisk.MEDIUM,
        mutatesUi = false,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val config = configStore.load()
        if (!config.isConfigured) {
            return ToolResult.failure("未配置 Home Assistant，请在设置中填写 Base URL 和长期访问令牌")
        }
        val domain = args["domain"]?.toString()?.trim()?.lowercase()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 domain（如 climate/light/cover/media_player/switch）")
        val service = args["service"]?.toString()?.trim()?.lowercase()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 service（如 set_temperature/turn_on/turn_off）")
        val entityId = args["entityId"]?.toString()?.trim()?.ifBlank { null }
        val data = args["data"] as? Map<*, *>
            ?: emptyMap<String, Any?>()
        val normalizedData = data.entries.associate { it.key.toString() to it.value }
        val result = client.callService(config, domain, service, entityId, normalizedData)
        return if (result.ok) {
            ToolResult.success(
                result.message,
                mapOf("domain" to domain, "service" to service, "entityId" to entityId),
            )
        } else {
            ToolResult.failure(result.message)
        }
    }
}
