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
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.SensitiveActionRequest
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.ai.DeepSeekNlpParser
import com.voiceconfig.app.scheduler.TriggerRuleScheduler
import com.voiceconfig.app.di.UserAliasRegistry
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.data.local.repository.AgentHistoryRepository
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import com.voiceconfig.data.local.repository.ExecutionLogRepository
import com.voiceconfig.data.local.repository.TaskRepository
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
    private val executionLogRepository: ExecutionLogRepository,
    private val userAliasRegistry: UserAliasRegistry,
    private val executionEngine: ExecutionEngine,
    private val apiKeyStore: ApiKeyStore,
    private val deepSeekNlpParser: DeepSeekNlpParser,
    private val aiDebugLogRepository: AiDebugLogRepository,
    private val agentHistoryRepository: AgentHistoryRepository,
    private val agentSession: AgentSession,
) : ViewModel() {

    init {
        viewModelScope.launch {
            seedTemplatesIfEmpty()
            migrateLegacyTasks()
            restoreSchedules()
        }
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

    val agentSessions: StateFlow<List<AgentSessionEntity>> = agentHistoryRepository.observeSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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

    private val _pendingAgentConfirmation = MutableStateFlow<PendingAgentConfirmation?>(null)
    val pendingAgentConfirmation: StateFlow<PendingAgentConfirmation?> = _pendingAgentConfirmation.asStateFlow()

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
                        draft == null -> "未能理解，请换一种说法（$source）"
                        draft.schedule != null && draft.confidence < AUTO_CREATE_THRESHOLD ->
                            "已识别，但置信度较低，请确认后创建（$source）"
                        else -> "解析成功，请确认任务（$source）"
                    },
                    manualPackage = "",
                    manualDeepLink = "",
                    lastAiError = deepSeekNlpParser.lastError,
                    lastAiRawResponse = deepSeekNlpParser.lastRawResponse,
                    lastAiParseError = deepSeekNlpParser.lastParseError,
                )
            }
            if (draft != null &&
                draft.schedule != null &&
                draft.confidence >= AUTO_CREATE_THRESHOLD
            ) {
                confirmCreate()
            }
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
                title = draft.rawText.take(30),
                schedule = schedule,
                actionType = draft.actionType,
                targetPackage = effectiveTargetPackage,
                targetActivity = draft.targetActivity,
                deepLink = effectiveDeepLink,
                executionMode = draft.executionMode,
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
                executionEngine.execute(
                    ExecutionRequest(
                        task = task,
                        requestedMode = requestedMode,
                    ),
                )
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
            }
        }
    }

    fun selectAgentSession(sessionId: Long) {
        _selectedAgentSessionId.value = sessionId
        viewModelScope.launch {
            val messages = repairToolCallIds(agentHistoryRepository.getMessages(sessionId))
            agentSession.restore(messages.map { it.toAgentMessage() })
        }
    }

    fun clearSelectedAgentSession() {
        _selectedAgentSessionId.value = null
        agentSession.clear()
    }

    fun newAgentSession() {
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
                agentSession.clear()
            }
            agentHistoryRepository.deleteSession(sessionId)
        }
    }

    fun clearAgentSession(sessionId: Long) {
        viewModelScope.launch {
            agentHistoryRepository.clearMessages(sessionId)
            if (_selectedAgentSessionId.value == sessionId) {
                agentSession.clear()
            }
        }
    }

    fun stopAgent() {
        agentSession.cancel()
        _agentStreamText.value = "正在停止..."
    }

    fun sendAgentMessage(text: String) {
        if (text.isBlank() || _isAgentBusy.value) return
        viewModelScope.launch {
            _isAgentBusy.value = true
            _agentStreamText.value = ""
            _agentReasoningText.value = ""
            try {
                val now = System.currentTimeMillis()
                var sessionId = _selectedAgentSessionId.value
                if (sessionId == null) {
                    sessionId = agentHistoryRepository.createSession(text.take(24), now)
                    _selectedAgentSessionId.value = sessionId
                }
                val targetSessionId: Long = sessionId
                val result = agentSession.send(
                    text,
                    onSensitiveAction = { request -> confirmSensitiveAction(request) },
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
                                    createdAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                )
                val sessionTitle = agentHistoryRepository.getSession(sessionId)?.title
                    ?.takeIf { it.isNotBlank() && it != "新会话" }
                    ?: (result.history.firstOrNull()?.content?.take(24) ?: text.take(24))
                agentHistoryRepository.updateSession(
                    sessionId = sessionId,
                    title = sessionTitle,
                    now = System.currentTimeMillis(),
                    messageCount = result.history.count { it.imageBase64 == null },
                )
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
            name = "上班打卡提醒",
            description = "每个工作日早上 8:25 提醒打卡",
            category = "工作",
            configJson = "工作日8点25分提醒我打卡",
        ),
        Template(
            name = "打开企业微信",
            description = "每天 8:25 打开企业微信",
            category = "工作",
            configJson = "每天8点25分打开企业微信",
        ),
        Template(
            name = "午休提醒",
            description = "每天 12:00 提醒午休",
            category = "生活",
            configJson = "每天中午12点提醒我午休",
        ),
        Template(
            name = "每周例会",
            description = "每周一 9:30 打开钉钉",
            category = "工作",
            configJson = "每周一9点30打开钉钉",
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
