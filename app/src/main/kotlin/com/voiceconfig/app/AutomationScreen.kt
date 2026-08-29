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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreenContent(
    uiState: MainUiState,
    deepSeekApiKey: String = "",
    installedAppLabels: Map<String, String>,
    tasks: List<Task>,
    templates: List<Template>,
    recentLogs: List<ExecutionLog>,
    onInputChange: (String) -> Unit,
    isListening: Boolean,
    isPreparing: Boolean,
    onMicClick: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onCreateByAgent: () -> Unit = {},
    onOpenAgentLogs: (Task) -> Unit,
    onOpenAgentSession: (Long) -> Unit = {},
    showCreatePanel: Boolean,
    onCreatePanelChange: (Boolean) -> Unit,
    onManualPackageChange: (String) -> Unit,
    onManualDeepLinkChange: (String) -> Unit,
    onParse: () -> Unit,
    onConfirmCreate: () -> Unit,
    onClearResult: () -> Unit,
    onToggleTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onCopyTask: (Task) -> Unit,
    onRunNow: (Task) -> Unit,
    onSummarizeLogs: () -> Unit,
    onSaveTemplate: (String) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    onExportTemplates: () -> Unit,
    onImportTemplates: () -> Unit,
    onTemplateSelected: (Template) -> Unit,
) {
    var manageTemplates by remember { mutableStateOf(false) }
    var templatesExpanded by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showSaveTemplate by remember { mutableStateOf(false) }
    var draftTemplateName by remember { mutableStateOf("") }
    var noKeyHintDismissed by remember { mutableStateOf(false) }
    var taskSearch by remember { mutableStateOf("") }
    var taskStatusFilter by remember { mutableStateOf<String?>(null) }
    val filteredTasks = tasks
        .filter { task ->
            when (taskStatusFilter) {
                "enabled" -> task.enabled
                "disabled" -> !task.enabled
                else -> true
            }
        }
        .filter { task ->
            taskSearch.isBlank() ||
                formatTaskTitle(task, installedAppLabels).contains(taskSearch, ignoreCase = true) ||
                task.rawText.contains(taskSearch, ignoreCase = true)
        }
    val sortedTemplates = templates.sortedByDescending { it.usageCount }
    BackHandler(enabled = showCreatePanel) {
        onCreatePanelChange(false)
    }
    BackHandler(enabled = templatesExpanded) {
        templatesExpanded = false
    }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .padding(bottom = if (showCreatePanel) 200.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "自动化",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { templatesExpanded = true }) {
                    Text("模板")
                }
                IconButton(onClick = onOpenAiSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                text = "共 ${tasks.size} 个任务 · ${tasks.count { it.enabled }} 个已启用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            Text(
                text = "适合简单定时任务；复杂指令 / 工具调用 / 调试请用「智能助手」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCreateByAgent,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("让言控创建")
                }
                OutlinedButton(
                    onClick = { onCreatePanelChange(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("手动创建")
                }
            }
        }
        if (deepSeekApiKey.isBlank() && !noKeyHintDismissed) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "大模型未配置 · 简单自动化仍可用",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = { noKeyHintDismissed = true }) {
                        Text("知道了")
                    }
                }
            }
        }
        uiState.parseMessage?.let {
            item {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        uiState.parsedDraft?.let { draft ->
            item {
                var showDetails by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "任务预览", style = MaterialTheme.typography.titleMedium)
                        val previewAction = when (draft.actionType) {
                            ActionType.NOTIFY -> "提醒"
                            ActionType.OPEN_DEEPLINK -> "打开${draft.deepLink ?: "页面"}"
                            ActionType.AGENT -> "智能助手执行"
                            else -> "打开${displayAppName(draft.targetPackage, installedAppLabels)}"
                        }
                        Text(
                            text = previewAction,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        draft.schedule?.let {
                            Text(
                                text = formatScheduleText(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showDetails = !showDetails }) {
                            Text(if (showDetails) "隐藏详细参数" else "查看详细参数")
                        }
                        if (showDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = "动作：${draft.actionType}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "目标包名：${draft.targetPackage ?: "无"}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "调度：${draft.schedule ?: "无"}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "置信度：${draft.confidence}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (draft.actionType == ActionType.OPEN_APP && draft.targetPackage.isNullOrBlank()) {
                            OutlinedTextField(
                                value = uiState.manualPackage,
                                onValueChange = onManualPackageChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("目标App包名（手动填写）") },
                            )
                        }
                        if (draft.actionType == ActionType.OPEN_DEEPLINK && draft.deepLink.isNullOrBlank()) {
                            OutlinedTextField(
                                value = uiState.manualDeepLink,
                                onValueChange = onManualDeepLinkChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Deep Link（手动填写）") },
                            )
                        }
                        Button(onClick = onConfirmCreate, modifier = Modifier.fillMaxWidth()) {
                            Text("确认创建")
                        }
                        OutlinedButton(
                            onClick = {
                                draftTemplateName = ""
                                showSaveTemplate = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存为模板")
                        }
                        TextButton(onClick = onClearResult) {
                            Text("取消")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            OutlinedTextField(
                value = taskSearch,
                onValueChange = { taskSearch = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索自动化任务") },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    null to "全部",
                    "enabled" to "已启用",
                    "disabled" to "已停用",
                ).forEach { (value, label) ->
                    val selected = taskStatusFilter == value
                    if (selected) {
                        Button(
                            onClick = { taskStatusFilter = value },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        ) { Text(label) }
                    } else {
                        TextButton(
                            onClick = { taskStatusFilter = value },
                            modifier = Modifier.weight(1f),
                        ) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (filteredTasks.isEmpty()) {
            item {
                Text(text = "我的任务（0）", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (tasks.isEmpty()) "还没有创建任务" else "没有匹配的任务",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (tasks.isEmpty()) "试试说：“每天上午10点提醒我喝水”" else "换个关键词试试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (tasks.isEmpty()) {
                            Button(onClick = { onCreatePanelChange(true) }) {
                                Text("立即创建")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Text(text = "我的任务（${filteredTasks.size}）", style = MaterialTheme.typography.titleMedium)
            }
            items(filteredTasks, key = { "task_${it.id}" }) { task ->
                val lastLog = recentLogs
                    .filter { it.taskId == task.id }
                    .maxByOrNull { it.scheduledAtEpochMillis }
                TaskRow(
                    task = task,
                    installedAppLabels = installedAppLabels,
                    lastLog = lastLog,
                    onToggle = { onToggleTask(task) },
                    onDelete = { onDeleteTask(task) },
                    onCopy = { onCopyTask(task) },
                    onRunNow = { onRunNow(task) },
                    onOpenLogs = { onOpenAgentLogs(task) },
                )
            }
        }
        item {
            HorizontalDivider()
        }
        if (recentLogs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "最近执行",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "按时间查看每次任务的执行结果和失败原因",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "收起" else "展开")
                    }
                    TextButton(
                        onClick = onSummarizeLogs,
                        enabled = !uiState.isSummarizing,
                    ) {
                        Text(if (uiState.isSummarizing) "总结中..." else "AI总结")
                    }
                }
            }
            if (showLogs) {
                items(recentLogs, key = { "log_${it.id}" }) { log ->
                    ExecutionLogRow(
                        log = log,
                        tasks = tasks,
                        installedAppLabels = installedAppLabels,
                        onOpenAgentSession = onOpenAgentSession,
                    )
                }
                uiState.logSummary?.let { summary ->
                    item {
                        Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                            Text(
                                text = summary,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(text = "最近执行", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "暂无执行记录，创建并执行任务后会显示在这里",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onCreateByAgent) {
                            Text("让言控创建")
                        }
                    }
                }
            }
        }
    }

        if (templatesExpanded) {
            TemplatesOverlay(
                templates = templates,
                manageTemplates = manageTemplates,
                onManageTemplatesChange = { manageTemplates = it },
                onBack = { templatesExpanded = false },
                onNewTemplate = {
                    draftTemplateName = ""
                    showSaveTemplate = true
                },
                onSelectTemplate = { template ->
                    onTemplateSelected(template)
                    templatesExpanded = false
                    onCreatePanelChange(true)
                },
                onDeleteTemplate = onDeleteTemplate,
                onExportTemplates = onExportTemplates,
                onImportTemplates = onImportTemplates,
            )
        }

        if (showCreatePanel) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 88.dp, bottom = 88.dp),
                shape = MaterialTheme.shapes.large,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                when {
                                    isPreparing -> "正在准备语音模型..."
                                    isListening -> "正在聆听..."
                                    else -> "例：每天上午10点提醒我喝水"
                                },
                            )
                        },
                        minLines = 1,
                        maxLines = 2,
                    )
                    Button(
                        onClick = onParse,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isParsing,
                    ) {
                        if (uiState.isParsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("生成中...")
                        } else {
                            Text("生成任务")
                        }
                    }
                }
            }
        }

    }

    if (showSaveTemplate) {
        AlertDialog(
            onDismissRequest = { showSaveTemplate = false },
            title = { Text("保存为模板") },
            text = {
                OutlinedTextField(
                    value = draftTemplateName,
                    onValueChange = { draftTemplateName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模板名称") },
                    placeholder = { Text("例如：喝水提醒") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveTemplate(draftTemplateName)
                        showSaveTemplate = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplate = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TemplatesOverlay(
    templates: List<Template>,
    manageTemplates: Boolean,
    onManageTemplatesChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNewTemplate: () -> Unit,
    onSelectTemplate: (Template) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    onExportTemplates: () -> Unit,
    onImportTemplates: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回")
                }
                Text(
                    text = "模板库",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (manageTemplates) {
                    TextButton(onClick = { onManageTemplatesChange(false) }) {
                        Text("完成")
                    }
                } else {
                    TextButton(onClick = onNewTemplate) {
                        Text("新建模板")
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (manageTemplates) "完成管理" else "管理模板") },
                            onClick = {
                                menuExpanded = false
                                onManageTemplatesChange(!manageTemplates)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出全部") },
                            onClick = {
                                menuExpanded = false
                                onExportTemplates()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入") },
                            onClick = {
                                menuExpanded = false
                                onImportTemplates()
                            },
                        )
                    }
                }
            }
            if (templates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无模板")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(templates.sortedByDescending { it.usageCount }, key = { it.id }) { template ->
                        Card(
                            onClick = { onSelectTemplate(template) },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(template.name, style = MaterialTheme.typography.bodyLarge)
                                    template.description.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(top = 4.dp),
                                    ) {
                                        Text(
                                            template.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (manageTemplates) {
                                    TextButton(onClick = { onDeleteTemplate(template) }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenContentPreview() {
    VoiceConfigTheme {
        MainScreenContent(
            uiState = MainUiState(
                input = "每天上午10点提醒我喝水",
                parsedDraft = TaskDraft(
                    rawText = "每天上午10点提醒我喝水",
                    schedule = null,
                    actionType = ActionType.OPEN_APP,
                    targetPackage = null,
                ),
                parseMessage = "解析成功，请确认任务",
            ),
            installedAppLabels = emptyMap(),
            tasks = emptyList(),
            templates = emptyList(),
            recentLogs = emptyList(),
            onInputChange = {},
            isListening = false,
            isPreparing = false,
            onMicClick = {},
            onOpenAiSettings = {},
            onOpenAgent = {},
            onOpenAgentLogs = {},
            showCreatePanel = false,
            onCreatePanelChange = {},
            onManualPackageChange = {},
            onManualDeepLinkChange = {},
            onParse = {},
            onConfirmCreate = {},
            onClearResult = {},
            onToggleTask = {},
            onDeleteTask = {},
            onCopyTask = {},
            onRunNow = {},
            onSummarizeLogs = {},
            onSaveTemplate = { _ -> },
            onDeleteTemplate = {},
            onExportTemplates = {},
            onImportTemplates = {},
            onTemplateSelected = {},
        )
    }
}
