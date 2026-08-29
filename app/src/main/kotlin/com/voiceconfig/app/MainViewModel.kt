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
    private val agentHistoryRepository: AgentHistoryRepository,
    private val agentSession: AgentSession,
    private val agentSkillStore: AgentSkillStore,
    private val agentRunLedger: AgentRunLedger,
    private val agentCapabilityInspector: AgentCapabilityInspector,
    private val agentTrace: AgentTrace,
    private val taskPlanStore: TaskPlanStore,
    private val ttsSpeaker: TtsSpeaker,
    private val homeAssistantFeature: HomeAssistantFeature,
    private val voiceSessionManager: VoiceSessionManager,
) : ViewModel() {

    private var skillBackfillStarted = false

    init {
        viewModelScope.launch {
            backfillSkillsFromHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    val shoppingItems: StateFlow<List<ShoppingItemRecord>> = shoppingFeature.items
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _selectedAgentSessionId = MutableStateFlow<Long?>(null)
    val selectedAgentSessionId: StateFlow<Long?> = _selectedAgentSessionId.asStateFlow()

    private val _isAgentBusy = MutableStateFlow(false)
    val isAgentBusy: StateFlow<Boolean> = _isAgentBusy.asStateFlow()

    private val _agentStreamText = MutableStateFlow("")
    val agentStreamText: StateFlow<String> = _agentStreamText.asStateFlow()

    private val _agentReasoningText = MutableStateFlow("")
    val agentReasoningText: StateFlow<String> = _agentReasoningText.asStateFlow()

    private val _agentDraft = MutableStateFlow("")
    val agentDraft: StateFlow<String> = _agentDraft.asStateFlow()

    private var lastVoiceSessionId: String? = null
    private var lastVoiceText: String = ""

    private val _voiceSession = MutableStateFlow(VoiceSession())
    val voiceSession: StateFlow<VoiceSession> = _voiceSession.asStateFlow()

    /** AutomationViewModel 由 Compose 层注入，用于统一语音/自然语言到自动化页的状态回退。 */
    var automationViewModel: AutomationViewModel? = null

    val agentSessions: StateFlow<List<AgentSessionEntity>> = agentHistoryRepository.observeSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val agentRunRecords: StateFlow<List<AgentRunRecord>> = agentRunLedger.observeRecords(100)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _agentRunDetail = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val agentRunDetail: StateFlow<List<Map<String, Any?>>> = _agentRunDetail.asStateFlow()

    fun loadAgentRunDetail(runId: String) {
        _agentRunDetail.value = emptyList()
        viewModelScope.launch {
            _agentRunDetail.value = withContext(Dispatchers.IO) {
                agentTrace.readRun(runId)
            }
        }
    }

    fun clearAgentRunDetail() {
        _agentRunDetail.value = emptyList()
    }

    private suspend fun backfillSkillsFromHistory() {
        if (skillBackfillStarted) return
        skillBackfillStarted = true
        val records = agentRunLedger.observeRecords(200)
            .first { it.isNotEmpty() }
            .filter { it.ok && it.verified != false && it.toolCalls.isNotEmpty() }
            .takeLast(30)
        for (record in records) {
            val events = withContext(Dispatchers.IO) {
                agentTrace.readRun(record.runId)
            }
            if (events.isNotEmpty()) {
                agentSkillStore.ingestFromTrace(
                    runId = record.runId,
                    userText = record.userText,
                    traceEvents = events,
                    verified = record.verified,
                    capabilitySummary = record.capabilitySummary,
                )
            }
        }
    }

    val agentMessages: StateFlow<List<AgentMessageEntity>> = _selectedAgentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else agentHistoryRepository.observeMessages(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val taskEvents: StateFlow<List<TaskEventEntity>> = agentHistoryRepository.observeTaskEvents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _pendingAgentConfirmation = MutableStateFlow<PendingAgentConfirmation?>(null)
    val pendingAgentConfirmation: StateFlow<PendingAgentConfirmation?> = _pendingAgentConfirmation.asStateFlow()

    private val _agentSteps = MutableStateFlow<List<AgentStepUi>>(emptyList())
    val agentSteps: StateFlow<List<AgentStepUi>> = _agentSteps.asStateFlow()

    private val _canResumeTask = MutableStateFlow(false)
    val canResumeTask: StateFlow<Boolean> = _canResumeTask.asStateFlow()

    private val _activeTaskPlans = MutableStateFlow<List<TaskPlan>>(emptyList())
    val activeTaskPlans: StateFlow<List<TaskPlan>> = _activeTaskPlans.asStateFlow()
    private val _lastAgentRunDurationMs = MutableStateFlow<Long?>(null)
    val lastAgentRunDurationMs: StateFlow<Long?> = _lastAgentRunDurationMs.asStateFlow()

    val agentSkills: StateFlow<List<AgentSkill>> = agentSkillStore.observeSkills()

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

    fun openAgentPage() {
        refreshCapabilityStatus()
        val latest = agentSessions.value.firstOrNull()?.id
        _selectedAgentSessionId.value = latest
        if (latest != null) {
            viewModelScope.launch {
                val messages = repairToolCallIds(agentHistoryRepository.getMessages(latest))
                agentSession.restore(messages.map { it.toAgentMessage() })
                _agentSteps.value = agentHistoryRepository.getSteps(latest).map { it.toAgentStepUi() }
                _lastAgentRunDurationMs.value = agentHistoryRepository.getSession(latest)?.lastRunDurationMs
            }
        }
        viewModelScope.launch {
            val plans = taskPlanStore.loadActivePlans()
            _activeTaskPlans.value = plans
            _canResumeTask.value = plans.isNotEmpty()
        }
    }

    fun cancelUnfinishedTaskPlans() {
        if (_isAgentBusy.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                taskPlanStore.deleteAllActive()
            }
            _activeTaskPlans.value = emptyList()
            _canResumeTask.value = false
        }
    }

    fun cancelTaskPlan(planId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                taskPlanStore.delete(planId)
            }
            _activeTaskPlans.value = _activeTaskPlans.value.filterNot { it.id == planId }
            _canResumeTask.value = _activeTaskPlans.value.isNotEmpty()
        }
    }

    fun resumeTaskPlan(planId: String) {
        if (_isAgentBusy.value) return
        viewModelScope.launch {
            val plan = taskPlanStore.loadActivePlans().firstOrNull { it.id == planId } ?: return@launch
            sendAgentMessage("继续上次任务", explicitPlan = plan)
        }
    }

    fun resumeLastTask() {
        if (_isAgentBusy.value) return
        viewModelScope.launch {
            val plan = taskPlanStore.loadActive()
            if (plan != null) {
                sendAgentMessage("继续上次任务")
            }
        }
    }

    fun selectAgentSession(sessionId: Long) {
        _selectedAgentSessionId.value = sessionId
        _agentSteps.value = emptyList()
        viewModelScope.launch {
            val messages = repairToolCallIds(agentHistoryRepository.getMessages(sessionId))
            agentSession.restore(messages.map { it.toAgentMessage() })
            _agentSteps.value = agentHistoryRepository.getSteps(sessionId).map { it.toAgentStepUi() }
            _lastAgentRunDurationMs.value = agentHistoryRepository.getSession(sessionId)?.lastRunDurationMs
        }
    }

    fun clearSelectedAgentSession() {
        _selectedAgentSessionId.value = null
        _agentSteps.value = emptyList()
        viewModelScope.launch {
            agentSession.clear()
        }
    }

    fun newAgentSession() {
        _agentSteps.value = emptyList()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = agentHistoryRepository.createSession("新会话", now)
            _selectedAgentSessionId.value = id
            agentSession.clear()
        }
    }

    fun renameAgentSession(sessionId: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            agentHistoryRepository.renameSession(sessionId, trimmed)
        }
    }

    fun deleteAgentSession(sessionId: Long) {
        viewModelScope.launch {
            if (_selectedAgentSessionId.value == sessionId) {
                _selectedAgentSessionId.value = null
                _agentSteps.value = emptyList()
                agentSession.clear()
            }
            agentHistoryRepository.deleteSession(sessionId)
        }
    }

    fun clearAllAgentSessions() {
        viewModelScope.launch {
            agentHistoryRepository.deleteAllSessions()
            clearSelectedAgentSession()
        }
    }

    fun clearAgentSession(sessionId: Long) {
        viewModelScope.launch {
            agentHistoryRepository.clearMessages(sessionId)
            agentHistoryRepository.clearSteps(sessionId)
            if (_selectedAgentSessionId.value == sessionId) {
                _agentSteps.value = emptyList()
                agentSession.clear()
            }
        }
    }

    fun stopAgent() {
        agentSession.cancel()
        _agentStreamText.value = "正在停止..."
    }

    fun onAgentInputChange(value: String) {
        _agentDraft.value = value
    }

    fun clearAgentDraft() {
        _agentDraft.value = ""
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

    fun submitAgentDraft() {
        val text = _agentDraft.value.trim()
        if (text.isNotBlank() && !_isAgentBusy.value) {
            sendAgentMessage(text)
            _agentDraft.value = ""
        }
    }

    /**
     * 统一自然语言入口：配置云模型时直接进入 Agent 单管道；
     * 未配置时回退到本地/兼容解析，仅用于模板和历史数据兼容。
     */
    fun submitNaturalLanguageInput() {
        val text = automationViewModel?.uiState?.value?.input?.trim().orEmpty()
        if (text.isBlank()) return
        if (apiKeyStore.deepSeekApiKey.isNotBlank()) {
            sendAgentMessage(text)
        } else {
            automationViewModel?.parse()
        }
    }

    fun onInputChange(value: String) {
        automationViewModel?.onInputChange(value)
    }

    fun setParseMessage(message: String) {
        automationViewModel?.setParseMessage(message)
    }

    fun parse() {
        automationViewModel?.parse()
    }

    /** 统一语音入口：所有 ASR 结果都应通过这里进入 Home/Agent 管道。 */
    fun submitVoiceResult(
        text: String,
        asrEngine: String = "unknown",
        language: String? = null,
        confidence: Float? = null,
        toAgent: Boolean = false,
        autoParse: Boolean = true,
        voiceSessionId: String? = null,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        if (voiceSessionId != null && voiceSessionId == lastVoiceSessionId && normalized == lastVoiceText) {
            // 同一个语音会话的重复 final 结果，忽略，避免创建重复目标。
            return
        }
        if (voiceSessionId != null) {
            lastVoiceSessionId = voiceSessionId
            lastVoiceText = normalized
        }
        val intent = VoiceIntent.fromText(text, asrEngine, language, confidence)
        submitVoiceIntent(intent, toAgent = toAgent, autoParse = autoParse)
    }

    fun submitVoiceIntent(
        intent: VoiceIntent,
        toAgent: Boolean = false,
        autoParse: Boolean = true,
    ) {
        if (intent.isBlank) return
        val resolved = intent.copy(
            intentType = if (toAgent) VoiceIntentType.AGENT else VoiceIntentType.SIMPLE_TASK,
        )

        // 第一阶段统一管道：只要配置了云模型，自然语言一律走
        // 云 LLM + Function Calling，不再由本地解析器判断简单/复杂。
        // 本地解析器仅作为未配置云模型时的兼容/模板回退。
        if (!toAgent && apiKeyStore.deepSeekApiKey.isNotBlank()) {
            _agentDraft.value = resolved.normalized
            sendAgentMessage(resolved.normalized)
            _agentDraft.value = ""
            return
        }

        if (toAgent) {
            _agentDraft.value = resolved.normalized
            if (apiKeyStore.agentVoiceAutoSend && apiKeyStore.deepSeekApiKey.isNotBlank()) {
                sendAgentMessage(resolved.normalized)
                _agentDraft.value = ""
            }
        } else {
            onInputChange(resolved.normalized)
            if (autoParse) {
                parse()
            }
        }
    }

    fun sendAgentMessage(text: String, explicitPlan: TaskPlan? = null) {
        if (text.isBlank() || _isAgentBusy.value) return
        viewModelScope.launch {
            _isAgentBusy.value = true
            _agentStreamText.value = ""
            _agentReasoningText.value = ""
            _agentSteps.value = emptyList()
            voiceSessionManager.begin(text)
            _voiceSession.value = voiceSessionManager.current()
            try {
                val now = System.currentTimeMillis()
                var sessionId = _selectedAgentSessionId.value
                val isNewSession = sessionId == null
                if (sessionId == null) {
                    sessionId = agentHistoryRepository.createSession(text.take(24), now)
                    _selectedAgentSessionId.value = sessionId
                }
                val targetSessionId: Long = sessionId
                val relevantSkills = agentSkillStore.relevant(text)
                val verifyPolicy = AgentVerificationPolicy(
                    enabled = apiKeyStore.agentAutoVerifyEnabled,
                    maxPerRun = apiKeyStore.agentMaxAutoVerifies,
                )
                val resumeIntent = text.contains("继续") || text.contains("恢复") || text.contains("接着做")
                val resumePlan = explicitPlan ?: if (resumeIntent) {
                    taskPlanStore.loadActive()
                } else {
                    null
                }
                if (resumePlan != null) taskPlanStore.set(resumePlan)
                val capabilitySummary = agentCapabilityInspector.snapshot().summary()
                val result = agentSession.send(
                    text,
                    skills = relevantSkills,
                    verifyPolicy = verifyPolicy,
                    plan = resumePlan,
                    resetHistory = isNewSession,
                    capabilitySummary = capabilitySummary,
                    onSensitiveAction = { request -> confirmSensitiveAction(request) },
                    onStep = { step ->
                        _agentSteps.update { current ->
                            val index = current.indexOfFirst { it.index == step.index }
                            if (index >= 0) {
                                current.toMutableList().apply { set(index, step) }
                            } else {
                                current + step
                            }
                        }
                        if (step.runId.isNotBlank() && step.status != AgentStepStatus.RUNNING) {
                            viewModelScope.launch {
                                agentHistoryRepository.upsertStep(
                                    AgentStepEntity(
                                        sessionId = targetSessionId,
                                        runId = step.runId,
                                        stepIndex = step.index,
                                        toolName = step.toolName,
                                        argsText = step.argsText,
                                        status = step.status.name,
                                        message = step.message,
                                        durationMs = step.durationMs,
                                        gapBeforeMs = step.gapBeforeMs,
                                        startedAtElapsedMs = step.startedAtElapsedMs,
                                        createdAtEpochMillis = System.currentTimeMillis(),
                                        updatedAtEpochMillis = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    },
                    onStreamEvent = { event ->
                        when (event) {
                            is AgentStreamEvent.Content -> _agentStreamText.value += event.text
                            is AgentStreamEvent.Reasoning -> _agentReasoningText.value += event.text
                            is AgentStreamEvent.ToolCallDelta -> {
                                if (_agentStreamText.value.isBlank()) {
                                    _agentStreamText.value = "正在调用工具：${event.name ?: "..."}"
                                }
                            }
                            else -> Unit
                        }
                    },
                    onMessage = { msg ->
                        // 实时写入数据库，使对话卡片在执行过程中即时渲染。
                        // 截图类 user 消息只用于多模态上下文，不持久化到聊天记录。
                        if (msg.imageBase64 == null) {
                            agentHistoryRepository.addMessage(
                                AgentMessageEntity(
                                    sessionId = targetSessionId,
                                    role = msg.role,
                                    content = msg.content,
                                    toolName = msg.toolName,
                                    toolArgs = msg.toolArgs,
                                    toolResultOk = msg.toolResultOk,
                                    toolCallId = msg.toolCallId,
                                    toolCallsJson = msg.toolCallsJson,
                                    reasoningContent = msg.reasoningContent,
                                    durationMs = msg.durationMs,
                                    thinkingMs = msg.thinkingMs,
                                    outputMs = msg.outputMs,
                                    ttftMs = msg.ttftMs,
                                    createdAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                )
                _lastAgentRunDurationMs.value = result.durationMs.takeIf { it > 0 }
                if (result.durationMs > 0) {
                    agentHistoryRepository.updateSessionDuration(sessionId, result.durationMs, System.currentTimeMillis())
                }
                val sessionTitle = agentHistoryRepository.getSession(sessionId)?.title
                    ?.takeIf { it.isNotBlank() && it != "新会话" }
                    ?: (result.history.firstOrNull()?.content?.take(24) ?: text.take(24))
                agentHistoryRepository.updateSession(
                    sessionId = sessionId,
                    title = sessionTitle,
                    now = System.currentTimeMillis(),
                    messageCount = result.history.count { it.imageBase64 == null },
                )
                if (result.ok) {
                    agentSkillStore.recordFromTurn(
                        text = text,
                        result = result,
                        sourceSessionId = sessionId,
                        capabilitySummary = capabilitySummary,
                    )
                }
                _voiceSession.value = when {
                    result.state == AgentRunState.WAITING_CONFIRM ->
                        voiceSessionManager.waitUser(result.message)
                    result.ok -> voiceSessionManager.complete()
                    else -> voiceSessionManager.current().copy(state = com.voiceconfig.app.agent.VoiceSessionState.IDLE)
                }
                if (apiKeyStore.agentTtsEnabled && result.message.isNotBlank()) {
                    ttsSpeaker.speak(result.message)
                }
            } finally {
                _isAgentBusy.value = false
            }
        }
    }

    fun approveAgentSkill(id: String) {
        agentSkillStore.approve(id)
    }

    fun rejectAgentSkill(id: String) {
        agentSkillStore.reject(id)
    }

    fun deleteAgentSkill(id: String) {
        agentSkillStore.delete(id)
    }

    fun setAgentSkillEnabled(id: String, enabled: Boolean) {
        agentSkillStore.setEnabled(id, enabled)
    }

    fun redactAgentSkill(id: String) {
        agentSkillStore.redact(id)
    }

    fun exportAgentSkill(id: String): String? = agentSkillStore.exportSkill(id)

    fun exportAllAgentSkills(): String = agentSkillStore.exportAll()

    fun importAgentSkill(json: String, source: String = "Android"): AgentSkill? =
        agentSkillStore.importSkill(json, source)

    suspend fun confirmSensitiveAction(request: SensitiveActionRequest): Boolean {
        if (apiKeyStore.agentAutoConfirmSensitiveActions) return true
        val deferred = CompletableDeferred<Boolean>()
        _pendingAgentConfirmation.value = PendingAgentConfirmation(
            request = request,
            deferred = deferred,
        )
        return deferred.await()
    }

    fun resolveAgentConfirmation(approved: Boolean) {
        val pending = _pendingAgentConfirmation.value ?: return
        pending.deferred.complete(approved)
        _pendingAgentConfirmation.value = null
    }
}

private fun repairToolCallIds(messages: List<AgentMessageEntity>): List<AgentMessageEntity> {
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

private fun AgentMessageEntity.toAgentMessage(): AgentMessage = AgentMessage(
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

private fun AgentStepEntity.toAgentStepUi(): AgentStepUi = AgentStepUi(
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
