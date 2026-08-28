package com.voiceconfig.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.ShizukuCommandRunner
import com.voiceconfig.app.agent.TaskPlan
import com.voiceconfig.app.ai.InstalledAppProvider
import com.voiceconfig.app.ai.AsrEngineStatus
import com.voiceconfig.app.ai.LocalAsrManager
import com.voiceconfig.app.service.AccessibilityKeepAlive
import com.voiceconfig.app.service.VoiceConfigService
import com.voiceconfig.app.ui.theme.SuccessGreen
import com.voiceconfig.app.ui.AgentNavigation
import com.voiceconfig.app.ui.AppDestination
import com.voiceconfig.app.ui.AgentPage
import com.voiceconfig.app.ui.HomeAssistantPage
import com.voiceconfig.app.ui.OnboardingScreen
import com.voiceconfig.app.ui.ShoppingResearchPage
import com.voiceconfig.app.ui.theme.VoiceConfigTheme
import com.voiceconfig.app.ui.theme.WarningOrange
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.model.Template
import com.voiceconfig.core.model.TaskDraft
import com.voiceconfig.core.model.ScheduleSpec
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val templates by viewModel.templates.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()
    val deepSeekApiKey by viewModel.deepSeekApiKey.collectAsState()
    val agentDeepSeekThinkingEnabled by viewModel.agentDeepSeekThinkingEnabled.collectAsState()
    val agentDeepSeekReasoningEffort by viewModel.agentDeepSeekReasoningEffort.collectAsState()
    val agentSkills by viewModel.agentSkills.collectAsState()
    val pendingAgentConfirmation by viewModel.pendingAgentConfirmation.collectAsState()
    val aiDebugLogs by viewModel.aiDebugLogs.collectAsState()
    val triggerRules by viewModel.triggerRules.collectAsState()
    val agentSessions by viewModel.agentSessions.collectAsState()
    val agentRunRecords by viewModel.agentRunRecords.collectAsState()
    val agentRunDetail by viewModel.agentRunDetail.collectAsState()
    val agentMessages by viewModel.agentMessages.collectAsState()
    val agentSteps by viewModel.agentSteps.collectAsState()
    val canResumeTask by viewModel.canResumeTask.collectAsState()
    val activeTaskPlans by viewModel.activeTaskPlans.collectAsState()
    val lastAgentRunDurationMs by viewModel.lastAgentRunDurationMs.collectAsState()
    val taskEvents by viewModel.taskEvents.collectAsState()
    val selectedAgentSessionId by viewModel.selectedAgentSessionId.collectAsState()
    val isAgentBusy by viewModel.isAgentBusy.collectAsState()
    val agentStreamText by viewModel.agentStreamText.collectAsState()
    val agentReasoningText by viewModel.agentReasoningText.collectAsState()
    val agentDraft by viewModel.agentDraft.collectAsState()
    val agentVoiceAutoSend by viewModel.agentVoiceAutoSend.collectAsState()
    val agentTtsEnabled by viewModel.agentTtsEnabled.collectAsState()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()
    val shoppingItems by viewModel.shoppingItems.collectAsState()
    val homeAssistantBaseUrl by viewModel.homeAssistantBaseUrl.collectAsState()
    val homeAssistantToken by viewModel.homeAssistantToken.collectAsState()
    val homeAssistantConfigured by viewModel.homeAssistantConfigured.collectAsState()
    val homeAssistantDevices by viewModel.homeAssistantDevices.collectAsState()
    val homeAssistantTestMessage by viewModel.homeAssistantTestMessage.collectAsState()
    val homeAssistantControlMessage by viewModel.homeAssistantControlMessage.collectAsState()
    var showShoppingPage by remember { mutableStateOf(false) }
    var showHomeAssistantPage by remember { mutableStateOf(false) }
    var showAgentPage by remember { mutableStateOf(false) }
    var agentInitialTab by remember { mutableIntStateOf(0) }
    var agentTabIndex by remember { mutableIntStateOf(0) }
    var agentLogTaskId by remember { mutableStateOf<Long?>(null) }
    val debugCommand by AgentTestBridge.command.collectAsState()
    val debugHomeSpeech by AgentTestBridge.homeSpeech.collectAsState()
    var showCreatePanel by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.parseMessage) {
        val message = uiState.parseMessage
        if (!message.isNullOrBlank() && (
                message.startsWith("任务已创建") ||
                message.startsWith("已保存为模板") ||
                message.startsWith("已创建")
            )
        ) {
            showCreatePanel = false
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(debugHomeSpeech) {
        val speech = debugHomeSpeech ?: return@LaunchedEffect
        showCreatePanel = true
        viewModel.submitVoiceResult(
            text = speech.text,
            asrEngine = "debug-bridge",
            toAgent = false,
            autoParse = speech.parse,
        )
        AgentTestBridge.clearHomeSpeech()
    }
    LaunchedEffect(debugCommand) {
        val command = debugCommand ?: return@LaunchedEffect
        if (command.text.isNotBlank()) {
            if (command.newSession) {
                viewModel.clearSelectedAgentSession()
                agentTabIndex = AgentNavigation.TAB_CONVERSATION
            }
            viewModel.onAgentInputChange(command.text)
            if (command.send) {
                viewModel.sendAgentMessage(command.text.trim())
                viewModel.clearAgentDraft()
            }
        }
        AgentTestBridge.clear()
    }
    var micOffsetX by remember { mutableStateOf(0f) }
    var micOffsetY by remember { mutableStateOf(0f) }
    var triggerType by remember { mutableStateOf("wifi") }
    var triggerName by remember { mutableStateOf("") }
    var triggerSsid by remember { mutableStateOf("") }
    var triggerPackage by remember { mutableStateOf("") }
    var triggerLevel by remember { mutableStateOf(20) }
    var triggerLat by remember { mutableStateOf("31.2304") }
    var triggerLng by remember { mutableStateOf("121.4737") }
    var triggerRadius by remember { mutableStateOf("100") }
    var triggerTap by remember { mutableStateOf("") }
    var triggerInput by remember { mutableStateOf("") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户拒绝时通过权限体检页引导 */ }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户拒绝时提示到设置页开启 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val context = LocalContext.current
    val localAsrManager = (context as? MainActivity)?.localAsrManager
    val installedAppProvider = (context as? MainActivity)?.installedAppProvider
    val agentSession = (context as? MainActivity)?.agentSession
    val accessibilityKeepAlive = (context as? MainActivity)?.accessibilityKeepAlive
    var installedAppLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(installedAppProvider) {
        installedAppLabels = withContext(Dispatchers.Default) {
            installedAppProvider?.installedApps?.associate { it.packageName to it.label } ?: emptyMap()
        }
    }
    var asrSelectedId by remember { mutableStateOf(localAsrManager?.selectedModel()?.id ?: "") }
    var showExperimentalAsr by remember { mutableStateOf(false) }
    var asrDownloadingId by remember { mutableStateOf<String?>(null) }
    var asrDownloadProgress by remember { mutableStateOf(0f) }
    var asrErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        localAsrManager?.warmUp()
    }
    val voiceController = rememberVoiceInputController(
        context = context,
        localAsrManager = localAsrManager,
        viewModel = viewModel,
        uiParsing = uiState.isParsing,
        isAgentBusy = isAgentBusy,
    )
    val isListening = voiceController.isListening.value
    val isPreparing = voiceController.isPreparing.value
    val onMicClick = voiceController.onMicClick
    val onAgentMicClick: () -> Unit = {
        agentTabIndex = AgentNavigation.TAB_CONVERSATION
        voiceController.onAgentMicClick()
    }
    val onParseClick = voiceController.onParseClick

    var currentDestination by remember { mutableStateOf<AppDestination>(AppDestination.Conversation) }
    LaunchedEffect(currentDestination) {
        showAgentPage = currentDestination == AppDestination.Conversation
        if (showAgentPage) {
            viewModel.openAgentPage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = currentDestination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
            ) { destination ->
            when (destination) {
                    AppDestination.Automation -> MainScreenContent(
                        uiState = uiState,
                        deepSeekApiKey = deepSeekApiKey,
                        installedAppLabels = installedAppLabels,
                        isListening = isListening,
                        isPreparing = isPreparing,
                        onMicClick = onMicClick,
                        onOpenAiSettings = {
                            currentDestination = AppDestination.Profile
                        },
                        onOpenAgent = {
                            agentInitialTab = 0
                            agentTabIndex = 0
                            agentLogTaskId = null
                            currentDestination = AppDestination.Conversation
                        },
                        onCreateByAgent = {
                            viewModel.newAgentSession()
                            viewModel.onAgentInputChange("帮我创建一个自动化任务：")
                            currentDestination = AppDestination.Conversation
                            scope.launch {
                                snackbarHostState.showSnackbar("已进入对话，输入你的自动化需求")
                            }
                        },
                        onOpenAgentLogs = { task ->
                            currentDestination = AppDestination.Automation
                        },
                        onOpenAgentSession = { sessionId ->
                            viewModel.selectAgentSession(sessionId)
                            agentInitialTab = 0
                            agentTabIndex = 0
                            agentLogTaskId = null
                            currentDestination = AppDestination.Conversation
                        },
                        showCreatePanel = showCreatePanel,
                        onCreatePanelChange = { showCreatePanel = it },
                        tasks = tasks,
                        templates = templates,
                        recentLogs = recentLogs,
                        onInputChange = viewModel::onInputChange,
                        onManualPackageChange = viewModel::onManualPackageChange,
                        onManualDeepLinkChange = viewModel::onManualDeepLinkChange,
                        onParse = onParseClick,
                        onConfirmCreate = viewModel::confirmCreate,
                        onClearResult = viewModel::clearResult,
                        onToggleTask = viewModel::toggleTask,
                        onDeleteTask = viewModel::deleteTask,
                        onCopyTask = viewModel::copyTaskToInput,
                        onRunNow = viewModel::runNow,
                        onSummarizeLogs = viewModel::summarizeLogs,
                        onSaveTemplate = viewModel::saveCurrentAsTemplate,
                        onDeleteTemplate = viewModel::deleteTemplate,
                        onExportTemplates = {
                            val text = templates.joinToString("\n") { template ->
                                "${template.name}|${template.description}|${template.category}|${template.configJson}"
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "导出全部模板"))
                        },
                        onImportTemplates = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val clip = clipboard?.primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (!text.isNullOrBlank()) {
                                text.lineSequence().forEach { line ->
                                    val parts = line.split("|", limit = 4)
                                    if (parts.size == 4 && parts[3].isNotBlank()) {
                                        viewModel.importTemplate(
                                            name = parts[0],
                                            description = parts[1],
                                            category = parts[2],
                                            configJson = parts[3],
                                        )
                                    }
                                }
                            }
                        },
                        onTemplateSelected = viewModel::onTemplateSelected,
                    )
                    AppDestination.Conversation -> AgentPage(
                        initialTabIndex = agentInitialTab,
                        tabIndex = agentTabIndex,
                        onTabChange = { agentTabIndex = it },
                        sessions = agentSessions,
                        messages = agentMessages,
                        agentSteps = agentSteps,
                        lastRunDurationMs = lastAgentRunDurationMs,
                        agentSkills = agentSkills,
                        agentRunRecords = agentRunRecords,
                        agentRunDetail = agentRunDetail,
                        onSelectRun = viewModel::loadAgentRunDetail,
                        onEnableAccessibility = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        accessibilityKeepAlive?.ensureEnabled() == true
                                    }.getOrDefault(false)
                                }
                                if (ok) {
                                    snackbarHostState.showSnackbar("已通过 Shizuku 写入无障碍开关，请稍候连接")
                                } else {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        },
                        onOpenAccessibilitySettings = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        taskEvents = taskEvents,
                        recentLogs = recentLogs,
                        tasks = tasks,
                        selectedSessionId = selectedAgentSessionId,
                        isAgentBusy = isAgentBusy,
                        streamText = agentStreamText,
                        reasoningText = agentReasoningText,
                        input = agentDraft,
                        onInputChange = viewModel::onAgentInputChange,
                        onQuickAction = { actionText ->
                            viewModel.newAgentSession()
                            viewModel.onAgentInputChange(actionText)
                            scope.launch {
                                snackbarHostState.showSnackbar("已新建会话并填入指令，确认后发送")
                            }
                        },
                        onVoiceInput = onAgentMicClick,
                        hasDeepSeekKey = deepSeekApiKey.isNotBlank(),
                        agentVoiceAutoSend = agentVoiceAutoSend,
                        onAgentVoiceAutoSendChange = viewModel::setAgentVoiceAutoSend,
                        agentTtsEnabled = agentTtsEnabled,
                        onAgentTtsEnabledChange = viewModel::setAgentTtsEnabled,
                        wakeWordEnabled = wakeWordEnabled,
                        onWakeWordEnabledChange = viewModel::setWakeWordEnabled,
                        initialLogTaskId = agentLogTaskId,
                        canResumeTask = canResumeTask,
                        activeTaskPlans = activeTaskPlans,
                        onResumeTask = viewModel::resumeLastTask,
                        onResumeTaskPlan = viewModel::resumeTaskPlan,
                        onCancelResumeTask = viewModel::cancelUnfinishedTaskPlans,
                        onCancelTaskPlan = viewModel::cancelTaskPlan,
                        agentThinkingEnabled = agentDeepSeekThinkingEnabled,
                        agentReasoningEffort = agentDeepSeekReasoningEffort,
                        onAgentThinkingEnabledChange = viewModel::setAgentDeepSeekThinkingEnabled,
                        onAgentReasoningEffortChange = viewModel::setAgentDeepSeekReasoningEffort,
                        onBack = {
                            currentDestination = AppDestination.Conversation
                        },
                        onSelectSession = viewModel::selectAgentSession,
                        onSend = viewModel::sendAgentMessage,
                        onNewSession = {
                            viewModel.clearAgentDraft()
                            viewModel.newAgentSession()
                        },
                        onShowSessions = {
                            viewModel.clearAgentDraft()
                            viewModel.clearSelectedAgentSession()
                        },
                        onStop = viewModel::stopAgent,
                        onRenameSession = viewModel::renameAgentSession,
                        onDeleteSession = viewModel::deleteAgentSession,
                        onClearSession = viewModel::clearAgentSession,
                        onApproveSkill = viewModel::approveAgentSkill,
                        onRejectSkill = viewModel::rejectAgentSkill,
                        onDeleteSkill = viewModel::deleteAgentSkill,
                        onToggleSkillEnabled = viewModel::setAgentSkillEnabled,
                        onRedactSkill = viewModel::redactAgentSkill,
                        onExportAllSkills = {
                            val text = viewModel.exportAllAgentSkills()
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("VoiceConfig Skills", text))
                            scope.launch { snackbarHostState.showSnackbar("已复制全部技能到剪贴板") }
                        },
                        onImportSkillsFromClipboard = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val text = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (text.isNullOrBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("剪贴板为空") }
                            } else {
                                val skill = viewModel.importAgentSkill(text, "Clipboard")
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (skill != null) "已导入待审核技能：${skill.name}" else "导入失败：剪贴板不是有效的技能 JSON",
                                    )
                                }
                            }
                        },
                        onOpenTask = { taskId ->
                            currentDestination = AppDestination.Automation
                        },
                        onOpenAutomation = {
                            currentDestination = AppDestination.Automation
                        },
                        onOpenSettings = {
                            currentDestination = AppDestination.Profile
                        },
                    )
                    AppDestination.Profile -> SettingsScreen(
                        viewModel = viewModel,
                        localAsrManager = localAsrManager,
                        aiDebugLogs = aiDebugLogs,
                        triggerRules = triggerRules,
                        onClose = {
                            currentDestination = AppDestination.Conversation
                        },
                        onOpenShopping = { showShoppingPage = true },
                        onOpenHomeAssistant = { showHomeAssistantPage = true },
                    )
                }
            }

            if (currentDestination == AppDestination.Automation) {
                FloatingMicButton(
                    isListening = isListening,
                    isPreparing = isPreparing,
                    onClick = {
                        if (showAgentPage) {
                            onAgentMicClick()
                        } else {
                            showCreatePanel = true
                            onMicClick()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 20.dp,
                            bottom = 180.dp,
                        )
                        .offset { IntOffset(micOffsetX.roundToInt(), micOffsetY.roundToInt()) },
                    offsetX = micOffsetX,
                    offsetY = micOffsetY,
                    onOffsetChange = { x, y ->
                        micOffsetX = x
                        micOffsetY = y
                    },
                )
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = currentDestination == AppDestination.Conversation,
                onClick = {
                    agentInitialTab = 0
                    agentTabIndex = agentInitialTab
                    agentLogTaskId = null
                    currentDestination = AppDestination.Conversation
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "首页/对话",
                    )
                },
                label = { Text("首页/对话") },
            )
            NavigationBarItem(
                selected = currentDestination == AppDestination.Automation,
                onClick = {
                    currentDestination = AppDestination.Automation
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "自动化",
                    )
                },
                label = { Text("自动化") },
            )
            NavigationBarItem(
                selected = currentDestination == AppDestination.Profile,
                onClick = {
                    currentDestination = AppDestination.Profile
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "我的",
                    )
                },
                label = { Text("我的") },
            )
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    if (showShoppingPage) {
        ShoppingResearchPage(
            items = shoppingItems,
            onClose = { showShoppingPage = false },
            onUpdateStatus = viewModel::updateShoppingItemStatus,
            onDelete = viewModel::deleteShoppingItem,
            onStartResearch = {
                showShoppingPage = false
                viewModel.newAgentSession()
                viewModel.onAgentInputChange("帮我查母婴用品并比较价格和评价")
                scope.launch {
                    currentDestination = AppDestination.Conversation
                    snackbarHostState.showSnackbar("已进入智能助手，输入研究目标后发送")
                }
            },
        )
    }
    if (showHomeAssistantPage) {
        HomeAssistantPage(
            baseUrl = homeAssistantBaseUrl,
            token = homeAssistantToken,
            configured = homeAssistantConfigured,
            devices = homeAssistantDevices ?: emptyList(),
            testMessage = homeAssistantTestMessage,
            controlMessage = homeAssistantControlMessage,
            onClose = { showHomeAssistantPage = false },
            onSaveAndTest = { url, tokenValue ->
                viewModel.saveHomeAssistantConfig(url, tokenValue)
                viewModel.testHomeAssistantConnection()
            },
            onControlService = viewModel::controlHomeAssistantService,
        )
    }
    }
    pendingAgentConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveAgentConfirmation(false) },
            title = { Text("敏感操作确认") },
            text = {
                Text(
                    "Agent 请求执行：\n${pending.request.toolName}(${pending.request.args})\n\n是否允许？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveAgentConfirmation(true) }) {
                    Text("允许")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveAgentConfirmation(false) }) {
                    Text("拒绝")
                }
            },
        )
    }

}

@Composable
private fun FloatingMicButton(
    isListening: Boolean,
    isPreparing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    offsetX: Float,
    offsetY: Float,
    onOffsetChange: (Float, Float) -> Unit,
) {
    val currentX by rememberUpdatedState(offsetX)
    val currentY by rememberUpdatedState(offsetY)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .size(60.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onOffsetChange(currentX, currentY + dragAmount)
                }
            },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        when {
            isPreparing -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            isListening -> Box(
                modifier = Modifier
                    .requiredSize(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
            else -> Image(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = "说话",
                modifier = Modifier.requiredSize(32.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
