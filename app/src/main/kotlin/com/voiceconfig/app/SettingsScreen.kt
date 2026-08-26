package com.voiceconfig.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.voiceconfig.app.ui.RemoteNodesDialog
import com.voiceconfig.app.ui.SshConsoleDialog
import com.voiceconfig.app.ui.SshFileDialog
import com.voiceconfig.app.ui.SshShellDialog
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    localAsrManager: LocalAsrManager?,
    aiDebugLogs: List<AiDebugLogEntity>,
    triggerRules: List<TriggerRule>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsState()
    val deepSeekApiKey by viewModel.deepSeekApiKey.collectAsState()
    val deepSeekModel by viewModel.deepSeekModel.collectAsState()
    val deepSeekThinkingEnabled by viewModel.deepSeekThinkingEnabled.collectAsState()
    val deepSeekReasoningEffort by viewModel.deepSeekReasoningEffort.collectAsState()
    val agentDeepSeekThinkingEnabled by viewModel.agentDeepSeekThinkingEnabled.collectAsState()
    val agentDeepSeekReasoningEffort by viewModel.agentDeepSeekReasoningEffort.collectAsState()
    val agentAutoConfirmSensitiveActions by viewModel.agentAutoConfirmSensitiveActions.collectAsState()
    val agentAutoVerifyEnabled by viewModel.agentAutoVerifyEnabled.collectAsState()
    val agentMaxAutoVerifies by viewModel.agentMaxAutoVerifies.collectAsState()

    var draftApiKey by remember { mutableStateOf(deepSeekApiKey) }
    var draftModel by remember { mutableStateOf(deepSeekModel) }
    var draftThinkingEnabled by remember { mutableStateOf(deepSeekThinkingEnabled) }
    var draftReasoningEffort by remember { mutableStateOf(deepSeekReasoningEffort) }
    var draftAgentThinkingEnabled by remember { mutableStateOf(agentDeepSeekThinkingEnabled) }
    var draftAgentReasoningEffort by remember { mutableStateOf(agentDeepSeekReasoningEffort) }
    var draftAgentAutoConfirmSensitiveActions by remember { mutableStateOf(agentAutoConfirmSensitiveActions) }
    var draftAgentAutoVerifyEnabled by remember { mutableStateOf(agentAutoVerifyEnabled) }
    var draftAgentMaxAutoVerifies by remember { mutableStateOf(agentMaxAutoVerifies) }

    var showApiKey by remember { mutableStateOf(false) }
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
    var showSshConsole by remember { mutableStateOf(false) }
    var showSshFile by remember { mutableStateOf(false) }
    var showSshShell by remember { mutableStateOf(false) }
    var showSshAudit by remember { mutableStateOf(false) }
    val remoteNodes by viewModel.remoteNodes.collectAsState()
    val remoteCommandResult by viewModel.remoteCommandResult.collectAsState()
    val sshResult by viewModel.sshResult.collectAsState()
    val sshBootstrapResult by viewModel.sshBootstrapResult.collectAsState()
    val sshFileResult by viewModel.sshFileResult.collectAsState()
    val sshShellOutput by viewModel.sshShellOutput.collectAsState()
    val sshShellRunning by viewModel.sshShellRunning.collectAsState()
    val sshShellError by viewModel.sshShellError.collectAsState()
    val sshAuditText by viewModel.sshAuditText.collectAsState()
    val pendingSshHostKey by viewModel.pendingSshHostKey.collectAsState()
    val defaultRemoteHost = remoteNodes.firstOrNull()?.host.orEmpty()
    val savedSshCredential = remember(defaultRemoteHost) {
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
                TextButton(
                    onClick = {
                        viewModel.setDeepSeekApiKey(draftApiKey)
                        viewModel.setDeepSeekModel(draftModel)
                        viewModel.setDeepSeekThinkingEnabled(draftThinkingEnabled)
                        viewModel.setDeepSeekReasoningEffort(draftReasoningEffort)
                        viewModel.setAgentDeepSeekThinkingEnabled(draftAgentThinkingEnabled)
                        viewModel.setAgentDeepSeekReasoningEffort(draftAgentReasoningEffort)
                        viewModel.setAgentAutoConfirmSensitiveActions(draftAgentAutoConfirmSensitiveActions)
                        viewModel.setAgentAutoVerifyEnabled(draftAgentAutoVerifyEnabled)
                        viewModel.setAgentMaxAutoVerifies(draftAgentMaxAutoVerifies)
                        onClose()
                    },
                ) {
                    Text("保存")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsSectionCard(title = "模型与密钥") {
                        OutlinedTextField(
                            value = draftApiKey,
                            onValueChange = { draftApiKey = it },
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
                            onValueChange = { draftModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("模型（默认 deepseek-v4-flash-vision-exp）") },
                            singleLine = true,
                        )
                        SwitchRow(
                            title = "DeepSeek 思考模式",
                            subtitle = "默认关闭；开启后更准确但生成更慢",
                            checked = draftThinkingEnabled,
                            onCheckedChange = { draftThinkingEnabled = it },
                        )
                        if (draftThinkingEnabled) {
                            ReasoningSelector(
                                selected = draftReasoningEffort,
                                onSelect = { draftReasoningEffort = it },
                            )
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "智能助手行为") {
                        SwitchRow(
                            title = "Agent 推理",
                            subtitle = "默认开启，强度 max；仅影响 Agent 页面",
                            checked = draftAgentThinkingEnabled,
                            onCheckedChange = { draftAgentThinkingEnabled = it },
                        )
                        if (draftAgentThinkingEnabled) {
                            ReasoningSelector(
                                selected = draftAgentReasoningEffort,
                                onSelect = { draftAgentReasoningEffort = it },
                            )
                        }
                        SwitchRow(
                            title = "敏感操作自动执行",
                            subtitle = "开启后 Agent 不再弹出确认，直接执行发送/支付/删除等操作；建议仅测试或信任场景使用",
                            checked = draftAgentAutoConfirmSensitiveActions,
                            onCheckedChange = { draftAgentAutoConfirmSensitiveActions = it },
                        )
                        SwitchRow(
                            title = "自动截屏验证",
                            subtitle = "开启后每次改变界面的工具执行后自动截屏确认",
                            checked = draftAgentAutoVerifyEnabled,
                            onCheckedChange = { draftAgentAutoVerifyEnabled = it },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "每次最多自动验证次数",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { draftAgentMaxAutoVerifies = (draftAgentMaxAutoVerifies - 1).coerceAtLeast(0) }) {
                                Text("-")
                            }
                            Text(
                                text = draftAgentMaxAutoVerifies.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            TextButton(onClick = { draftAgentMaxAutoVerifies = (draftAgentMaxAutoVerifies + 1).coerceAtMost(20) }) {
                                Text("+")
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "语音识别") {
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
                    SettingsSectionCard(title = "条件触发器") {
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
                                    text = "${if (rule.enabled) "🟢" else "⚪️"} ${rule.name} · ${rule.condition.type}",
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
                    SettingsSectionCard(title = "远程节点") {
                        Text(
                            text = "已登记 ${remoteNodes.size} 个远程节点，支持只读命令、任务队列与 Skill 分发。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showRemoteNodes = true }) {
                            Text("管理远程节点")
                        }
                        TextButton(onClick = {
                            showSshConsole = true
                            viewModel.clearSshResult()
                        }) {
                            Text("SSH 命令终端")
                        }
                        TextButton(onClick = {
                            showSshFile = true
                            viewModel.clearSshFileResult()
                        }) {
                            Text("SSH 远程文件")
                        }
                        TextButton(onClick = {
                            showSshShell = true
                            viewModel.clearSshShellOutput()
                            viewModel.clearSshShellError()
                        }) {
                            Text("SSH 交互终端")
                        }
                        TextButton(onClick = {
                            showSshAudit = true
                            viewModel.loadSshAudit()
                        }) {
                            Text("查看 SSH 审计")
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "权限与系统") {
                        PermissionCheckSection(modifier = Modifier.fillMaxWidth())
                    }
                }

                item {
                    SettingsSectionCard(title = "高级 / 调试") {
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
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReasoningSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(text = "思考强度", style = MaterialTheme.typography.bodyMedium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected == "low", onClick = { onSelect("low") })
            Text("低", style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = selected == "medium", onClick = { onSelect("medium") })
            Text("中", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected == "high", onClick = { onSelect("high") })
            Text("高", style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = selected == "max", onClick = { onSelect("max") })
            Text("最大", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
