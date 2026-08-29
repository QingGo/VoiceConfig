package com.voiceconfig.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.model.Template
import com.voiceconfig.core.model.TaskDraft
import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.core.model.TriggerCondition
import com.voiceconfig.core.model.TriggerAction
import com.voiceconfig.core.model.VerifySpec
import com.voiceconfig.core.model.FallbackSpec
import com.voiceconfig.core.nlp.AppAliasResolver
import com.voiceconfig.core.nlp.NaturalLanguageParser
import com.voiceconfig.core.nlp.ScheduleModificationParser
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.executor.ExecutionEngine
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.executor.ExecutionResult
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.app.agent.AgentMessage
import com.voiceconfig.app.agent.AgentStreamEvent
import com.voiceconfig.app.agent.AgentRunLedger
import com.voiceconfig.app.agent.AgentRunRecord
import com.voiceconfig.app.agent.AgentCapabilityInspector
import com.voiceconfig.app.agent.AgentPreflight
import com.voiceconfig.app.agent.AgentRunState
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStatus
import com.voiceconfig.app.agent.AgentSkillStore
import com.voiceconfig.app.agent.TaskPlan
import com.voiceconfig.app.agent.TaskPlanStore
import com.voiceconfig.app.agent.AgentVerificationPolicy
import com.voiceconfig.app.agent.AgentStepStatus
import com.voiceconfig.app.agent.AgentStepUi
import com.voiceconfig.app.agent.AgentTrace
import com.voiceconfig.app.agent.SensitiveActionRequest
import com.voiceconfig.app.agent.VoiceSessionManager
import com.voiceconfig.app.agent.VoiceSession
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.ai.DeepSeekNlpParser
import com.voiceconfig.app.ai.VoiceIntent
import com.voiceconfig.app.ai.VoiceIntentType
import com.voiceconfig.app.ai.TtsSpeaker
import com.voiceconfig.app.HomeAssistantFeature
import com.voiceconfig.app.ui.CapabilityStatus
import com.voiceconfig.app.ui.CapabilityStatusMapper
import com.voiceconfig.app.home.HomeAssistantClient
import com.voiceconfig.app.home.HomeAssistantConfigStore
import com.voiceconfig.app.home.HomeAssistantDevice
import com.voiceconfig.app.scheduler.TriggerRuleScheduler
import com.voiceconfig.app.di.UserAliasRegistry
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.AgentStepEntity
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.data.local.repository.AgentHistoryRepository
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import com.voiceconfig.data.local.repository.ExecutionLogRepository
import com.voiceconfig.app.RemoteNodeFeature
import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import com.voiceconfig.data.local.repository.RemoteProjectRepository
import com.voiceconfig.data.local.repository.RemoteProjectRecord
import com.voiceconfig.app.ShoppingFeature
import com.voiceconfig.data.local.repository.ShoppingItemRecord
import com.voiceconfig.data.local.repository.TaskRepository
import com.voiceconfig.app.remote.RemoteCommandClient
import com.voiceconfig.app.remote.SshBootstrapClient
import com.voiceconfig.app.remote.SshAuditStore
import com.voiceconfig.app.remote.SshBootstrapResult
import com.voiceconfig.app.remote.SshClient
import com.voiceconfig.app.remote.SshCredentialStore
import com.voiceconfig.app.remote.SshFileResult
import com.voiceconfig.app.remote.SshHostKeyStore
import com.voiceconfig.app.remote.SshKeyManager
import com.voiceconfig.app.remote.SshKeyStore
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.SshPendingTrust
import com.voiceconfig.app.remote.SshRemoteFile
import com.voiceconfig.app.remote.SshShellHandle
import com.voiceconfig.app.remote.StoredSshCredential
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshExecResult
import com.voiceconfig.app.remote.RemoteCommandResult
import com.voiceconfig.app.TemplateFeature
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import org.json.JSONArray
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val remoteNodeFeature: RemoteNodeFeature,
    private val shoppingFeature: ShoppingFeature,
    private val apiKeyStore: ApiKeyStore,
    private val homeAssistantFeature: HomeAssistantFeature,
    private val agentCapabilityInspector: AgentCapabilityInspector,
) : ViewModel() {

    private var skillBackfillStarted = false

    override fun onCleared() {
        super.onCleared()
    }

    val shoppingItems: StateFlow<List<ShoppingItemRecord>> = shoppingFeature.items
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val remoteNodes: StateFlow<List<RemoteNode>> = remoteNodeFeature.nodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _capabilityStatus = MutableStateFlow(CapabilityStatus())
    val capabilityStatus: StateFlow<CapabilityStatus> = _capabilityStatus.asStateFlow()

    fun refreshCapabilityStatus() {
        _capabilityStatus.value = CapabilityStatusMapper.from(
            snapshot = agentCapabilityInspector.snapshot(),
            homeAssistantConfigured = homeAssistantFeature.configured.value,
            remoteNodeCount = remoteNodes.value.size,
            wakeWordEnabled = apiKeyStore.wakeWordEnabled,
        )
    }

    fun updateShoppingItemStatus(productId: String, status: String) {
        viewModelScope.launch {
            shoppingFeature.updateStatus(productId, status)
        }
    }

    fun deleteShoppingItem(productId: String) {
        viewModelScope.launch {
            shoppingFeature.delete(productId)
        }
    }

    fun clearShoppingItems() {
        viewModelScope.launch {
            shoppingFeature.clear()
        }
    }

}

internal fun repairToolCallIds(messages: List<AgentMessageEntity>): List<AgentMessageEntity> {
    val pendingIds = ArrayDeque<String>()
    return messages.map { msg ->
        if (msg.role == "assistant") {
            msg.toolCallsJson?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) pendingIds.addLast(id)
                    }
                }
            }
            msg
        } else if (msg.role == "tool" && msg.toolCallId.isNullOrBlank()) {
            val id = pendingIds.removeFirstOrNull()
            if (id != null) msg.copy(toolCallId = id) else msg
        } else {
            msg
        }
    }
}

internal fun AgentMessageEntity.toAgentMessage(): AgentMessage = AgentMessage(
    role = role,
    content = content,
    toolCallId = toolCallId,
    toolName = toolName,
    toolArgs = toolArgs,
    toolResultOk = toolResultOk,
    toolCallsJson = toolCallsJson,
    reasoningContent = reasoningContent,
    durationMs = durationMs,
    thinkingMs = thinkingMs,
    outputMs = outputMs,
)

internal fun AgentStepEntity.toAgentStepUi(): AgentStepUi = AgentStepUi(
    index = stepIndex,
    runId = runId,
    toolName = toolName,
    argsText = argsText,
    status = runCatching { AgentStepStatus.valueOf(status) }.getOrDefault(AgentStepStatus.FAILED),
    message = message,
    durationMs = durationMs,
    gapBeforeMs = gapBeforeMs,
    startedAtElapsedMs = startedAtElapsedMs,
)

data class MainUiState(
    val input: String = "",
    val isParsing: Boolean = false,
    val parsedDraft: TaskDraft? = null,
    val parseMessage: String? = null,
    val manualPackage: String = "",
    val manualDeepLink: String = "",
    val isSummarizing: Boolean = false,
    val logSummary: String? = null,
    val lastAiError: String? = null,
    val lastAiRawResponse: String? = null,
    val lastAiParseError: String? = null,
)


data class PendingAgentConfirmation(
    val request: SensitiveActionRequest,
    val deferred: CompletableDeferred<Boolean>,
)
