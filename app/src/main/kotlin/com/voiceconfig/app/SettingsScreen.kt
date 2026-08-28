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

    val uiState by viewModel.uiState.collectAsState()
    val deepSeekApiKey by viewModel.deepSeekApiKey.collectAsState()
    val deepSeekModel by viewModel.deepSeekModel.collectAsState()
    val agentAutoConfirmSensitiveActions by viewModel.agentAutoConfirmSensitiveActions.collectAsState()
    val agentAutoVerifyEnabled by viewModel.agentAutoVerifyEnabled.collectAsState()
    val agentMaxAutoVerifies by viewModel.agentMaxAutoVerifies.collectAsState()
    val homeAssistantBaseUrl by viewModel.homeAssistantBaseUrl.collectAsState()
    val homeAssistantToken by viewModel.homeAssistantToken.collectAsState()
    val homeAssistantConfigured by viewModel.homeAssistantConfigured.collectAsState()
    val homeAssistantDevices by viewModel.homeAssistantDevices.collectAsState()
    val homeAssistantTestMessage by viewModel.homeAssistantTestMessage.collectAsState()
    val homeAssistantControlMessage by viewModel.homeAssistantControlMessage.collectAsState()
    val agentVoiceAutoSend by viewModel.agentVoiceAutoSend.collectAsState()
    val agentTtsEnabled by viewModel.agentTtsEnabled.collectAsState()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

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
    val remoteNodes by viewModel.remoteNodes.collectAsState()
    val remoteProjects by viewModel.remoteProjects.collectAsState()
    val remoteCommandResult by viewModel.remoteCommandResult.collectAsState()
    val sshResult by viewModel.sshResult.collectAsState()
    val sshBootstrapResult by viewModel.sshBootstrapResult.collectAsState()
    val sshFileResult by viewModel.sshFileResult.collectAsState()
    val sshFileEntries by viewModel.sshFileEntries.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val sshServiceResult by viewModel.sshServiceResult.collectAsState()
    val sshNodeLogResult by viewModel.sshNodeLogResult.collectAsState()
    val sshShellOutput by viewModel.sshShellOutput.collectAsState()
    val sshShellRunning by viewModel.sshShellRunning.collectAsState()
    val sshShellError by viewModel.sshShellError.collectAsState()
    val sshAuditText by viewModel.sshAuditText.collectAsState()
    val pendingSshHostKey by viewModel.pendingSshHostKey.collectAsState()
    val defaultRemoteHost = remoteNodes.firstOrNull()?.host.orEmpty()
    val savedSshCredential = remember(defaultRemoteHost, showSshFile, showSshConsole, showSshShell) {
        defaultRemoteHost.takeIf { it.isNotBlank() }?.let { viewModel.getSshCredential(it, 22) }
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
                                    value = if (deepSeekApiKey.isBlank()) "未配置" else "已配置",
                                    valueColor = if (deepSeekApiKey.isBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                ),
                            )
                            add(
                                VoiceStatusItem(
                                    label = "Home Assistant",
                                    value = if (homeAssistantConfigured) "已连接" else "未配置",
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
                        onThemeModeChange = viewModel::setThemeMode,
                    )
                }

                item {
                    ModelSettingsSection(
                        isConfigured = deepSeekApiKey.isNotBlank(),
                        currentModel = deepSeekModel,
                        draftApiKey = draftApiKey,
                        onDraftApiKeyChange = {
                            draftApiKey = it
                            viewModel.setDeepSeekApiKey(it)
                        },
                        draftModel = draftModel,
                        onDraftModelChange = {
                            draftModel = it
                            viewModel.setDeepSeekModel(it)
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
                        onAutoConfirmChange = viewModel::setAgentAutoConfirmSensitiveActions,
                        autoVerify = agentAutoVerifyEnabled,
                        onAutoVerifyChange = viewModel::setAgentAutoVerifyEnabled,
                        maxAutoVerifies = agentMaxAutoVerifies,
                        onMaxAutoVerifiesChange = viewModel::setAgentMaxAutoVerifies,
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
                        onSaveConfig = viewModel::saveHomeAssistantConfig,
                        onTest = viewModel::testHomeAssistantConnection,
                        onOpenPanel = onOpenHomeAssistant,
                        onControl = viewModel::controlHomeAssistant,
                    )
                }

                item {
                    VoiceSettingsSection(
                        agentVoiceAutoSend = agentVoiceAutoSend,
                        onAgentVoiceAutoSendChange = viewModel::setAgentVoiceAutoSend,
                        agentTtsEnabled = agentTtsEnabled,
                        onAgentTtsEnabledChange = viewModel::setAgentTtsEnabled,
                        wakeWordEnabled = wakeWordEnabled,
                        onWakeWordEnabledChange = viewModel::setWakeWordEnabled,
                    )
                }

                item {
                    AsrSettingsSection(localAsrManager = localAsrManager)
                }

                item {
                    TriggerSettingsSection(
                        rules = triggerRules,
                        onAddWifi = viewModel::addWifiTrigger,
                        onAddBattery = viewModel::addBatteryTrigger,
                        onAddLocation = viewModel::addLocationTrigger,
                        onToggleRule = viewModel::toggleTriggerRule,
                        onDeleteRule = viewModel::deleteTriggerRule,
                    )
                }

                item {
                    SettingsSectionCard(title = "智能能力", defaultExpanded = false) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("购物研究清单", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "价格 / 评分 / 口碑对比与采购状态",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = onOpenShopping) {
                                Text("打开")
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "开发者模式", defaultExpanded = false) {
                        SwitchRow(
                            title = "显示高级能力",
                            subtitle = "SSH、远程节点、审计、调试日志仅在需要时开启",
                            checked = developerMode,
                            onCheckedChange = { enabled ->
                                developerMode = enabled
                                uxPrefs.edit().putBoolean("developer_mode", enabled).apply()
                            },
                        )
                    }
                }

                if (developerMode) {
                item {
                    SettingsSectionCard(title = "高级能力 / 远程", defaultExpanded = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "已登记 ${remoteNodes.size} 个远程节点",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "SSH 命令 / 文件 / 终端 / 服务",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showRemoteNodes = true }) {
                                Text("管理")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteToolTile("命令终端", Icons.Default.Build, onClick = {
                                showSshConsole = true
                                viewModel.clearSshResult()
                            })
                            RemoteToolTile("远程文件", Icons.Default.List, onClick = {
                                showSshFile = true
                                viewModel.clearSshFileResult()
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteToolTile("交互终端", Icons.Default.Edit, onClick = {
                                showSshShell = true
                                viewModel.clearSshShellOutput()
                                viewModel.clearSshShellError()
                            })
                            RemoteToolTile("SSH 审计", Icons.Default.Info, onClick = {
                                showSshAudit = true
                                viewModel.loadSshAudit()
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteToolTile("密钥管理", Icons.Default.Lock, onClick = {
                                showSshKeys = true
                                viewModel.refreshSshKeys()
                            })
                            RemoteToolTile("系统服务", Icons.Default.Settings, onClick = {
                                showSshServices = true
                                viewModel.clearSshServiceResult()
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteToolTile("节点日志", Icons.Default.List, onClick = {
                                showSshNodeLogs = true
                                viewModel.clearSshNodeLogResult()
                            })
                            RemoteToolTile("远程节点", Icons.Default.Phone, onClick = { showRemoteNodes = true })
                            RemoteToolTile("远程项目", Icons.Default.Build, onClick = { showRemoteProjects = true })
                        }
                    }
                }
                }

                item {
                    SettingsSectionCard(title = "权限与系统", defaultExpanded = false) {
                        PermissionCheckSection(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (developerMode) {
                item {
                    SettingsSectionCard(title = "高级 / 调试", defaultExpanded = false) {
                        TextButton(onClick = { showDebugSection = !showDebugSection }) {
                            Text(if (showDebugSection) "收起开发者调试" else "展开开发者调试")
                        }
                        if (showDebugSection) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    uiState.lastAiError?.let { error ->
                                        Text(
                                            text = "最近 AI 错误：$error",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    uiState.lastAiParseError?.let { parseError ->
                                        Text(
                                            text = "JSON 解析错误：$parseError",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    uiState.lastAiRawResponse?.let { raw ->
                                        if (showRawAi) {
                                            Text(
                                                text = raw,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            uiState.lastAiRawResponse?.let {
                                TextButton(onClick = { showRawAi = !showRawAi }) {
                                    Text(if (showRawAi) "隐藏原始返回" else "查看原始返回")
                                }
                            }
                            Text(
                                text = "AI 错误日志（${aiDebugLogs.size} 条）",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(
                                onClick = {
                                    val report = viewModel.buildAiDebugLogReport(aiDebugLogs)
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("AI错误日志", report))
                                },
                            ) {
                                Text("复制为 GitHub Issue 文本")
                            }
                            TextButton(
                                onClick = {
                                    val report = viewModel.buildAiDebugLogReport(aiDebugLogs)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, report)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "导出 AI 错误日志"))
                                },
                            ) {
                                Text("分享错误日志")
                            }
                        }
                    }
                }
            }
        }
    }
    }
    if (showRemoteNodes) {
        RemoteNodesDialog(
            nodes = remoteNodes,
            onDismiss = { showRemoteNodes = false },
            onSave = viewModel::saveRemoteNode,
            onDelete = viewModel::deleteRemoteNode,
            onToggleEnabled = viewModel::setRemoteNodeEnabled,
            onTogglePaused = viewModel::setRemoteNodePaused,
            commandResult = remoteCommandResult,
            onExecute = viewModel::executeRemoteCommand,
            onClearResult = viewModel::clearRemoteCommandResult,
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
            onRun = viewModel::executeSsh,
            result = sshResult,
            onClearResult = viewModel::clearSshResult,
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onInstall = viewModel::installNodeViaSsh,
            bootstrapResult = sshBootstrapResult,
            onClearBootstrapResult = viewModel::clearSshBootstrapResult,
            onClearHostKey = viewModel::clearSshHostKey,
            savedKeys = sshKeys,
        )
    }

    if (showSshFile) {
        SshFileDialog(
            onDismiss = { showSshFile = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onList = viewModel::listSshFiles,
            onRead = viewModel::readSshFile,
            onWrite = viewModel::writeSshFile,
            result = sshFileResult,
            onClearResult = viewModel::clearSshFileResult,
            entries = sshFileEntries,
            onMkdir = viewModel::mkdirSshFile,
            onDelete = viewModel::deleteSshFile,
            onRename = viewModel::renameSshFile,
            savedKeys = sshKeys,
        )
    }

    if (showSshShell) {
        SshShellDialog(
            onDismiss = {
                viewModel.closeSshShell()
                showSshShell = false
            },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            onStart = viewModel::startSshShell,
            onSend = viewModel::sendSshShellCommand,
            onCloseSession = viewModel::closeSshShell,
            output = sshShellOutput,
            running = sshShellRunning,
            error = sshShellError,
            onClearOutput = viewModel::clearSshShellOutput,
            onClearError = viewModel::clearSshShellError,
            savedKeys = sshKeys,
            onResize = viewModel::resizeSshShell,
        )
    }

    if (showSshAudit) {
        AlertDialog(
            onDismissRequest = {
                showSshAudit = false
                viewModel.clearSshAudit()
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
                    viewModel.clearSshAudit()
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
            onGenerate = viewModel::generateSshKey,
            onRename = viewModel::renameSshKey,
            onDelete = viewModel::deleteSshKey,
        )
    }

    if (showSshServices) {
        SshServiceDialog(
            onDismiss = { showSshServices = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            result = sshServiceResult,
            onClearResult = viewModel::clearSshServiceResult,
            onList = viewModel::listSshServices,
            onStart = viewModel::startSshService,
            onStop = viewModel::stopSshService,
            onRestart = viewModel::restartSshService,
            onStatus = viewModel::statusSshService,
            onLogs = viewModel::logsSshService,
            savedKeys = sshKeys,
        )
    }

    if (showSshNodeLogs) {
        SshNodeLogDialog(
            onDismiss = { showSshNodeLogs = false },
            defaultHost = defaultRemoteHost,
            initialCredential = savedSshCredential,
            result = sshNodeLogResult,
            onClearResult = viewModel::clearSshNodeLogResult,
            onReadAudit = viewModel::readSshNodeAudit,
            onReadLog = viewModel::readSshNodeLog,
            savedKeys = sshKeys,
        )
    }

    pendingSshHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmSshHostKey(false) },
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
                TextButton(onClick = { viewModel.confirmSshHostKey(true) }) {
                    Text("信任并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmSshHostKey(false) }) {
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
