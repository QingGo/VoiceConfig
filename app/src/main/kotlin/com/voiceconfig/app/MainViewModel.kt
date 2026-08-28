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
import com.voiceconfig.data.local.repository.TemplateRepository
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
    private val parser: NaturalLanguageParser,
    private val scheduleModificationParser: ScheduleModificationParser,
    private val appAliasResolver: AppAliasResolver,
    private val taskRepository: TaskRepository,
    private val taskScheduler: TaskScheduler,
    private val nextRunCalculator: NextRunCalculator,
    private val templateRepository: TemplateRepository,
    private val triggerRuleRepository: TriggerRuleRepository,
    private val triggerRuleScheduler: TriggerRuleScheduler,
    private val remoteNodeRepository: RemoteNodeRepository,
    private val remoteProjectRepository: RemoteProjectRepository,
    private val shoppingFeature: ShoppingFeature,
    private val remoteCommandClient: RemoteCommandClient,
    private val sshClient: SshClient,
    private val sshBootstrapClient: SshBootstrapClient,
    private val sshCredentialStore: SshCredentialStore,
    private val sshHostKeyStore: SshHostKeyStore,
    private val sshAuditStore: SshAuditStore,
    private val sshKeyStore: SshKeyStore,
    private val sshKeyManager: SshKeyManager,
    private val executionLogRepository: ExecutionLogRepository,
    private val userAliasRegistry: UserAliasRegistry,
    private val executionEngine: ExecutionEngine,
    private val apiKeyStore: ApiKeyStore,
    private val deepSeekNlpParser: DeepSeekNlpParser,
    private val aiDebugLogRepository: AiDebugLogRepository,
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
            seedTemplatesIfEmpty()
            migrateLegacyTasks()
            restoreSchedules()
            backfillSkillsFromHistory()
        }
    }

    override fun onCleared() {
        closeSshShell()
        super.onCleared()
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val tasks: StateFlow<List<Task>> = taskRepository.observeTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val templates: StateFlow<List<Template>> = templateRepository.observeTemplates()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val shoppingItems: StateFlow<List<ShoppingItemRecord>> = shoppingFeature.items
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val remoteNodes: StateFlow<List<RemoteNode>> = remoteNodeRepository.observeNodes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val remoteProjects: StateFlow<List<RemoteProjectRecord>> = remoteProjectRepository.observeProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _remoteCommandResult = MutableStateFlow<RemoteCommandResult?>(null)
    val remoteCommandResult: StateFlow<RemoteCommandResult?> = _remoteCommandResult.asStateFlow()

    private val _sshResult = MutableStateFlow<SshExecResult?>(null)
    val sshResult: StateFlow<SshExecResult?> = _sshResult.asStateFlow()

    private val _sshServiceResult = MutableStateFlow<SshExecResult?>(null)
    val sshServiceResult: StateFlow<SshExecResult?> = _sshServiceResult.asStateFlow()

    private val _sshNodeLogResult = MutableStateFlow<SshExecResult?>(null)
    val sshNodeLogResult: StateFlow<SshExecResult?> = _sshNodeLogResult.asStateFlow()

    private val _sshBootstrapResult = MutableStateFlow<SshBootstrapResult?>(null)
    val sshBootstrapResult: StateFlow<SshBootstrapResult?> = _sshBootstrapResult.asStateFlow()

    private val _pendingSshHostKey = MutableStateFlow<SshPendingTrust?>(null)
    val pendingSshHostKey: StateFlow<SshPendingTrust?> = _pendingSshHostKey.asStateFlow()

    private val _sshFileResult = MutableStateFlow<SshFileResult?>(null)
    val sshFileResult: StateFlow<SshFileResult?> = _sshFileResult.asStateFlow()

    private val _sshFileEntries = MutableStateFlow<List<SshRemoteFile>?>(null)
    val sshFileEntries: StateFlow<List<SshRemoteFile>?> = _sshFileEntries.asStateFlow()

    private val _sshShellOutput = MutableStateFlow("")
    val sshShellOutput: StateFlow<String> = _sshShellOutput.asStateFlow()

    private val _sshShellRunning = MutableStateFlow(false)
    val sshShellRunning: StateFlow<Boolean> = _sshShellRunning.asStateFlow()

    private val _sshShellError = MutableStateFlow<String?>(null)
    val sshShellError: StateFlow<String?> = _sshShellError.asStateFlow()

    private val _sshAuditText = MutableStateFlow("")
    val sshAuditText: StateFlow<String> = _sshAuditText.asStateFlow()

    private val _sshKeys = MutableStateFlow<List<SshManagedKey>>(sshKeyStore.list())
    val sshKeys: StateFlow<List<SshManagedKey>> = _sshKeys.asStateFlow()

    private var sshShellHandle: SshShellHandle? = null
    private var sshShellHostHost: String? = null
    private var sshShellHostPort: Int? = null
    private var sshShellHostUser: String? = null

    val triggerRules: StateFlow<List<TriggerRule>> = triggerRuleRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val recentLogs: StateFlow<List<ExecutionLog>> = executionLogRepository.observeRecent(20)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val aiDebugLogs: StateFlow<List<AiDebugLogEntity>> = aiDebugLogRepository.observeRecent(50)
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

    private val _agentVoiceAutoSend = MutableStateFlow(apiKeyStore.agentVoiceAutoSend)
    val agentVoiceAutoSend: StateFlow<Boolean> = _agentVoiceAutoSend.asStateFlow()

    private val _agentTtsEnabled = MutableStateFlow(apiKeyStore.agentTtsEnabled)
    val agentTtsEnabled: StateFlow<Boolean> = _agentTtsEnabled.asStateFlow()

    private val _wakeWordEnabled = MutableStateFlow(apiKeyStore.wakeWordEnabled)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(apiKeyStore.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    val homeAssistantBaseUrl: StateFlow<String> = homeAssistantFeature.baseUrl
    val homeAssistantToken: StateFlow<String> = homeAssistantFeature.token
    val homeAssistantConfigured: StateFlow<Boolean> = homeAssistantFeature.configured
    val homeAssistantDevices: StateFlow<List<HomeAssistantDevice>?> = homeAssistantFeature.devices
    val homeAssistantTestMessage: StateFlow<String?> = homeAssistantFeature.testMessage
    val homeAssistantControlMessage: StateFlow<String?> = homeAssistantFeature.controlMessage

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

    private val _agentAutoVerifyEnabled = MutableStateFlow(apiKeyStore.agentAutoVerifyEnabled)
    val agentAutoVerifyEnabled: StateFlow<Boolean> = _agentAutoVerifyEnabled.asStateFlow()

    private val _agentMaxAutoVerifies = MutableStateFlow(apiKeyStore.agentMaxAutoVerifies)
    val agentMaxAutoVerifies: StateFlow<Int> = _agentMaxAutoVerifies.asStateFlow()

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

    fun onInputChange(value: String) {
        _uiState.update {
            it.copy(
                input = value,
                parsedDraft = null,
                parseMessage = null,
                manualPackage = "",
                manualDeepLink = "",
            )
        }
    }

    fun onManualPackageChange(value: String) {
        _uiState.update { it.copy(manualPackage = value) }
    }

    fun onManualDeepLinkChange(value: String) {
        _uiState.update { it.copy(manualDeepLink = value) }
    }

    /**
     * 【兼容路径】旧的自然语言解析：仅在未配置云模型时作为模板/历史数据回退。
     * 不要在此扩展新的意图能力。
     */
    fun parse() {
        val input = _uiState.value.input
        if (_uiState.value.isParsing) return
        _uiState.update { it.copy(isParsing = true, parseMessage = null) }
        viewModelScope.launch {
            var usedModification = false
            val draft = withContext(Dispatchers.IO) {
                val currentDraft = _uiState.value.parsedDraft
                val modified = currentDraft?.schedule?.let { currentSchedule ->
                    scheduleModificationParser.parse(input, currentSchedule)
                }
                if (modified != null) {
                    usedModification = true
                    currentDraft!!.copy(
                        rawText = currentDraft.rawText,
                        schedule = modified,
                        confidence = 0.95,
                    )
                } else {
                    val parsed = runCatching { parser.parse(input) }.getOrNull()
                    parsed?.let { d ->
                        if (d.actionType == ActionType.OPEN_APP && d.targetPackage.isNullOrBlank()) {
                            val alias = extractAppAlias(d.rawText.ifBlank { input })
                            val resolved = alias?.let { appAliasResolver.resolve(it) }
                            if (resolved != null) {
                                d.copy(
                                    targetPackage = resolved.packageName,
                                    targetActivity = resolved.activityName ?: d.targetActivity,
                                )
                            } else {
                                d
                            }
                        } else if (d.actionType == ActionType.AGENT) {
                            d.copy(
                                agentPrompt = d.agentPrompt?.takeIf { it.isNotBlank() } ?: input,
                                executionMode = ExecutionMode.AGENT,
                            )
                        } else {
                            d
                        }
                    }
                }
            }
            val source = when {
                usedModification -> "多轮修改"
                deepSeekNlpParser.lastUsedRemote -> "DeepSeek"
                else -> "本地规则"
            }
            _uiState.update {
                it.copy(
                    isParsing = false,
                    parsedDraft = draft,
                    parseMessage = when {
                        draft == null -> {
                            val noKey = apiKeyStore.deepSeekApiKey.isBlank()
                            when {
                                noKey -> "当前未配置 DeepSeek，仅支持提醒/定时打开App等简单任务；复杂任务需先配置大模型"
                                deepSeekNlpParser.lastError != null && !deepSeekNlpParser.lastUsedRemote ->
                                    "解析失败：${deepSeekNlpParser.lastError}；复杂任务需要大模型或检查网络，简单任务仍可使用"
                                else -> "未能理解，请换一种说法（$source）"
                            }
                        }
                        draft.schedule != null && draft.confidence < AUTO_CREATE_THRESHOLD ->
                            "已识别，但置信度较低，请确认后创建（$source）"
                        else -> {
                            if (draft.actionType == ActionType.AGENT) {
                                "将使用智能助手执行，保存后到点自动运行（$source）"
                            } else {
                                "解析成功，请确认任务（$source）"
                            }
                        }
                    },
                    manualPackage = "",
                    manualDeepLink = "",
                    lastAiError = deepSeekNlpParser.lastError,
                    lastAiRawResponse = deepSeekNlpParser.lastRawResponse,
                    lastAiParseError = deepSeekNlpParser.lastParseError,
                )
            }
            // 语音/输入只生成预览，不再自动创建定时任务，避免一句话产生多个重复目标。
        }
    }

    companion object {
        const val AUTO_CREATE_THRESHOLD = 0.85
    }

    fun confirmCreate() {
        val draft = _uiState.value.parsedDraft ?: return
        val schedule = draft.schedule ?: run {
            _uiState.update { it.copy(parseMessage = "该任务缺少时间信息，无法创建定时任务") }
            return
        }
        val manualPackage = _uiState.value.manualPackage.trim().ifBlank { null }
        val effectiveTargetPackage = draft.targetPackage ?: manualPackage
        if (manualPackage != null && draft.targetPackage == null) {
            learnUserAlias(draft.rawText, manualPackage)
        }
        if (draft.actionType == com.voiceconfig.core.model.ActionType.OPEN_APP && effectiveTargetPackage.isNullOrBlank()) {
            _uiState.update { it.copy(parseMessage = "无法识别要打开的应用，请手动填写包名或重新输入") }
            return
        }
        val manualDeepLink = _uiState.value.manualDeepLink.trim().ifBlank { null }
        val effectiveDeepLink = draft.deepLink ?: manualDeepLink
        if (draft.actionType == com.voiceconfig.core.model.ActionType.OPEN_DEEPLINK && effectiveDeepLink.isNullOrBlank()) {
            _uiState.update { it.copy(parseMessage = "无法识别要打开的页面，请手动填写 Deep Link 或重新输入") }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val nextRunAt = nextRunCalculator.nextRunAfter(schedule)
                ?.atZone(ZoneId.systemDefault())
                ?.toInstant()
                ?.toEpochMilli()
            if (nextRunAt == null) {
                _uiState.update { it.copy(parseMessage = "该时间已过或无法计算下次执行时间") }
                return@launch
            }

            val task = Task(
                rawText = draft.rawText,
                title = if (draft.actionType == ActionType.AGENT) {
                    "智能助手：${draft.rawText.take(24)}"
                } else {
                    draft.rawText.take(30)
                },
                schedule = schedule,
                actionType = draft.actionType,
                targetPackage = effectiveTargetPackage,
                targetActivity = draft.targetActivity,
                deepLink = effectiveDeepLink,
                agentPrompt = if (draft.actionType == ActionType.AGENT) {
                    (draft.agentPrompt ?: draft.rawText).trim().ifBlank { draft.rawText }
                } else {
                    null
                },
                executionMode = if (draft.actionType == ActionType.AGENT) ExecutionMode.AGENT else draft.executionMode,
                nextRunAtEpochMillis = nextRunAt,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            val taskId = runCatching { taskRepository.saveTask(task) }
                .getOrElse { e ->
                    _uiState.update { it.copy(parseMessage = "任务保存失败：${e.message}") }
                    return@launch
                }
            runCatching { taskScheduler.schedule(task.copy(id = taskId)) }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            parsedDraft = null,
                            input = "",
                            parseMessage = "任务已保存，但定时注册失败：${e.message}",
                        )
                    }
                    return@launch
                }
            val nextRunText = nextRunAt?.let {
                java.time.Instant.ofEpochMilli(it)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .toString()
            } ?: "未计算"
            recordTaskEvent(
                taskId = taskId,
                eventType = "CREATE",
                rawText = draft.rawText,
                summary = "创建任务：$nextRunText",
                now = now,
            )
            _uiState.update {
                it.copy(
                    parsedDraft = null,
                    input = "",
                    parseMessage = "任务已创建，下次执行：$nextRunText",
                )
            }
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (task.enabled) {
                taskRepository.setEnabled(task.id, false)
                runCatching { taskScheduler.cancel(task.id) }
                recordTaskEvent(task.id, "DISABLE", task.rawText, "停用任务：${task.rawText}", now)
            } else {
                val nextRunAt = nextRunCalculator.nextRunAfter(task.schedule)
                    ?.atZone(ZoneId.systemDefault())
                    ?.toInstant()
                    ?.toEpochMilli()
                if (nextRunAt == null) {
                    _uiState.update { it.copy(parseMessage = "无法计算下次执行时间，任务未启用") }
                    return@launch
                }
                taskRepository.saveTask(
                    task.copy(
                        enabled = true,
                        nextRunAtEpochMillis = nextRunAt,
                        updatedAtEpochMillis = now,
                    ),
                )
                val scheduled = runCatching { taskScheduler.schedule(task.copy(enabled = true)) }
                if (scheduled.isFailure) {
                    taskRepository.setEnabled(task.id, false)
                    _uiState.update { it.copy(parseMessage = "任务启用失败，已恢复为停用状态") }
                } else {
                    recordTaskEvent(task.id, "ENABLE", task.rawText, "启用任务：${task.rawText}", now)
                }
            }
        }
    }

    fun copyTaskToInput(task: Task) {
        _uiState.update {
            it.copy(
                input = task.rawText,
                parsedDraft = null,
                parseMessage = null,
                manualPackage = "",
                manualDeepLink = "",
            )
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            recordTaskEvent(task.id, "DELETE", task.rawText, "删除任务：${task.rawText}", System.currentTimeMillis())
            runCatching { taskScheduler.cancel(task.id) }
            taskRepository.deleteTask(task.id)
            executionLogRepository.deleteByTask(task.id)
        }
    }

    fun runNow(task: Task) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val requestedMode = when (task.executionMode) {
                ExecutionMode.AUTO ->
                    if (!task.deepLink.isNullOrBlank()) ExecutionMode.DEEP_LINK else ExecutionMode.SHIZUKU
                else -> task.executionMode
            }
            val result = runCatching {
                if (task.actionType == ActionType.AGENT) {
                    val prompt = task.agentPrompt ?: task.rawText
                    val capability = agentCapabilityInspector.snapshot()
                    val preflight = AgentPreflight.evaluate(capability, prompt)
                    if (!preflight.ready) {
                        ExecutionResult.failure(
                            mode = ExecutionMode.AGENT,
                            errorCode = "CAPABILITY_PREFLIGHT",
                            message = preflight.summary(),
                        )
                    } else {
                        val capabilitySummary = capability.summary()
                        val agentResult = agentSession.sendIsolated(
                            userText = prompt,
                            skills = agentSkillStore.relevant(prompt),
                            verifyPolicy = AgentVerificationPolicy(
                                enabled = apiKeyStore.agentAutoVerifyEnabled,
                                maxPerRun = apiKeyStore.agentMaxAutoVerifies,
                            ),
                            capabilitySummary = capabilitySummary,
                            onSensitiveAction = {
                                apiKeyStore.agentAutoConfirmSensitiveActions
                            },
                        )
                        if (agentResult.ok) {
                            agentSkillStore.recordFromTurn(
                                text = prompt,
                                result = agentResult,
                                capabilitySummary = capabilitySummary,
                            )
                        }
                        when {
                            !agentResult.ok -> ExecutionResult.failure(
                                mode = ExecutionMode.AGENT,
                                errorCode = "AGENT_FAILED",
                                message = agentResult.message,
                            )
                            agentResult.state == AgentRunState.WAITING_CONFIRM -> ExecutionResult(
                                status = ExecutionStatus.WAITING_HUMAN,
                                usedMode = ExecutionMode.AGENT,
                                errorCode = "WAITING_HUMAN",
                                message = agentResult.message,
                            )
                            else -> ExecutionResult.success(ExecutionMode.AGENT).copy(message = agentResult.message)
                        }
                    }
                } else {
                    executionEngine.execute(
                        ExecutionRequest(
                            task = task,
                            requestedMode = requestedMode,
                        ),
                    )
                }
            }.getOrElse { e ->
                ExecutionResult.failure(
                    mode = requestedMode,
                    errorCode = "EXECUTION_EXCEPTION",
                    message = e.message,
                )
            }
            executionLogRepository.add(
                ExecutionLog(
                    taskId = task.id,
                    scheduledAtEpochMillis = startedAt,
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = System.currentTimeMillis(),
                    status = result.status,
                    executionMode = result.usedMode,
                    requestedMode = requestedMode,
                    verified = result.verified,
                    errorCode = result.errorCode,
                    message = result.message,
                    agentSessionId = _selectedAgentSessionId.value,
                ),
            )
            recordTaskEvent(
                taskId = task.id,
                eventType = "RUN",
                rawText = task.rawText,
                summary = "立即执行：${result.status}",
                now = System.currentTimeMillis(),
            )
            if (task.schedule.type == ScheduleSpec.ScheduleType.ONCE &&
                (result.status == ExecutionStatus.SUCCESS || result.status == ExecutionStatus.FALLBACK)
            ) {
                taskRepository.setEnabled(task.id, false)
                runCatching { taskScheduler.cancel(task.id) }
            }
            _uiState.update {
                it.copy(
                    parseMessage = "立即执行：${result.status}${result.message?.let { msg -> " - $msg" } ?: ""}",
                )
            }
        }
    }

    fun addWifiTrigger(name: String, ssid: String, targetPackage: String, tapTarget: String? = null, inputText: String? = null) {
        val trimmedName = name.trim().ifBlank { "到 $ssid 打开 $targetPackage" }
        val trimmedSsid = ssid.trim()
        val trimmedPackage = targetPackage.trim()
        if (trimmedSsid.isBlank() || trimmedPackage.isBlank()) {
            _uiState.update { it.copy(parseMessage = "请填写 Wi-Fi 名称和目标包名") }
            return
        }
        val now = System.currentTimeMillis()
        val hasUiAction = !tapTarget.isNullOrBlank() || !inputText.isNullOrBlank()
        viewModelScope.launch {
            val id = triggerRuleRepository.save(
                TriggerRule(
                    name = trimmedName,
                    condition = TriggerCondition(
                        type = TriggerCondition.TriggerType.WIFI,
                        wifiSsid = trimmedSsid,
                    ),
                    action = TriggerAction(
                        type = if (hasUiAction) ActionType.UI_ACTION else ActionType.OPEN_APP,
                        targetPackage = trimmedPackage,
                        tapTarget = tapTarget,
                        inputText = inputText,
                    ),
                    verify = VerifySpec(VerifySpec.VerifyType.FOREGROUND, expectedPackage = trimmedPackage),
                    fallback = FallbackSpec(notifyOnFailure = true),
                    enabled = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            recordTaskEvent(
                taskId = id,
                eventType = "TRIGGER_CREATE",
                rawText = trimmedName,
                summary = "创建 Wi-Fi 触发器：$trimmedSsid",
                now = now,
            )
            _uiState.update { it.copy(parseMessage = "已创建 Wi-Fi 触发器：$trimmedName") }
        }
    }

    fun addBatteryTrigger(name: String, level: Int, targetPackage: String, tapTarget: String? = null, inputText: String? = null) {
        val trimmedName = name.trim().ifBlank { "低电量 $level% 打开 $targetPackage" }
        val trimmedPackage = targetPackage.trim()
        if (level !in 1..100 || trimmedPackage.isBlank()) {
            _uiState.update { it.copy(parseMessage = "请填写 1-100 的电量和目标包名") }
            return
        }
        val now = System.currentTimeMillis()
        val hasUiAction = !tapTarget.isNullOrBlank() || !inputText.isNullOrBlank()
        viewModelScope.launch {
            val id = triggerRuleRepository.save(
                TriggerRule(
                    name = trimmedName,
                    condition = TriggerCondition(
                        type = TriggerCondition.TriggerType.BATTERY,
                        batteryState = TriggerCondition.BatteryState.LOW,
                        batteryLevel = level,
                    ),
                    action = TriggerAction(
                        type = if (hasUiAction) ActionType.UI_ACTION else ActionType.OPEN_APP,
                        targetPackage = trimmedPackage,
                        tapTarget = tapTarget,
                        inputText = inputText,
                    ),
                    verify = VerifySpec(VerifySpec.VerifyType.FOREGROUND, expectedPackage = trimmedPackage),
                    fallback = FallbackSpec(notifyOnFailure = true),
                    enabled = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            recordTaskEvent(
                taskId = id,
                eventType = "TRIGGER_CREATE",
                rawText = trimmedName,
                summary = "创建低电量触发器：$level%",
                now = now,
            )
            _uiState.update { it.copy(parseMessage = "已创建低电量触发器：$trimmedName") }
        }
    }

    fun addLocationTrigger(name: String, latitude: Double, longitude: Double, radiusMeters: Int, targetPackage: String, tapTarget: String? = null, inputText: String? = null) {
        val trimmedName = name.trim().ifBlank { "到位置打开 $targetPackage" }
        val trimmedPackage = targetPackage.trim()
        if (trimmedPackage.isBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || radiusMeters !in 10..5000) {
            _uiState.update { it.copy(parseMessage = "请填写有效的位置参数和目标包名") }
            return
        }
        val now = System.currentTimeMillis()
        val hasUiAction = !tapTarget.isNullOrBlank() || !inputText.isNullOrBlank()
        val action = TriggerAction(
            type = if (hasUiAction) ActionType.UI_ACTION else ActionType.OPEN_APP,
            targetPackage = trimmedPackage,
            tapTarget = tapTarget,
            inputText = inputText,
        )
        viewModelScope.launch {
            val id = triggerRuleRepository.save(
                TriggerRule(
                    name = trimmedName,
                    condition = TriggerCondition(
                        type = TriggerCondition.TriggerType.LOCATION,
                        latitude = latitude,
                        longitude = longitude,
                        radiusMeters = radiusMeters,
                    ),
                    action = action,
                    verify = VerifySpec(VerifySpec.VerifyType.FOREGROUND, expectedPackage = trimmedPackage),
                    fallback = FallbackSpec(notifyOnFailure = true),
                    enabled = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            triggerRuleScheduler.schedule(
                TriggerRule(
                    id = id,
                    name = trimmedName,
                    condition = TriggerCondition(
                        type = TriggerCondition.TriggerType.LOCATION,
                        latitude = latitude,
                        longitude = longitude,
                        radiusMeters = radiusMeters,
                    ),
                    action = action,
                    verify = VerifySpec(VerifySpec.VerifyType.FOREGROUND, expectedPackage = trimmedPackage),
                    fallback = FallbackSpec(notifyOnFailure = true),
                    enabled = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            recordTaskEvent(
                taskId = id,
                eventType = "TRIGGER_CREATE",
                rawText = trimmedName,
                summary = "创建位置触发器：$latitude,$longitude",
                now = now,
            )
            _uiState.update { it.copy(parseMessage = "已创建位置触发器：$trimmedName") }
        }
    }

    fun deleteTriggerRule(rule: TriggerRule) {
        viewModelScope.launch {
            recordTaskEvent(
                taskId = rule.id,
                eventType = "TRIGGER_DELETE",
                rawText = rule.name,
                summary = "删除触发器：${rule.name}",
                now = System.currentTimeMillis(),
            )
            triggerRuleScheduler.cancel(rule)
            triggerRuleRepository.delete(rule.id)
        }
    }

    fun toggleTriggerRule(rule: TriggerRule) {
        viewModelScope.launch {
            val newEnabled = !rule.enabled
            triggerRuleRepository.setEnabled(rule.id, newEnabled)
            if (newEnabled) {
                triggerRuleScheduler.schedule(rule.copy(enabled = true))
            } else {
                triggerRuleScheduler.cancel(rule)
            }
            recordTaskEvent(
                taskId = rule.id,
                eventType = if (newEnabled) "TRIGGER_ENABLE" else "TRIGGER_DISABLE",
                rawText = rule.name,
                summary = "${if (newEnabled) "启用" else "停用"}触发器：${rule.name}",
                now = System.currentTimeMillis(),
            )
        }
    }

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
        val text = _uiState.value.input.trim()
        if (text.isBlank()) return
        if (apiKeyStore.deepSeekApiKey.isNotBlank()) {
            sendAgentMessage(text)
        } else {
            parse()
        }
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
            if (_agentVoiceAutoSend.value && apiKeyStore.deepSeekApiKey.isNotBlank()) {
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
                if (_agentTtsEnabled.value && result.message.isNotBlank()) {
                    ttsSpeaker.speak(result.message)
                }
            } finally {
                _isAgentBusy.value = false
            }
        }
    }

    private suspend fun recordTaskEvent(taskId: Long?, eventType: String, rawText: String?, summary: String, now: Long) {
        agentHistoryRepository.addTaskEvent(
            TaskEventEntity(
                taskId = taskId,
                agentSessionId = _selectedAgentSessionId.value,
                eventType = eventType,
                rawText = rawText,
                summary = summary,
                createdAtEpochMillis = now,
            ),
        )
    }

    fun summarizeLogs() {
        val logs = recentLogs.value
        if (logs.isEmpty()) return
        _uiState.update { it.copy(isSummarizing = true, logSummary = null) }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                deepSeekNlpParser.summarize(logs)
            }
            _uiState.update {
                it.copy(
                    isSummarizing = false,
                    logSummary = summary ?: "总结失败：${deepSeekNlpParser.lastError ?: "未知错误"}",
                    lastAiError = deepSeekNlpParser.lastError,
                )
            }
        }
    }

    fun importTemplate(name: String, description: String, category: String, configJson: String) {
        val trimmedName = name.trim().ifBlank { configJson.take(12) }
        val trimmedConfig = configJson.trim()
        if (trimmedConfig.isBlank()) return
        viewModelScope.launch {
            templateRepository.add(
                Template(
                    name = trimmedName,
                    description = description.trim(),
                    category = category.trim().ifBlank { "导入" },
                    configJson = trimmedConfig,
                ),
            )
        }
    }

    fun saveCurrentAsTemplate(name: String) {
        val draft = _uiState.value.parsedDraft
        val rawText = draft?.rawText?.takeIf { it.isNotBlank() } ?: _uiState.value.input
        if (rawText.isBlank()) {
            _uiState.update { it.copy(parseMessage = "请先输入或生成任务，再保存为模板") }
            return
        }
        val finalName = name.trim().ifBlank { rawText.take(12) }
        viewModelScope.launch {
            templateRepository.add(
                Template(
                    name = finalName,
                    description = "自定义模板",
                    category = "自定义",
                    configJson = rawText,
                ),
            )
            _uiState.update { it.copy(parseMessage = "已保存为模板：$finalName") }
        }
    }

    fun deleteTemplate(template: Template) {
        viewModelScope.launch {
            templateRepository.delete(template.id)
        }
    }

    fun onTemplateSelected(template: Template) {
        _uiState.update {
            it.copy(
                input = template.configJson,
                parsedDraft = null,
                parseMessage = null,
                manualPackage = "",
                manualDeepLink = "",
            )
        }
        viewModelScope.launch {
            templateRepository.incrementUsage(template.id)
        }
    }

    private fun learnUserAlias(rawText: String, packageName: String) {
        val alias = listOf("打开", "启动", "进入")
            .mapNotNull { rawText.substringAfterLast(it, "").takeIf(String::isNotBlank) }
            .minByOrNull { it.length }
            ?.trim()
            ?.trimEnd('吧', '啊', '呀', '哦')
            ?.takeIf { it.isNotBlank() }
            ?: return
        appAliasResolver.addUserAlias(alias, packageName)
        userAliasRegistry.add(alias, packageName)
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

    fun setAgentAutoVerifyEnabled(enabled: Boolean) {
        apiKeyStore.agentAutoVerifyEnabled = enabled
        _agentAutoVerifyEnabled.value = enabled
    }

    fun setAgentMaxAutoVerifies(value: Int) {
        apiKeyStore.agentMaxAutoVerifies = value
        _agentMaxAutoVerifies.value = value
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

    fun saveRemoteNode(node: RemoteNode) {
        viewModelScope.launch {
            runCatching { remoteNodeRepository.saveNode(node) }
        }
    }

    fun deleteRemoteNode(id: Long) {
        viewModelScope.launch {
            runCatching { remoteNodeRepository.deleteNode(id) }
        }
    }

    fun setRemoteNodeEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { remoteNodeRepository.setEnabled(id, enabled) }
        }
    }

    fun setRemoteNodePaused(id: Long, paused: Boolean) {
        viewModelScope.launch {
            runCatching { remoteNodeRepository.setPaused(id, paused) }
        }
    }

    suspend fun getRemoteNode(id: Long): RemoteNode? = remoteNodeRepository.getNode(id)

    suspend fun refreshRemoteNode(id: Long) {
        val node = remoteNodeRepository.getNode(id) ?: return
        runCatching {
            val monitor = com.voiceconfig.app.remote.RemoteMonitorClient(remoteNodeRepository)
            val snapshot = monitor.snapshot(node.name)
            remoteNodeRepository.markSeen(
                id = id,
                status = "online",
                error = null,
            )
            @Suppress("UNUSED_EXPRESSION")
            snapshot
        }
    }

    fun executeRemoteCommand(node: RemoteNode, command: String) {
        viewModelScope.launch {
            _remoteCommandResult.value = null
            _remoteCommandResult.value = runCatching {
                remoteCommandClient.execute(node.name, command)
            }.getOrElse { e ->
                RemoteCommandResult(
                    ok = false,
                    command = command,
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    error = e.message ?: "执行失败",
                )
            }
        }
    }

    fun clearRemoteCommandResult() {
        _remoteCommandResult.value = null
    }

    fun getSshCredential(host: String, port: Int = 22): StoredSshCredential? =
        sshCredentialStore.load(host, port)

    fun clearSshHostKey(config: SshConfig) {
        sshHostKeyStore.clear(config.host, config.port)
    }

    fun getSshHostKey(host: String, port: Int = 22): String? =
        sshHostKeyStore.get(host, port)

    fun refreshSshKeys() {
        _sshKeys.value = sshKeyStore.list()
    }

    fun generateSshKey(type: String, name: String) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) {
                sshKeyManager.generate(type, name)
            }
            if (key != null) _sshKeys.value = sshKeyStore.list()
        }
    }

    fun deleteSshKey(id: String) {
        sshKeyStore.delete(id)
        _sshKeys.value = sshKeyStore.list()
    }

    fun renameSshKey(id: String, name: String) {
        sshKeyStore.rename(id, name)
        _sshKeys.value = sshKeyStore.list()
    }

    fun getSshKey(id: String): SshManagedKey? = sshKeyStore.get(id)

    fun executeSsh(config: SshConfig, command: String) {
        viewModelScope.launch {
            _sshResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                // 首次连接：先用无害命令换取指纹，等待用户确认后再执行真正命令。
                val probe = sshClient.execute(config, "true", 15_000)
                val fp = probe.hostKeyFingerprint
                if (fp == null) {
                    _sshResult.value = probe
                    return@launch
                }
                _pendingSshHostKey.value = SshPendingTrust(
                    config = config,
                    fingerprint = fp,
                    hostKeyType = probe.hostKeyType,
                    hostKeyBase64 = probe.hostKeyBase64,
                    command = command,
                )
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val result = sshClient.execute(effective, command)
            sshAuditStore.record(
                config.host, config.port, config.username,
                "exec", command, result.exitCode == 0,
            )
            _sshResult.value = result
        }
    }

    fun installNodeViaSsh(config: SshConfig, bindMode: String = "tailscale") {
        viewModelScope.launch {
            _sshBootstrapResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                val probe = sshClient.execute(config, "true", 15_000)
                val fp = probe.hostKeyFingerprint
                if (fp == null) {
                    _sshBootstrapResult.value = SshBootstrapResult(
                        false,
                        "无法获取主机指纹：${probe.stderr.ifBlank { probe.stdout }}",
                    )
                    return@launch
                }
                _pendingSshHostKey.value = SshPendingTrust(
                    config = config,
                    fingerprint = fp,
                    hostKeyType = probe.hostKeyType,
                    hostKeyBase64 = probe.hostKeyBase64,
                    installBindMode = bindMode,
                )
                return@launch
            }
            runSshInstallDirect(config.copy(hostKeyFingerprint = trusted), bindMode)
        }
    }

    fun confirmSshHostKey(trusted: Boolean) {
        val pending = _pendingSshHostKey.value ?: return
        _pendingSshHostKey.value = null
        if (!trusted) return
        viewModelScope.launch {
            sshHostKeyStore.save(pending.config.host, pending.config.port, pending.fingerprint)
            if (pending.hostKeyType != null && pending.hostKeyBase64 != null) {
                sshHostKeyStore.saveHostKey(
                    pending.config.host,
                    pending.config.port,
                    pending.hostKeyType,
                    pending.hostKeyBase64,
                    pending.fingerprint,
                )
            }
            val effective = pending.config.copy(hostKeyFingerprint = pending.fingerprint)
            if (pending.command != null) {
                _sshResult.value = null
                val execResult = sshClient.execute(effective, pending.command)
                sshAuditStore.record(
                    pending.config.host, pending.config.port, pending.config.username,
                    "exec", pending.command, execResult.exitCode == 0,
                )
                _sshResult.value = execResult
            } else if (pending.installBindMode != null) {
                _sshBootstrapResult.value = null
                runSshInstallDirect(effective, pending.installBindMode)
            }
        }
    }

    private suspend fun runSshInstallDirect(config: SshConfig, bindMode: String) {
        val result = sshBootstrapClient.install(config, bindMode)
        if (result.ok && result.token != null) {
            remoteNodeRepository.saveNode(
                RemoteNode(
                    nodeId = result.nodeId ?: ("node_ssh_" + System.currentTimeMillis()),
                    name = config.host,
                    host = config.host,
                    port = 8787,
                    scheme = "http",
                    token = result.token,
                    allowedCommands = listOf(
                        "hostname", "uname", "uptime", "free", "df", "ps",
                        "os_release", "network", "tailscale",
                    ),
                    enabled = true,
                    paused = false,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        sshAuditStore.record(
            config.host, config.port, config.username,
            "bootstrap", bindMode, result.ok,
        )
        _sshBootstrapResult.value = result
    }

    fun listSshFiles(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            _sshFileEntries.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val files = sshClient.listFiles(effective, path)
            if (files == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "列目录失败（路径不存在或不可读）")
                sshAuditStore.record(config.host, config.port, config.username, "list", path, false)
            } else {
                _sshFileEntries.value = files
                _sshFileResult.value = SshFileResult(true, path, "共 ${files.size} 项")
                sshAuditStore.record(config.host, config.port, config.username, "list", path, true)
            }
        }
    }

    fun readSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val content = sshClient.download(effective, path)
            if (content == null) {
                sshAuditStore.record(config.host, config.port, config.username, "read", path, false)
                _sshFileResult.value = SshFileResult(false, path, "", "读取失败（文件不存在或不可读）")
            } else {
                sshAuditStore.record(config.host, config.port, config.username, "read", path, true)
                _sshFileResult.value = SshFileResult(true, path, content)
            }
        }
    }

    fun writeSshFile(config: SshConfig, path: String, content: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.upload(effective, path, content)
            sshAuditStore.record(config.host, config.port, config.username, "write", path, ok)
            _sshFileResult.value = if (ok) {
                SshFileResult(true, path, "已写入 $path")
            } else {
                SshFileResult(false, path, "", "写入失败（请检查路径权限）")
            }
        }
    }

    fun clearSshFileResult() {
        _sshFileResult.value = null
        _sshFileEntries.value = null
    }

    fun mkdirSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.mkdir(effective, path)
            sshAuditStore.record(config.host, config.port, config.username, "mkdir", path, ok)
            _sshFileResult.value = if (ok) SshFileResult(true, path, "已创建目录 $path")
            else SshFileResult(false, path, "", "创建目录失败")
        }
    }

    fun deleteSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.delete(effective, path)
            sshAuditStore.record(config.host, config.port, config.username, "delete", path, ok)
            _sshFileResult.value = if (ok) SshFileResult(true, path, "已删除 $path")
            else SshFileResult(false, path, "", "删除失败（目录非空或权限不足）")
        }
    }

    fun renameSshFile(config: SshConfig, oldPath: String, newPath: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, oldPath, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.rename(effective, oldPath, newPath)
            sshAuditStore.record(config.host, config.port, config.username, "rename", "$oldPath -> $newPath", ok)
            _sshFileResult.value = if (ok) SshFileResult(true, newPath, "已重命名为 $newPath")
            else SshFileResult(false, oldPath, "", "重命名失败")
        }
    }

    private fun ensureTrustedOrNull(config: SshConfig): SshConfig? {
        val trusted = sshHostKeyStore.get(config.host, config.port) ?: return null
        return config.copy(hostKeyFingerprint = trusted)
    }

    fun listSshServices(config: SshConfig) {
        viewModelScope.launch {
            _sshServiceResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshServiceResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "systemctl --no-pager --type=service --all list-units", 30_000)
            sshAuditStore.record(config.host, config.port, config.username, "service_list", "systemctl list-units", result.exitCode == 0)
            _sshServiceResult.value = result
        }
    }

    fun runSshService(config: SshConfig, command: String, detail: String) {
        viewModelScope.launch {
            _sshServiceResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshServiceResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, command, 30_000)
            sshAuditStore.record(config.host, config.port, config.username, "service", detail, result.exitCode == 0)
            _sshServiceResult.value = result
        }
    }

    fun startSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl start " + shellQuote(name.trim()), "start $name")
    }

    fun stopSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl stop " + shellQuote(name.trim()), "stop $name")
    }

    fun restartSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl restart " + shellQuote(name.trim()), "restart $name")
    }

    fun statusSshService(config: SshConfig, name: String) {
        runSshService(config, "systemctl status " + shellQuote(name.trim()) + " --no-pager -l", "status $name")
    }

    fun logsSshService(config: SshConfig, name: String) {
        runSshService(config, "journalctl -u " + shellQuote(name.trim()) + " -n 200 --no-pager", "logs $name")
    }

    fun readSshNodeAudit(config: SshConfig) {
        viewModelScope.launch {
            _sshNodeLogResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshNodeLogResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "tail -n 300 ~/.voiceconfig-node/audit.jsonl", 30_000)
            _sshNodeLogResult.value = result
        }
    }

    fun readSshNodeLog(config: SshConfig) {
        viewModelScope.launch {
            _sshNodeLogResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshNodeLogResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "tail -n 300 ~/.voiceconfig-node/node.log 2>/dev/null || echo '(no node.log)'", 30_000)
            _sshNodeLogResult.value = result
        }
    }

    fun clearSshServiceResult() {
        _sshServiceResult.value = null
    }

    fun clearSshNodeLogResult() {
        _sshNodeLogResult.value = null
    }

    fun startSshShell(config: SshConfig) {
        viewModelScope.launch {
            closeSshShell()
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshShellError.value = "请先在 SSH 命令终端确认主机指纹"
                _sshShellRunning.value = false
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            _sshShellOutput.value = ""
            _sshShellError.value = null
            _sshShellRunning.value = true
            sshShellHostHost = config.host
            sshShellHostPort = config.port
            sshShellHostUser = config.username
            sshShellHandle = sshClient.openShell(
                effective,
                onOutput = { text ->
                    _sshShellOutput.value = (_sshShellOutput.value + text).takeLast(200_000)
                    sshAuditStore.record(
                        config.host, config.port, config.username,
                        "shell_out", text.take(200), true,
                    )
                },
                onClosed = {
                    _sshShellRunning.value = false
                    sshShellHandle = null
                    sshShellHostHost = null
                    sshShellHostPort = null
                    sshShellHostUser = null
                },
            )
            if (sshShellHandle == null) {
                _sshShellRunning.value = false
                _sshShellError.value = "无法打开交互式终端"
                sshAuditStore.record(config.host, config.port, config.username, "shell_start", "failed", false)
            } else {
                sshAuditStore.record(config.host, config.port, config.username, "shell_start", "opened", true)
            }
        }
    }

    fun sendSshShellCommand(command: String) {
        if (command.isBlank()) return
        val handle = sshShellHandle ?: return
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                handle.send(command)
            }
            // 终端命令不记录敏感内容太长；只记录前 200 字符。
            sshAuditStore.record(
                sshShellHostHost ?: "",
                sshShellHostPort ?: 22,
                sshShellHostUser ?: "",
                "shell_cmd",
                command.take(200),
                sent,
            )
            if (!sent) {
                _sshShellError.value = "发送命令失败：" + (handle.lastSendError ?: "终端连接可能已断开")
            }
        }
    }

    fun resizeSshShell(width: Int, height: Int) {
        val handle = sshShellHandle ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                handle.resize(width, height)
            }
        }
    }

    fun closeSshShell() {
        val handle = sshShellHandle
        sshShellHandle = null
        _sshShellRunning.value = false
        sshShellHostHost = null
        sshShellHostPort = null
        sshShellHostUser = null
        if (handle != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    handle.close()
                }
            }
        }
    }

    fun clearSshShellOutput() {
        _sshShellOutput.value = ""
    }

    fun clearSshShellError() {
        _sshShellError.value = null
    }

    fun loadSshAudit() {
        viewModelScope.launch {
            _sshAuditText.value = withContext(Dispatchers.IO) {
                sshAuditStore.readRecent(200)
            }
        }
    }

    fun clearSshAudit() {
        _sshAuditText.value = ""
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\''") + "'"

    fun clearSshResult() {
        _sshResult.value = null
    }

    fun clearSshBootstrapResult() {
        _sshBootstrapResult.value = null
    }

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

    fun setParseMessage(message: String) {
        _uiState.update { it.copy(parseMessage = message) }
    }

    fun clearResult() {
        _uiState.update { it.copy(parsedDraft = null, parseMessage = null) }
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

    private suspend fun restoreSchedules() {
        val now = System.currentTimeMillis()
        taskRepository.getEnabledTasks().forEach { task ->
            val nextRun = nextRunCalculator.nextRunAfter(task.schedule)
                ?.atZone(ZoneId.systemDefault())
                ?.toInstant()
                ?.toEpochMilli()
            if (nextRun != null) {
                if (nextRun != task.nextRunAtEpochMillis) {
                    taskRepository.saveTask(
                        task.copy(
                            nextRunAtEpochMillis = nextRun,
                            updatedAtEpochMillis = now,
                        ),
                    )
                }
                taskScheduler.schedule(task.copy(nextRunAtEpochMillis = nextRun))
            }
        }
        triggerRuleScheduler.restoreAll(triggerRuleRepository.getEnabled())
    }

    private suspend fun migrateLegacyTasks() {
        val tasks = taskRepository.observeTasks().first()
        tasks.forEach { task ->
            if (task.targetPackage.isNullOrBlank()) return@forEach
            val alias = extractAppAlias(task.rawText) ?: return@forEach
            val resolved = appAliasResolver.resolve(alias) ?: return@forEach
            val newPackage = resolved.packageName
            val newActivity = resolved.activityName
            val packageChanged = newPackage != task.targetPackage
            val activityChanged = newActivity != null && newActivity != task.targetActivity
            if (packageChanged || activityChanged) {
                taskRepository.saveTask(
                    task.copy(
                        targetPackage = newPackage,
                        targetActivity = newActivity ?: task.targetActivity,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun extractAppAlias(rawText: String): String? =
        Regex("(?:打开|启动|进入)\\s*(.+)").find(rawText)?.groupValues?.get(1)
            ?.replace("每天", "")
            ?.replace("每日", "")
            ?.replace("工作日", "")
            ?.replace("明天", "")
            ?.replace("早上", "")
            ?.replace("上午", "")
            ?.replace("中午", "")
            ?.replace("下午", "")
            ?.replace("晚上", "")
            ?.trim()
            ?.trimEnd('吧', '啊', '呀', '哦')
            ?.takeIf { it.isNotBlank() }

    private suspend fun seedTemplatesIfEmpty() {
        if (templateRepository.observeTemplates().first().isNotEmpty()) return
        defaultTemplates().forEach { templateRepository.add(it) }
    }

    private fun defaultTemplates(): List<Template> = listOf(
        Template(
            name = "喝水提醒",
            description = "每天上午 10:00 提醒喝水",
            category = "健康",
            configJson = "每天上午10点提醒我喝水",
        ),
        Template(
            name = "打开瑞幸咖啡",
            description = "每天 9:00 打开瑞幸咖啡",
            category = "生活",
            configJson = "每天上午9点打开瑞幸咖啡",
        ),
        Template(
            name = "企业微信定时打开",
            description = "工作场景：每天 08:00 自动打开企业微信",
            category = "工作",
            configJson = "每天早上8点打开企业微信",
        ),
        Template(
            name = "午休提醒",
            description = "每天 12:00 提醒午休",
            category = "生活",
            configJson = "每天中午12点提醒我午休",
        ),
        Template(
            name = "运动提醒",
            description = "每周一 19:00 提醒运动",
            category = "健康",
            configJson = "每周一晚上7点提醒我运动",
        ),
    )
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
