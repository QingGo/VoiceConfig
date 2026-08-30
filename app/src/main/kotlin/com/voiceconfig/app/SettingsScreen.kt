package com.voiceconfig.app

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.ai.LocalAsrManager
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.app.ui.VoiceSectionCard
import com.voiceconfig.app.ui.VoiceStatusCard
import com.voiceconfig.app.ui.VoiceStatusItem
import com.voiceconfig.app.ui.RemoteNodesDialog
import com.voiceconfig.app.ui.RemoteProjectsDialog
import com.voiceconfig.app.ui.SshConsoleDialog
import com.voiceconfig.app.ui.SshFileDialog
import com.voiceconfig.app.ui.SshShellDialog
import com.voiceconfig.app.ui.SshKeysDialog
import com.voiceconfig.app.ui.SshServiceDialog
import com.voiceconfig.app.ui.SshNodeLogDialog
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    automationViewModel: AutomationViewModel,
    profileViewModel: ProfileViewModel,
    sshViewModel: SshViewModel,
    localAsrManager: LocalAsrManager?,
    aiDebugLogs: List<AiDebugLogEntity>,
    triggerRules: List<TriggerRule>,
    onClose: () -> Unit,
    onOpenShopping: () -> Unit = {},
    onOpenHomeAssistant: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uxPrefs = context.getSharedPreferences("voiceconfig_ux", Context.MODE_PRIVATE)
    var developerMode by remember { mutableStateOf(uxPrefs.getBoolean("developer_mode", false)) }
    BackHandler(onBack = onClose)

    val uiState by automationViewModel.uiState.collectAsState()
    val deepSeekApiKey by profileViewModel.deepSeekApiKey.collectAsState()
    val deepSeekModel by profileViewModel.deepSeekModel.collectAsState()
    val agentAutoConfirmSensitiveActions by profileViewModel.agentAutoConfirmSensitiveActions.collectAsState()
    val agentAutoVerifyEnabled by profileViewModel.agentAutoVerifyEnabled.collectAsState()
    val agentMaxAutoVerifies by profileViewModel.agentMaxAutoVerifies.collectAsState()
    val wechatUiAutomationEnabled by profileViewModel.wechatUiAutomationEnabled.collectAsState()
    val wecomCorpId by profileViewModel.wecomCorpId.collectAsState()
    val wecomAgentId by profileViewModel.wecomAgentId.collectAsState()
    val wecomSecret by profileViewModel.wecomSecret.collectAsState()
    val wecomTestMessage by profileViewModel.wecomTestMessage.collectAsState()
    val agentSkills by profileViewModel.agentSkills.collectAsState()
    val flowScripts by profileViewModel.flowScripts.collectAsState()
    val homeAssistantBaseUrl by profileViewModel.homeAssistantBaseUrl.collectAsState()
    val homeAssistantToken by profileViewModel.homeAssistantToken.collectAsState()
    val homeAssistantConfigured by profileViewModel.homeAssistantConfigured.collectAsState()
    val homeAssistantDevices by profileViewModel.homeAssistantDevices.collectAsState()
    val homeAssistantTestMessage by profileViewModel.homeAssistantTestMessage.collectAsState()
    val homeAssistantControlMessage by profileViewModel.homeAssistantControlMessage.collectAsState()
    val agentVoiceAutoSend by profileViewModel.agentVoiceAutoSend.collectAsState()
    val agentTtsEnabled by profileViewModel.agentTtsEnabled.collectAsState()
    val wakeWordEnabled by profileViewModel.wakeWordEnabled.collectAsState()
    val overlayBallEnabled by profileViewModel.overlayBallEnabled.collectAsState()
    val themeMode by profileViewModel.themeMode.collectAsState()
    val capabilityStatus by viewModel.capabilityStatus.collectAsState()

    var draftApiKey by remember { mutableStateOf(deepSeekApiKey) }
    var draftModel by remember { mutableStateOf(deepSeekModel) }
    var showModelEditor by remember { mutableStateOf(false) }

    var showApiKey by remember { mutableStateOf(false) }
    var showDebugSection by remember { mutableStateOf(false) }
    var showRawAi by remember { mutableStateOf(false) }


    var showRemoteNodes by remember { mutableStateOf(false) }
    var showRemoteProjects by remember { mutableStateOf(false) }
    var showSshConsole by remember { mutableStateOf(false) }
    var showSshFile by remember { mutableStateOf(false) }
    var showSshShell by remember { mutableStateOf(false) }
    var showSshAudit by remember { mutableStateOf(false) }
    var showSshKeys by remember { mutableStateOf(false) }
    var showSshServices by remember { mutableStateOf(false) }
    var showSshNodeLogs by remember { mutableStateOf(false) }
    val remoteNodes by sshViewModel.remoteNodes.collectAsState()
    val remoteProjects by sshViewModel.remoteProjects.collectAsState()
    val remoteCommandResult by sshViewModel.remoteCommandResult.collectAsState()
    val sshResult by sshViewModel.sshResult.collectAsState()
    val sshBootstrapResult by sshViewModel.sshBootstrapResult.collectAsState()
    val sshFileResult by sshViewModel.sshFileResult.collectAsState()
    val sshFileEntries by sshViewModel.sshFileEntries.collectAsState()
    val sshKeys by sshViewModel.sshKeys.collectAsState()
    val sshServiceResult by sshViewModel.sshServiceResult.collectAsState()
    val sshNodeLogResult by sshViewModel.sshNodeLogResult.collectAsState()
    val sshShellOutput by sshViewModel.sshShellOutput.collectAsState()
    val sshShellRunning by sshViewModel.sshShellRunning.collectAsState()
    val sshShellError by sshViewModel.sshShellError.collectAsState()
    val sshAuditText by sshViewModel.sshAuditText.collectAsState()
    val pendingSshHostKey by sshViewModel.pendingSshHostKey.collectAsState()
    val defaultRemoteHost = remoteNodes.firstOrNull()?.host.orEmpty()
    val savedSshCredential = remember(defaultRemoteHost, showSshFile, showSshConsole, showSshShell) {
        defaultRemoteHost.takeIf { it.isNotBlank() }?.let { sshViewModel.getSshCredential(it, 22) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text("完成")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    VoiceStatusCard(
                        title = "我的状态",
                        onItemClick = { item ->
                            when (item.label) {
                                "AI 模型" -> showModelEditor = true
                                "Home Assistant" -> onOpenHomeAssistant()
                            }
                        },
                        items = buildList {
                            add(
                                VoiceStatusItem(
                                    label = "AI 模型",
                                    value = if (capabilityStatus.cloudLlm) "已配置" else "未配置",
                                    valueColor = if (capabilityStatus.cloudLlm) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                ),
                            )
                            add(
                                VoiceStatusItem(
                                    label = "Home Assistant",
                                    value = if (capabilityStatus.homeAssistant) "已连接" else "未配置",
                                ),
                            )
                            add(
                                VoiceStatusItem(
                                    label = "无障碍",
                                    value = if (capabilityStatus.accessibility) "已开启" else "未开启",
                                ),
                            )
                            add(
                                VoiceStatusItem(
                                    label = "Shizuku",
                                    value = if (capabilityStatus.shizuku) "可用" else "未启用",
                                ),
                            )
                            add(
                                VoiceStatusItem(
                                    label = "语音唤醒",
                                    value = if (wakeWordEnabled) "已开启" else "未开启",
                                ),
                            )
                            if (developerMode) {
                                add(
                                    VoiceStatusItem(
                                        label = "远程节点",
                                        value = "已登记 ${remoteNodes.size} 个",
                                    ),
                                )
                            }
                        },
                    )
                }

                item {
                    AppearanceSettingsSection(
                        themeMode = themeMode,
                        onThemeModeChange = profileViewModel::setThemeMode,
                    )
                }

                item {
                    ModelSettingsSection(
                        isConfigured = deepSeekApiKey.isNotBlank(),
                        currentModel = deepSeekModel,
                        draftApiKey = draftApiKey,
                        onDraftApiKeyChange = {
                            draftApiKey = it
                            profileViewModel.setDeepSeekApiKey(it)
                        },
                        draftModel = draftModel,
                        onDraftModelChange = {
                            draftModel = it
                            profileViewModel.setDeepSeekModel(it)
                        },
                        showEditor = showModelEditor,
                        onShowEditorChange = { showModelEditor = it },
                        showKey = showApiKey,
                        onShowKeyChange = { showApiKey = it },
                    )
                }

                item {
                    AgentBehaviorSettingsSection(
                        autoConfirm = agentAutoConfirmSensitiveActions,
                        onAutoConfirmChange = profileViewModel::setAgentAutoConfirmSensitiveActions,
                        autoVerify = agentAutoVerifyEnabled,
                        onAutoVerifyChange = profileViewModel::setAgentAutoVerifyEnabled,
                        maxAutoVerifies = agentMaxAutoVerifies,
                        onMaxAutoVerifiesChange = profileViewModel::setAgentMaxAutoVerifies,
                    )
                }

                item {
                    EnterpriseWechatSettingsSection(
                        wechatAutomationEnabled = wechatUiAutomationEnabled,
                        onWechatAutomationChange = profileViewModel::setWechatUiAutomationEnabled,
                        wecomCorpId = wecomCorpId,
                        onWecomCorpIdChange = profileViewModel::setWecomCorpId,
                        wecomAgentId = wecomAgentId,
                        onWecomAgentIdChange = profileViewModel::setWecomAgentId,
                        wecomSecret = wecomSecret,
                        onWecomSecretChange = profileViewModel::setWecomSecret,
                        wecomTestMessage = wecomTestMessage,
                        onTestWecom = profileViewModel::testWecomConnection,
                    )
                }

                item {
                    BuiltinSkillsSettingsSection(skills = agentSkills)
                }
                item {
                    FlowScriptSettingsSection(
                        scripts = flowScripts,
                        onApprove = profileViewModel::approveFlowScript,
                        onReject = profileViewModel::rejectFlowScript,
                        onSetEnabled = profileViewModel::setFlowScriptEnabled,
                        onDelete = profileViewModel::deleteFlowScript,
                        onImportJson = profileViewModel::importFlowScriptJson,
                    )
                }

                item {
                    HomeAssistantSettingsSection(
                        configured = homeAssistantConfigured,
                        baseUrl = homeAssistantBaseUrl,
                        token = homeAssistantToken,
                        testMessage = homeAssistantTestMessage,
                        controlMessage = homeAssistantControlMessage,
                        devices = homeAssistantDevices ?: emptyList(),
                        onSaveConfig = profileViewModel::saveHomeAssistantConfig,
                        onTest = profileViewModel::testHomeAssistantConnection,
                        onOpenPanel = onOpenHomeAssistant,
                        onControl = profileViewModel::controlHomeAssistant,
                    )
                }

                item {
                    VoiceSettingsSection(
                        agentVoiceAutoSend = agentVoiceAutoSend,
                        onAgentVoiceAutoSendChange = profileViewModel::setAgentVoiceAutoSend,
                        agentTtsEnabled = agentTtsEnabled,
                        onAgentTtsEnabledChange = profileViewModel::setAgentTtsEnabled,
                        wakeWordEnabled = wakeWordEnabled,
                        onWakeWordEnabledChange = profileViewModel::setWakeWordEnabled,
                        overlayBallEnabled = overlayBallEnabled,
                        onOverlayBallEnabledChange = profileViewModel::setOverlayBallEnabled,
                    )
                }

                item {
                    AsrSettingsSection(localAsrManager = localAsrManager)
                }

                item {
                    TriggerSettingsSection(
                        rules = triggerRules,
                        onAddWifi = automationViewModel::addWifiTrigger,
                        onAddBattery = automationViewModel::addBatteryTrigger,
                        onAddLocation = automationViewModel::addLocationTrigger,
                        onToggleRule = automationViewModel::toggleTriggerRule,
                        onDeleteRule = automationViewModel::deleteTriggerRule,
                    )
                }

                item {
                    IntelligenceSettingsSection(onOpenShopping = onOpenShopping)
                }

                item {
                    DeveloperModeSettingsSection(
                        developerMode = developerMode,
                        onDeveloperModeChange = { enabled ->
                            developerMode = enabled
                            uxPrefs.edit().putBoolean("developer_mode", enabled).apply()
                        },
                    )
                }

                if (developerMode) {
                item {
                    AdvancedSettingsSection(
                        remoteNodesCount = remoteNodes.size,
                        onOpenRemoteNodes = { showRemoteNodes = true },
                        onOpenSshConsole = {
                            showSshConsole = true
                            sshViewModel.clearSshResult()
                        },
                        onOpenSshFile = {
                            showSshFile = true
                            sshViewModel.clearSshFileResult()
                        },
                        onOpenSshShell = {
                            showSshShell = true
                            sshViewModel.clearSshShellOutput()
                            sshViewModel.clearSshShellError()
                        },
                        onOpenSshAudit = {
                            showSshAudit = true
                            sshViewModel.loadSshAudit()
                        },
                        onOpenSshKeys = {
                            showSshKeys = true
                            sshViewModel.refreshSshKeys()
                        },
                        onOpenSshServices = {
                            showSshServices = true
                            sshViewModel.clearSshServiceResult()
                        },
                        onOpenSshNodeLogs = {
                            showSshNodeLogs = true
                            sshViewModel.clearSshNodeLogResult()
                        },
                        onOpenRemoteProjects = { showRemoteProjects = true },
                        aiDebugLogsSize = aiDebugLogs.size,
                        lastAiError = uiState.lastAiError,
                        lastAiParseError = uiState.lastAiParseError,
                        lastAiRawResponse = uiState.lastAiRawResponse,
                        onCopyDebugReport = {
                            val report = profileViewModel.buildAiDebugLogReport(aiDebugLogs)
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("AI错误日志", report))
                        },
                        onShareDebugReport = {
                            val report = profileViewModel.buildAiDebugLogReport(aiDebugLogs)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "导出 AI 错误日志"))
                        },
                    )
                }
                }

                item {
                    PermissionSettingsSection()
                }

                item {
                    AboutSettingsSection()
                }

            }
        }
    }
    if (showRemoteNodes) {
        RemoteNodesDialog(
            nodes = remoteNodes,
            onDismiss = { showRemoteNodes = false },
            onSave = sshViewModel::saveRemoteNode,
            onDelete = sshViewModel::deleteRemoteNode,
            onToggleEnabled = sshViewModel::setRemoteNodeEnabled,
            onTogglePaused = sshViewModel::setRemoteNodePaused,
            commandResult = remoteCommandResult,
            onExecute = sshViewModel::executeRemoteCommand,
            onClearResult = sshViewModel::clearRemoteCommandResult,
        )
    }

    if (showRemoteProjects) {
        RemoteProjectsDialog(
            projects = remoteProjects,
            onDismiss = { showRemoteProjects = false },
        )
    }

    if (showSshConsole) {
        SshConsoleDialog(
            onDismiss = { showSshConsole = false },
            onRun = sshViewModel::executeSsh,
            result = sshResult,
            onClearResult = sshViewModel::clearSshResult,
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onInstall = sshViewModel::installNodeViaSsh,
            bootstrapResult = sshBootstrapResult,
            onClearBootstrapResult = sshViewModel::clearSshBootstrapResult,
            onClearHostKey = sshViewModel::clearSshHostKey,
            savedKeys = sshKeys,
        )
    }

    if (showSshFile) {
        SshFileDialog(
            onDismiss = { showSshFile = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onList = sshViewModel::listSshFiles,
            onRead = sshViewModel::readSshFile,
            onWrite = sshViewModel::writeSshFile,
            result = sshFileResult,
            onClearResult = sshViewModel::clearSshFileResult,
            entries = sshFileEntries,
            onMkdir = sshViewModel::mkdirSshFile,
            onDelete = sshViewModel::deleteSshFile,
            onRename = sshViewModel::renameSshFile,
            savedKeys = sshKeys,
        )
    }

    if (showSshShell) {
        SshShellDialog(
            onDismiss = {
                sshViewModel.closeSshShell()
                showSshShell = false
            },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onStart = sshViewModel::startSshShell,
            onSend = sshViewModel::sendSshShellCommand,
            onCloseSession = sshViewModel::closeSshShell,
            output = sshShellOutput,
            running = sshShellRunning,
            error = sshShellError,
            onClearOutput = sshViewModel::clearSshShellOutput,
            onClearError = sshViewModel::clearSshShellError,
            savedKeys = sshKeys,
            onResize = sshViewModel::resizeSshShell,
        )
    }

    if (showSshAudit) {
        AlertDialog(
            onDismissRequest = {
                showSshAudit = false
                sshViewModel.clearSshAudit()
            },
            title = { Text("SSH 审计（最近 200 条）") },
            text = {
                SelectionContainer {
                    Text(
                        text = sshAuditText.ifBlank { "暂无审计记录" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSshAudit = false
                    sshViewModel.clearSshAudit()
                }) {
                    Text("关闭")
                }
            },
        )
    }

    if (showSshKeys) {
        SshKeysDialog(
            keys = sshKeys,
            onDismiss = { showSshKeys = false },
            onGenerate = sshViewModel::generateSshKey,
            onRename = sshViewModel::renameSshKey,
            onDelete = sshViewModel::deleteSshKey,
        )
    }

    if (showSshServices) {
        SshServiceDialog(
            onDismiss = { showSshServices = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            result = sshServiceResult,
            onClearResult = sshViewModel::clearSshServiceResult,
            onList = sshViewModel::listSshServices,
            onStart = sshViewModel::startSshService,
            onStop = sshViewModel::stopSshService,
            onRestart = sshViewModel::restartSshService,
            onStatus = sshViewModel::statusSshService,
            onLogs = sshViewModel::logsSshService,
            savedKeys = sshKeys,
        )
    }

    if (showSshNodeLogs) {
        SshNodeLogDialog(
            onDismiss = { showSshNodeLogs = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            result = sshNodeLogResult,
            onClearResult = sshViewModel::clearSshNodeLogResult,
            onReadAudit = sshViewModel::readSshNodeAudit,
            onReadLog = sshViewModel::readSshNodeLog,
            savedKeys = sshKeys,
        )
    }

    pendingSshHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { sshViewModel.confirmSshHostKey(false) },
            title = { Text("首次连接确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "正在连接 ${pending.config.host}:${pending.config.port}，请核对主机指纹：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SelectionContainer {
                        Text(
                            text = pending.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "确认后才会执行目标命令或安装节点。指纹不匹配请拒绝。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { sshViewModel.confirmSshHostKey(true) }) {
                    Text("信任并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { sshViewModel.confirmSshHostKey(false) }) {
                    Text("拒绝")
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    VoiceSectionCard(
        title = title,
        defaultExpanded = defaultExpanded,
        content = { content() },
    )
}
