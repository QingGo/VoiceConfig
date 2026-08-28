package com.voiceconfig.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.Task
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.app.R
import com.voiceconfig.app.agent.AgentRunRecord
import com.voiceconfig.app.agent.AgentRunState
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStatus
import com.voiceconfig.app.agent.AgentStepUi
import com.voiceconfig.app.agent.TaskPlan
import com.voiceconfig.app.agent.AgentStepStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun todaySuggestion(): String {
    return when (LocalTime.now().hour) {
        in 5..10 -> "早上好，试试：每天早上 8 点自动打开企业微信"
        in 11..13 -> "中午好，试试：帮我点一杯瑞幸咖啡"
        in 14..17 -> "下午好，试试：帮我整理一下今天的任务"
        else -> "晚上好，试试：提醒我明天早上 8 点打开企业微信"
    }
}

@Composable
private fun HomeHero(
    sessionCount: Int,
    onNewSession: () -> Unit,
    onShowHistory: () -> Unit,
    onContinueLast: (() -> Unit)? = null,
    onSuggestion: (String) -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "今天想让我做什么？",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "直接说，或者输入一句话。复杂任务我会自己拆解、执行并确认。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(
                onClick = { onSuggestion(todaySuggestion()) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    text = todaySuggestion(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onNewSession,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始新对话")
                }
                if (onContinueLast != null) {
                    TextButton(onClick = onContinueLast) {
                        Text("继续上次")
                    }
                }
                TextButton(onClick = onShowHistory) {
                    Text("历史 (${sessionCount})")
                }
            }
        }
    }
}

@Composable
internal fun HomeQuickActions(
    onQuickAction: (String) -> Unit,
    onOpenShopping: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "快捷任务",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "点击后自动新建会话并填入指令，发送前由你确认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickActionTile(
                    title = "打开企业微信",
                    subtitle = "定时 / 自动准备",
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickAction("每天早上 8 点打开企业微信") },
                )
                QuickActionTile(
                    title = "点杯瑞幸",
                    subtitle = "帮我下单",
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickAction("帮我点一杯瑞幸咖啡") },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickActionTile(
                    title = "回复微信",
                    subtitle = "智能起草并发送",
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickAction("帮我回复微信里的新消息") },
                )
                QuickActionTile(
                    title = "母婴比价",
                    subtitle = "研究 / 对比",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenShopping,
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
internal fun ConversationTab(
    sessions: List<AgentSessionEntity>,
    messages: List<AgentMessageEntity>,
    agentSteps: List<AgentStepUi> = emptyList(),
    lastRunDurationMs: Long? = null,
    selectedSessionId: Long?,
    isAgentBusy: Boolean,
    streamText: String,
    reasoningText: String,
    onSelectSession: (Long) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onQuickAction: (String) -> Unit = {},
    onVoiceInput: () -> Unit = {},
    isListening: Boolean = false,
    onOpenShopping: () -> Unit = {},
    onClearAllSessions: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit = {},
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onClearSession: (Long) -> Unit,
    showInput: Boolean = true,
    hasDeepSeekKey: Boolean = true,
    agentVoiceAutoSend: Boolean = false,
    onAgentVoiceAutoSendChange: (Boolean) -> Unit = {},
    agentTtsEnabled: Boolean = false,
    onAgentTtsEnabledChange: (Boolean) -> Unit = {},
    wakeWordEnabled: Boolean = false,
    onWakeWordEnabledChange: (Boolean) -> Unit = {},
    onApproveSkill: (String) -> Unit = {},
    onRejectSkill: (String) -> Unit = {},
    onDeleteSkill: (String) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
    var clearTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var historySearch by remember { mutableStateOf("") }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedSessionId == null) {
            if (!showHistory) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "hero") {
                        HomeHero(
                            sessionCount = sessions.size,
                            onNewSession = onNewSession,
                            onShowHistory = { showHistory = true },
                            onContinueLast = sessions
                                .maxByOrNull { it.updatedAtEpochMillis }
                                ?.let { last -> { onSelectSession(last.id) } },
                            onSuggestion = onQuickAction,
                        )
                    }
                    item(key = "quick_actions") {
                        HomeQuickActions(
                            onQuickAction = onQuickAction,
                            onOpenShopping = onOpenShopping,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "history_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { showHistory = false }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("返回")
                            }
                            if (sessions.isNotEmpty()) {
                                TextButton(onClick = { showClearHistoryDialog = true }) {
                                    Text("清空", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "历史会话",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    val filteredSessions = if (historySearch.isBlank()) {
                        sessions
                    } else {
                        sessions.filter { it.title.contains(historySearch, ignoreCase = true) }
                    }
                    item(key = "history_search") {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索历史会话") },
                            singleLine = true,
                        )
                    }
                    groupSessionsByDay(filteredSessions).forEach { (day, daySessions) ->
                        item(key = "day_$day") {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(daySessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                menuExpanded = menuFor == session.id,
                                onMenuToggle = { menuFor = if (menuFor == session.id) null else session.id },
                                onMenuDismiss = { menuFor = null },
                                onClick = { onSelectSession(session.id) },
                                onRename = {
                                    menuFor = null
                                    renameTarget = session
                                    renameText = session.title
                                },
                                onClear = {
                                    menuFor = null
                                    clearTarget = session
                                },
                                onDelete = {
                                    menuFor = null
                                    deleteTarget = session
                                },
                            )
                        }
                    }
                    if (filteredSessions.isEmpty()) {
                        item {
                            Text(
                                text = if (sessions.isEmpty()) "还没有历史会话，开始第一次对话吧。" else "没有匹配的历史会话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            ConversationMessages(
                modifier = Modifier.weight(1f),
                messages = messages,
                agentSteps = agentSteps,
                lastRunDurationMs = lastRunDurationMs,
                isAgentBusy = isAgentBusy,
                streamText = streamText,
                reasoningText = reasoningText,
            )
        }

        if (showInput) {
            VoiceCommandBar(
                input = input,
                onInputChange = onInputChange,
                onSend = onSend,
                onVoiceInput = onVoiceInput,
                isBusy = isAgentBusy,
                onStop = onStop,
                isListening = isListening,
            )
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("清空历史会话") },
            text = { Text("将删除全部会话、消息、步骤和任务事件，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAllSessions()
                    showClearHistoryDialog = false
                    showHistory = false
                    historySearch = ""
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRenameSession(session.id, renameText.trim())
                        }
                        renameTarget = null
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定删除“${session.title}”吗？会话中的消息将一并删除，且不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(session.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    clearTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("清空消息") },
            text = { Text("确定清空“${session.title}”的全部消息吗？会话会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearSession(session.id)
                        clearTarget = null
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}
@Composable
private fun SessionCard(
    session: AgentSessionEntity,
    menuExpanded: Boolean,
    onMenuToggle: () -> Unit,
    onMenuDismiss: () -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(session.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${session.messageCount} 条消息 · ${formatTime(session.updatedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = onMenuToggle) {
                    Icon(Icons.Default.MoreVert, contentDescription = "会话操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onMenuDismiss,
                ) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                    DropdownMenuItem(text = { Text("清空消息") }, onClick = onClear)
                    DropdownMenuItem(text = { Text("删除会话") }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun TaskEventsTab(taskEvents: List<TaskEventEntity>, tasks: List<Task>) {
    val grouped = remember(taskEvents) { groupTaskEvents(taskEvents) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (grouped.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("暂无任务创建/修改记录")
                    Text(
                        "去「对话」Tab 发送指令，创建/修改任务后会在这里显示记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        grouped.forEach { (taskId, events) ->
            val task = tasks.firstOrNull { it.id == taskId }
            item(key = "task_header_$taskId") {
                Text(
                    text = task?.title ?: if (taskId == null) "未关联任务" else "任务 #$taskId",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            items(events, key = { it.id }) { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(event.summary, style = MaterialTheme.typography.bodyLarge)
                        event.rawText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${event.eventType} · ${formatTime(event.createdAtEpochMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionTab(
    recentLogs: List<ExecutionLog>,
    tasks: List<Task>,
    initialLogTaskId: Long? = null,
    onOpenTask: (Long) -> Unit = {},
) {
    var filter by remember { mutableStateOf("") }
    var taskFilterId by remember(initialLogTaskId) { mutableStateOf(initialLogTaskId) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val filteredLogs = remember(filter, taskFilterId, recentLogs, tasks) {
        val keyword = filter.trim()
        recentLogs.filter { log ->
            val matchesTask = taskFilterId == null || log.taskId == taskFilterId
            if (!matchesTask) return@filter false
            if (keyword.isBlank()) return@filter true
            val task = tasks.firstOrNull { it.id == log.taskId }
            val haystack = buildString {
                append(task?.title ?: "任务 #${log.taskId}")
                append(' ')
                append(readableStatus(log.status))
                append(' ')
                append(readableMode(log.executionMode))
                append(' ')
                append(log.message ?: "")
            }
            haystack.contains(keyword, ignoreCase = true)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索任务、状态或消息") },
                singleLine = true,
            )
        }
        taskFilterId?.let { taskId ->
            val task = tasks.firstOrNull { it.id == taskId }
            item(key = "task_filter_$taskId") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "只看：${task?.title ?: "任务 #$taskId"}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { taskFilterId = null }) {
                        Text("清除筛选")
                    }
                }
            }
        }
        if (filteredLogs.isEmpty()) {
            item { Text("暂无运行日志") }
        }
        items(filteredLogs, key = { it.id }) { log ->
            val task = tasks.firstOrNull { it.id == log.taskId }
            val expanded = expandedId == log.id
            Card(
                onClick = { expandedId = if (expanded) null else log.id },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = task?.title ?: "任务 #${log.taskId}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${formatTime(log.scheduledAtEpochMillis)} · ${readableStatus(log.status)} · ${readableMode(log.executionMode)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (expanded) "点击收起原始日志" else "点击展开原始日志",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (expanded) {
                        log.message?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        log.agentSessionId?.let { sessionId ->
                            Text(
                                "关联会话 #$sessionId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = { onOpenTask(log.taskId) }) {
                            Text("回首页查看任务")
                        }
                    }
                }
            }
        }
    }
}

private fun readableStatus(status: ExecutionStatus): String = when (status) {
    ExecutionStatus.SCHEDULED -> "已调度"
    ExecutionStatus.EXECUTING -> "执行中"
    ExecutionStatus.SUCCESS -> "成功"
    ExecutionStatus.FAILED -> "失败"
    ExecutionStatus.SKIPPED -> "已跳过"
    ExecutionStatus.FALLBACK -> "已降级"
    ExecutionStatus.WAITING_HUMAN -> "等待用户确认"
}

private fun readableMode(mode: ExecutionMode?): String = when (mode) {
    ExecutionMode.AUTO -> "自动"
    ExecutionMode.NOTIFICATION -> "通知提醒"
    ExecutionMode.DEEP_LINK -> "Deep Link"
    ExecutionMode.SHIZUKU -> "Shizuku 高级执行"
    ExecutionMode.ACCESSIBILITY -> "无障碍"
    ExecutionMode.AGENT -> "Agent"
    null -> "-"
}

private fun groupSessionsByDay(sessions: List<AgentSessionEntity>): List<Pair<String, List<AgentSessionEntity>>> {
    val today = LocalDate.now()
    return sessions.groupBy { session ->
        val date = Instant.ofEpochMilli(session.updatedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> date.toString()
        }
    }.toList()
}

private fun groupTaskEvents(events: List<TaskEventEntity>): List<Pair<Long?, List<TaskEventEntity>>> =
    events.groupBy { it.taskId }.toList()

internal fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()
        .substring(0, 16)


internal fun taskPlanStatusText(plan: TaskPlan): String = when {
    plan.waitingForHuman != null -> "等待确认：${plan.waitingForHuman}"
    plan.status.name == "WAITING_CONFIRM" -> "等待确认"
    plan.status.name == "ACTIVE" -> "进行中"
    plan.status.name == "COMPLETED" -> "已完成"
    plan.status.name == "FAILED" -> "失败"
    plan.status.name == "CANCELLED" -> "已取消"
    else -> plan.status.name
}
