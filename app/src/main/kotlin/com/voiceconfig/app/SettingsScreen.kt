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
    var draftHaBaseUrl by remember { mutableStateOf(homeAssistantBaseUrl) }
    var draftHaToken by remember { mutableStateOf(homeAssistantToken) }
    var showModelEditor by remember { mutableStateOf(false) }

    var showApiKey by remember { mutableStateOf(false) }
    var showHaToken by remember { mutableStateOf(false) }
    var showDebugSection by remember { mutableStateOf(false) }
    var showRawAi by remember { mutableStateOf(false) }
    var showExperimentalAsr by remember { mutableStateOf(false) }

    var asrSelectedId by remember { mutableStateOf(localAsrManager?.selectedModel()?.id ?: "") }
    var asrDownloadingId by remember { mutableStateOf<String?>(null) }
    var asrDownloadProgress by remember { mutableStateOf(0f) }
    var asrErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var triggerType by remember { mutableStateOf("wifi") }
    var triggerName by remember { mutableStateOf("") }
    var triggerSsid by remember { mutableStateOf("") }
    var triggerLevel by remember { mutableStateOf(20) }
    var triggerLat by remember { mutableStateOf("") }
    var triggerLng by remember { mutableStateOf("") }
    var triggerRadius by remember { mutableStateOf("100") }
    var triggerPackage by remember { mutableStateOf("") }
    var triggerTap by remember { mutableStateOf("") }
    var triggerInput by remember { mutableStateOf("") }
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

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.addLocationTrigger(
                triggerName,
                triggerLat.toDoubleOrNull() ?: 0.0,
                triggerLng.toDoubleOrNull() ?: 0.0,
                triggerRadius.toIntOrNull() ?: 100,
                triggerPackage,
                triggerTap,
                triggerInput,
            )
        }
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
                    SettingsSectionCard(title = "外观", defaultExpanded = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = themeMode == "system", onClick = { viewModel.setThemeMode("system") })
                            Text("跟随系统")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = themeMode == "light", onClick = { viewModel.setThemeMode("light") })
                            Text("浅色")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = themeMode == "dark", onClick = { viewModel.setThemeMode("dark") })
                            Text("深色")
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "模型与密钥", defaultExpanded = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (deepSeekApiKey.isBlank()) "未配置" else "已配置",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (deepSeekApiKey.isBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                if (deepSeekApiKey.isNotBlank()) {
                                    Text(
                                        text = deepSeekModel.ifBlank { "deepseek-v4-flash-vision-exp" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = { showModelEditor = !showModelEditor }) {
                                Text(if (showModelEditor) "收起" else "编辑")
                            }
                        }
                        if (showModelEditor) {
                            OutlinedTextField(
                                value = draftApiKey,
                                onValueChange = {
                                    draftApiKey = it
                                    viewModel.setDeepSeekApiKey(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("DeepSeek API Key") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showApiKey = !showApiKey }) {
                                        Text(if (showApiKey) "隐藏" else "显示")
                                    }
                                },
                            )
                            OutlinedTextField(
                                value = draftModel,
                                onValueChange = {
                                    draftModel = it
                                    viewModel.setDeepSeekModel(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("模型（默认 deepseek-v4-flash-vision-exp）") },
                                singleLine = true,
                            )
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "智能助手行为", defaultExpanded = false) {
                        SwitchRow(
                            title = "敏感操作自动执行",
                            subtitle = "开启后 Agent 不再弹出确认，直接执行发送/支付/删除等操作；建议仅测试或信任场景使用",
                            checked = agentAutoConfirmSensitiveActions,
                            onCheckedChange = viewModel::setAgentAutoConfirmSensitiveActions,
                        )
                        SwitchRow(
                            title = "自动截屏验证",
                            subtitle = "开启后每次改变界面的工具执行后自动截屏确认",
                            checked = agentAutoVerifyEnabled,
                            onCheckedChange = viewModel::setAgentAutoVerifyEnabled,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "每次最多自动验证次数",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.setAgentMaxAutoVerifies((agentMaxAutoVerifies - 1).coerceAtLeast(0)) }) {
                                Text("-")
                            }
                            Text(
                                text = agentMaxAutoVerifies.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            TextButton(onClick = { viewModel.setAgentMaxAutoVerifies((agentMaxAutoVerifies + 1).coerceAtMost(20)) }) {
                                Text("+")
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "智能家居 / Home Assistant", defaultExpanded = false) {
                        Text(
                            text = if (homeAssistantConfigured) "已连接配置" else "未配置",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (homeAssistantConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        OutlinedTextField(
                            value = draftHaBaseUrl,
                            onValueChange = {
                                draftHaBaseUrl = it
                                viewModel.saveHomeAssistantConfig(it, draftHaToken)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Home Assistant Base URL") },
                            placeholder = { Text("http://192.168.1.100:8123") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draftHaToken,
                            onValueChange = {
                                draftHaToken = it
                                viewModel.saveHomeAssistantConfig(draftHaBaseUrl, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("长期访问令牌 (Long-Lived Access Token)") },
                            singleLine = true,
                            visualTransformation = if (showHaToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showHaToken = !showHaToken }) {
                                    Text(if (showHaToken) "隐藏" else "显示")
                                }
                            },
                        )
                        Text(
                            text = "配置后 Agent 可通过 home_devices / home_control 控制空调、灯光、窗帘、电视、音乐等 Home Assistant 已接入设备。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                viewModel.saveHomeAssistantConfig(draftHaBaseUrl, draftHaToken)
                                viewModel.testHomeAssistantConnection()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = draftHaBaseUrl.isNotBlank() && draftHaToken.isNotBlank(),
                        ) {
                            Text("测试连接")
                        }
                        TextButton(
                            onClick = onOpenHomeAssistant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开设备面板")
                        }
                        homeAssistantTestMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message.startsWith("已连接")) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        homeAssistantControlMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        homeAssistantDevices?.take(12)?.let { devices ->
                            if (devices.isNotEmpty()) {
                                HorizontalDivider()
                                Text(
                                    text = "设备预览",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                devices.forEach { device ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.friendlyName,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                text = "${device.domain} · ${device.state}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        val sensitive = device.domain in setOf(
                                            "lock", "camera", "alarm_control_panel", "siren",
                                        )
                                        val controllable = device.domain in setOf(
                                            "light", "switch", "fan", "media_player", "input_boolean",
                                        )
                                        if (sensitive) {
                                            Text(
                                                text = "需确认",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else if (!controllable) {
                                            Text(
                                                text = "仅 Agent",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    viewModel.controlHomeAssistant(device.entityId, device.domain)
                                                },
                                            ) {
                                                Text("开关")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "语音", defaultExpanded = false) {
                        SwitchRow(
                            title = "语音输入后自动发送",
                            subtitle = "语音识别完成后直接发送给智能助手，不需要再点发送",
                            checked = agentVoiceAutoSend,
                            onCheckedChange = viewModel::setAgentVoiceAutoSend,
                        )
                        SwitchRow(
                            title = "Agent 结果语音播报",
                            subtitle = "Agent 完成任务后用 TTS 读出结果摘要",
                            checked = agentTtsEnabled,
                            onCheckedChange = viewModel::setAgentTtsEnabled,
                        )
                        SwitchRow(
                            title = "语音唤醒",
                            subtitle = "不打开 App 也能说“言控”唤醒；需要麦克风权限",
                            checked = wakeWordEnabled,
                            onCheckedChange = viewModel::setWakeWordEnabled,
                        )
                        Text(
                            text = "唤醒词：言控 / 你好言控",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    SettingsSectionCard(title = "语音识别", defaultExpanded = false) {
                        if (localAsrManager != null) {
                            Text(
                                text = "推荐：${localAsrManager.recommendedModel().displayName}（性能最佳，需下载）\n默认内置：${localAsrManager.defaultModel().displayName}（安装包小，开箱可用）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "模型列表", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = { showExperimentalAsr = !showExperimentalAsr }) {
                                    Text(if (showExperimentalAsr) "隐藏实验模型" else "显示实验模型")
                                }
                            }
                            localAsrManager.visibleModels(
                                includeExperimental = showExperimentalAsr,
                                includeHidden = false,
                            ).forEach { model ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = asrSelectedId == model.id,
                                            onClick = {
                                                if (localAsrManager.isDownloaded(model)) {
                                                    localAsrManager.selectModel(model.id)
                                                    asrSelectedId = model.id
                                                    scope.launch { localAsrManager.warmUp() }
                                                }
                                            },
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = model.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = "${localAsrManager.modelSizeText(model)} · ${model.description}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (model.builtin || localAsrManager.isDownloaded(model)) {
                                            TextButton(
                                                onClick = {
                                                    localAsrManager.selectModel(model.id)
                                                    asrSelectedId = model.id
                                                    scope.launch { localAsrManager.warmUp() }
                                                },
                                                enabled = asrSelectedId != model.id,
                                            ) {
                                                Text(if (asrSelectedId == model.id) "使用中" else "使用")
                                            }
                                        } else {
                                            if (asrDownloadingId == model.id) {
                                                Column(
                                                    modifier = Modifier.width(110.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    LinearProgressIndicator(
                                                        progress = { asrDownloadProgress },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                    Text(
                                                        text = "下载中 ${(asrDownloadProgress * 100).toInt()}%",
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            asrDownloadingId = model.id
                                                            asrErrors = asrErrors - model.id
                                                            runCatching {
                                                                localAsrManager.downloadModel(model) { progress ->
                                                                    asrDownloadProgress = progress
                                                                }
                                                            }.onFailure { e ->
                                                                asrErrors = asrErrors + (model.id to (e.message ?: "下载失败"))
                                                            }
                                                            asrDownloadingId = null
                                                            localAsrManager.selectModel(model.id)
                                                            asrSelectedId = model.id
                                                            scope.launch { localAsrManager.warmUp() }
                                                        }
                                                    },
                                                ) {
                                                    Text("下载")
                                                }
                                            }
                                        }
                                    }
                                    asrErrors[model.id]?.let { error ->
                                        if (asrDownloadingId != model.id) {
                                            Text(
                                                text = error,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "条件触发器", defaultExpanded = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = triggerType == "wifi", onClick = { triggerType = "wifi" })
                            Text("Wi-Fi")
                            RadioButton(selected = triggerType == "battery", onClick = { triggerType = "battery" })
                            Text("低电量")
                            RadioButton(selected = triggerType == "location", onClick = { triggerType = "location" })
                            Text("位置")
                        }
                        OutlinedTextField(
                            value = triggerName,
                            onValueChange = { triggerName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("触发器名称（可选）") },
                            singleLine = true,
                        )
                        when (triggerType) {
                            "wifi" -> OutlinedTextField(
                                value = triggerSsid,
                                onValueChange = { triggerSsid = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Wi-Fi 名称（SSID）") },
                                singleLine = true,
                            )
                            "battery" -> OutlinedTextField(
                                value = triggerLevel.toString(),
                                onValueChange = { triggerLevel = it.toIntOrNull() ?: 20 },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("低电量阈值 %（1-100）") },
                                singleLine = true,
                            )
                            else -> {
                                OutlinedTextField(
                                    value = triggerLat,
                                    onValueChange = { triggerLat = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("纬度（如 31.2304）") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = triggerLng,
                                    onValueChange = { triggerLng = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("经度（如 121.4737）") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = triggerRadius,
                                    onValueChange = { triggerRadius = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("半径（米，10-5000）") },
                                    singleLine = true,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = triggerPackage,
                            onValueChange = { triggerPackage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("目标包名（如 com.tencent.wework）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = triggerTap,
                            onValueChange = { triggerTap = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("点击坐标（可选，格式 x,y）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = triggerInput,
                            onValueChange = { triggerInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("输入文本（可选）") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                if (triggerType == "location" &&
                                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                } else {
                                    when (triggerType) {
                                        "wifi" -> viewModel.addWifiTrigger(triggerName, triggerSsid, triggerPackage, triggerTap, triggerInput)
                                        "battery" -> viewModel.addBatteryTrigger(triggerName, triggerLevel, triggerPackage, triggerTap, triggerInput)
                                        else -> viewModel.addLocationTrigger(
                                            triggerName,
                                            triggerLat.toDoubleOrNull() ?: 0.0,
                                            triggerLng.toDoubleOrNull() ?: 0.0,
                                            triggerRadius.toIntOrNull() ?: 100,
                                            triggerPackage,
                                            triggerTap,
                                            triggerInput,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("创建触发器")
                        }
                        triggerRules.forEach { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${if (rule.enabled) "已启用" else "已停用"} · ${rule.name} · ${rule.condition.type}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { viewModel.toggleTriggerRule(rule) },
                                )
                                TextButton(onClick = { viewModel.deleteTriggerRule(rule) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
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
