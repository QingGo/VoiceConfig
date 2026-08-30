package com.voiceconfig.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceconfig.app.agent.AgentMessage
import com.voiceconfig.app.agent.AgentRunLedger
import com.voiceconfig.app.agent.AgentRunRecord
import com.voiceconfig.app.agent.AgentCapabilityInspector
import com.voiceconfig.app.agent.AgentPreflight
import com.voiceconfig.app.agent.AgentPreflightResult
import com.voiceconfig.app.agent.AgentRunState
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStore
import com.voiceconfig.app.agent.AgentStepStatus
import com.voiceconfig.app.agent.AgentStepUi
import com.voiceconfig.app.agent.AgentStreamEvent
import com.voiceconfig.app.agent.AgentTrace
import com.voiceconfig.app.agent.AgentVerificationPolicy
import com.voiceconfig.app.agent.SensitiveActionRequest
import com.voiceconfig.app.agent.TaskPlan
import com.voiceconfig.app.agent.TaskPlanStore
import com.voiceconfig.app.agent.VoiceSession
import com.voiceconfig.app.agent.VoiceSessionManager
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.ai.TtsSpeaker
import com.voiceconfig.app.voice.GlobalVoiceCommand
import com.voiceconfig.app.voice.VoiceCommandOrigin
import com.voiceconfig.app.voice.VoiceCommandCenter
import com.voiceconfig.app.voice.VoiceCommandTarget
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.AgentStepEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.data.local.repository.AgentHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentViewModel @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
    private val agentHistoryRepository: AgentHistoryRepository,
    private val agentSession: AgentSession,
    private val agentSkillStore: AgentSkillStore,
    private val agentRunLedger: AgentRunLedger,
    private val agentCapabilityInspector: AgentCapabilityInspector,
    private val agentTrace: AgentTrace,
    private val taskPlanStore: TaskPlanStore,
    private val ttsSpeaker: TtsSpeaker,
    private val voiceSessionManager: VoiceSessionManager,
    private val voiceCommandCenter: VoiceCommandCenter,
) : ViewModel() {

    private var skillBackfillStarted = false

    init {
        viewModelScope.launch {
            backfillSkillsFromHistory()
        }
        viewModelScope.launch {
            voiceCommandCenter.commands
                .filter { it.target == VoiceCommandTarget.AGENT }
                .collect { command ->
                    if (voiceCommandCenter.isAcked(command.commandId)) return@collect
                    if (voiceCommandCenter.isExpired(command)) {
                        voiceCommandCenter.ack(command.commandId)
                        return@collect
                    }
                    handleVoiceCommand(command)
                    voiceCommandCenter.ack(command.commandId)
                }
        }
    }

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

    private val _voiceSession = MutableStateFlow(VoiceSession())
    val voiceSession: StateFlow<VoiceSession> = _voiceSession.asStateFlow()

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

    fun openAgentPage() {
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

    /**
     * VoiceCommandCenter 的统一 Agent 命令入口。
     * 每个命令在这里只会处理一次；处理完成后由调用方 ack。
     */
    fun handleVoiceCommand(command: GlobalVoiceCommand) {
        val text = command.text
        if (text.isBlank()) return
        if (command.autoSend && apiKeyStore.deepSeekApiKey.isNotBlank()) {
            onAgentInputChange(text)
            sendAgentMessage(text, origin = VoiceCommandOrigin.from(command))
            clearAgentDraft()
        } else {
            onAgentInputChange(text)
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

    fun sendAgentMessage(
        text: String,
        explicitPlan: TaskPlan? = null,
        origin: VoiceCommandOrigin? = null,
    ) {
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
                val capabilitySnapshot = agentCapabilityInspector.snapshot()
                val capabilitySummary = capabilitySnapshot.summary()
                val preflight = AgentPreflight.evaluate(capabilitySnapshot, text)
                val result = agentSession.send(
                    text,
                    skills = relevantSkills,
                    verifyPolicy = verifyPolicy,
                    plan = resumePlan,
                    resetHistory = isNewSession,
                    capabilitySummary = capabilitySummary,
                    origin = origin,
                    preflight = preflight,
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
        val approved = withTimeoutOrNull(SENSITIVE_CONFIRM_TIMEOUT_MS) {
            deferred.await()
        } ?: false
        _pendingAgentConfirmation.value = null
        return approved
    }

    companion object {
        private const val SENSITIVE_CONFIRM_TIMEOUT_MS = 60_000L
    }

    fun resolveAgentConfirmation(approved: Boolean) {
        val pending = _pendingAgentConfirmation.value ?: return
        pending.deferred.complete(approved)
        _pendingAgentConfirmation.value = null
    }
}
