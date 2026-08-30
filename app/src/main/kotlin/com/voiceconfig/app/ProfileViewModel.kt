package com.voiceconfig.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.agent.WechatRiskGuard
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStore
import com.voiceconfig.app.agent.FlowScript
import com.voiceconfig.app.agent.FlowScriptStatus
import com.voiceconfig.app.agent.FlowScriptStore
import com.voiceconfig.app.agent.WecomSendMessageTool
import com.voiceconfig.app.home.HomeAssistantDevice
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
    private val homeAssistantFeature: HomeAssistantFeature,
    private val aiDebugLogRepository: AiDebugLogRepository,
    private val wecomSendMessageTool: WecomSendMessageTool,
    private val agentSkillStore: AgentSkillStore,
    private val flowScriptStore: FlowScriptStore,
) : ViewModel() {


    val aiDebugLogs: StateFlow<List<AiDebugLogEntity>> = aiDebugLogRepository.observeRecent(50)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _agentVoiceAutoSend = MutableStateFlow(apiKeyStore.agentVoiceAutoSend)
    val agentVoiceAutoSend: StateFlow<Boolean> = _agentVoiceAutoSend.asStateFlow()

    private val _agentTtsEnabled = MutableStateFlow(apiKeyStore.agentTtsEnabled)
    val agentTtsEnabled: StateFlow<Boolean> = _agentTtsEnabled.asStateFlow()

    private val _wakeWordEnabled = MutableStateFlow(apiKeyStore.wakeWordEnabled)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val _overlayBallEnabled = MutableStateFlow(apiKeyStore.overlayBallEnabled)
    val overlayBallEnabled: StateFlow<Boolean> = _overlayBallEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(apiKeyStore.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    val homeAssistantBaseUrl: StateFlow<String> = homeAssistantFeature.baseUrl
    val homeAssistantToken: StateFlow<String> = homeAssistantFeature.token
    val homeAssistantConfigured: StateFlow<Boolean> = homeAssistantFeature.configured
    val homeAssistantDevices: StateFlow<List<HomeAssistantDevice>?> = homeAssistantFeature.devices
    val homeAssistantTestMessage: StateFlow<String?> = homeAssistantFeature.testMessage
    val homeAssistantControlMessage: StateFlow<String?> = homeAssistantFeature.controlMessage
    private val _deepSeekApiKey = MutableStateFlow(apiKeyStore.deepSeekApiKey)
    val deepSeekApiKey: StateFlow<String> = _deepSeekApiKey.asStateFlow()

    private val _deepSeekModel = MutableStateFlow(apiKeyStore.deepSeekModel)
    val deepSeekModel: StateFlow<String> = _deepSeekModel.asStateFlow()

    private val _deepSeekThinkingEnabled = MutableStateFlow(apiKeyStore.deepSeekThinkingEnabled)
    val deepSeekThinkingEnabled: StateFlow<Boolean> = _deepSeekThinkingEnabled.asStateFlow()

    private val _deepSeekReasoningEffort = MutableStateFlow(apiKeyStore.deepSeekReasoningEffort)
    val deepSeekReasoningEffort: StateFlow<String> = _deepSeekReasoningEffort.asStateFlow()

    private val _agentDeepSeekThinkingEnabled = MutableStateFlow(apiKeyStore.agentDeepSeekThinkingEnabled)
    val agentDeepSeekThinkingEnabled: StateFlow<Boolean> = _agentDeepSeekThinkingEnabled.asStateFlow()

    private val _agentDeepSeekReasoningEffort = MutableStateFlow(apiKeyStore.agentDeepSeekReasoningEffort)
    val agentDeepSeekReasoningEffort: StateFlow<String> = _agentDeepSeekReasoningEffort.asStateFlow()

    private val _agentAutoConfirmSensitiveActions = MutableStateFlow(apiKeyStore.agentAutoConfirmSensitiveActions)
    val agentAutoConfirmSensitiveActions: StateFlow<Boolean> = _agentAutoConfirmSensitiveActions.asStateFlow()

    private val _wechatUiAutomationEnabled = MutableStateFlow(apiKeyStore.wechatUiAutomationEnabled)
    val wechatUiAutomationEnabled: StateFlow<Boolean> = _wechatUiAutomationEnabled.asStateFlow()

    private val _wecomCorpId = MutableStateFlow(apiKeyStore.wecomCorpId)
    val wecomCorpId: StateFlow<String> = _wecomCorpId.asStateFlow()

    private val _wecomAgentId = MutableStateFlow(apiKeyStore.wecomAgentId)
    val wecomAgentId: StateFlow<String> = _wecomAgentId.asStateFlow()

    private val _wecomSecret = MutableStateFlow(apiKeyStore.wecomSecret)
    val wecomSecret: StateFlow<String> = _wecomSecret.asStateFlow()

    private val _wecomTestMessage = MutableStateFlow<String?>(null)
    val wecomTestMessage: StateFlow<String?> = _wecomTestMessage.asStateFlow()

    val agentSkills: StateFlow<List<AgentSkill>> = agentSkillStore.skills
    val flowScripts: StateFlow<List<FlowScript>> = flowScriptStore.flows

    private val _agentAutoVerifyEnabled = MutableStateFlow(apiKeyStore.agentAutoVerifyEnabled)
    val agentAutoVerifyEnabled: StateFlow<Boolean> = _agentAutoVerifyEnabled.asStateFlow()

    private val _agentMaxAutoVerifies = MutableStateFlow(apiKeyStore.agentMaxAutoVerifies)
    val agentMaxAutoVerifies: StateFlow<Int> = _agentMaxAutoVerifies.asStateFlow()
    fun setAgentVoiceAutoSend(enabled: Boolean) {
        apiKeyStore.agentVoiceAutoSend = enabled
        _agentVoiceAutoSend.value = enabled
    }

    fun setAgentTtsEnabled(enabled: Boolean) {
        apiKeyStore.agentTtsEnabled = enabled
        _agentTtsEnabled.value = enabled
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        apiKeyStore.wakeWordEnabled = enabled
        _wakeWordEnabled.value = enabled
    }

    fun setOverlayBallEnabled(enabled: Boolean) {
        apiKeyStore.overlayBallEnabled = enabled
        _overlayBallEnabled.value = enabled
    }

    fun setThemeMode(mode: String) {
        apiKeyStore.themeMode = mode
        _themeMode.value = mode
    }

    fun saveHomeAssistantConfig(baseUrl: String, token: String) {
        homeAssistantFeature.saveConfig(baseUrl, token)
    }

    fun testHomeAssistantConnection() {
        viewModelScope.launch {
            homeAssistantFeature.testConnection()
        }
    }

    fun controlHomeAssistant(entityId: String, domain: String) {
        viewModelScope.launch {
            homeAssistantFeature.control(entityId, domain)
        }
    }

    fun controlHomeAssistantService(
        entityId: String,
        domain: String,
        service: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        viewModelScope.launch {
            homeAssistantFeature.controlService(entityId, domain, service, data)
        }
    }

    fun setDeepSeekApiKey(value: String) {
        val trimmed = value.trim()
        apiKeyStore.deepSeekApiKey = trimmed
        _deepSeekApiKey.value = trimmed
    }

    fun setDeepSeekModel(value: String) {
        val trimmed = value.trim()
        apiKeyStore.deepSeekModel = trimmed
        _deepSeekModel.value = trimmed
    }

    fun setDeepSeekThinkingEnabled(enabled: Boolean) {
        apiKeyStore.deepSeekThinkingEnabled = enabled
        _deepSeekThinkingEnabled.value = enabled
    }

    fun setDeepSeekReasoningEffort(effort: String) {
        apiKeyStore.deepSeekReasoningEffort = effort
        _deepSeekReasoningEffort.value = effort
    }

    fun setAgentDeepSeekThinkingEnabled(enabled: Boolean) {
        apiKeyStore.agentDeepSeekThinkingEnabled = enabled
        _agentDeepSeekThinkingEnabled.value = enabled
    }

    fun setAgentDeepSeekReasoningEffort(effort: String) {
        apiKeyStore.agentDeepSeekReasoningEffort = effort
        _agentDeepSeekReasoningEffort.value = effort
    }

    fun setAgentAutoConfirmSensitiveActions(enabled: Boolean) {
        apiKeyStore.agentAutoConfirmSensitiveActions = enabled
        _agentAutoConfirmSensitiveActions.value = enabled
    }

    fun setWechatUiAutomationEnabled(enabled: Boolean) {
        apiKeyStore.wechatUiAutomationEnabled = enabled
        WechatRiskGuard.setAutomationAllowed(enabled)
        _wechatUiAutomationEnabled.value = enabled
    }

    fun setWecomCorpId(value: String) {
        apiKeyStore.wecomCorpId = value.trim()
        _wecomCorpId.value = apiKeyStore.wecomCorpId
    }

    fun setWecomAgentId(value: String) {
        apiKeyStore.wecomAgentId = value.trim()
        _wecomAgentId.value = apiKeyStore.wecomAgentId
    }

    fun setWecomSecret(value: String) {
        apiKeyStore.wecomSecret = value.trim()
        _wecomSecret.value = apiKeyStore.wecomSecret
    }

    fun testWecomConnection() {
        viewModelScope.launch {
            val result = wecomSendMessageTool.verifyCredentials()
            _wecomTestMessage.value = result.message
        }
    }

    fun clearWecomTestMessage() {
        _wecomTestMessage.value = null
    }

    fun setAgentAutoVerifyEnabled(enabled: Boolean) {
        apiKeyStore.agentAutoVerifyEnabled = enabled
        _agentAutoVerifyEnabled.value = enabled
    }

    fun approveFlowScript(id: String) {
        flowScriptStore.approve(id)
    }

    fun rejectFlowScript(id: String) {
        flowScriptStore.reject(id)
    }

    fun setFlowScriptEnabled(id: String, enabled: Boolean) {
        flowScriptStore.setEnabled(id, enabled)
    }

    fun deleteFlowScript(id: String) {
        flowScriptStore.delete(id)
    }

    fun importFlowScriptJson(json: String): Boolean =
        flowScriptStore.importJson(json) != null

    fun setAgentMaxAutoVerifies(value: Int) {
        apiKeyStore.agentMaxAutoVerifies = value
        _agentMaxAutoVerifies.value = value
    }

    fun buildAiDebugLogReport(logs: List<AiDebugLogEntity>): String = buildString {
        appendLine("## VoiceConfig AI 错误日志")
        appendLine()
        if (logs.isEmpty()) {
            appendLine("（暂无 AI 错误日志）")
            return@buildString
        }
        logs.forEachIndexed { index, log ->
            appendLine("### ${index + 1}. ${log.createdAtEpochMillis.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() }}")
            appendLine("- 输入：${log.input}")
            appendLine("- 模型：${log.model}")
            appendLine("- 思考模式：${if (log.thinkingEnabled) "开启（${log.reasoningEffort}）" else "关闭"}")
            appendLine("- 解析错误：${log.parseError ?: "无"}")
            log.rawResponse?.let {
                appendLine("- 原始返回：")
                appendLine("```")
                appendLine(it)
                appendLine("```")
            }
            appendLine()
        }
    }

}
