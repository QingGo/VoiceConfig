package com.voiceconfig.app.ui

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
import androidx.compose.ui.unit.dp
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.Task
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStatus
import com.voiceconfig.app.agent.AgentStepUi
import com.voiceconfig.app.agent.AgentStepStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 独立 Agent 页面：对话历史 + 任务事件 + 执行记录，支持分组查看。
 */
@Composable
fun AgentPage(
    initialTabIndex: Int = 0,
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    sessions: List<AgentSessionEntity>,
    messages: List<AgentMessageEntity>,
    agentSteps: List<AgentStepUi> = emptyList(),
    agentSkills: List<AgentSkill> = emptyList(),
    taskEvents: List<TaskEventEntity>,
    recentLogs: List<ExecutionLog>,
    tasks: List<Task>,
    selectedSessionId: Long?,
    isAgentBusy: Boolean,
    streamText: String,
    reasoningText: String,
    input: String,
    onInputChange: (String) -> Unit,
    initialLogTaskId: Long? = null,
    agentThinkingEnabled: Boolean,
    agentReasoningEffort: String,
    onAgentThinkingEnabledChange: (Boolean) -> Unit,
    onAgentReasoningEffortChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onSend: (String) -> Unit,
    onNewSession: () -> Unit,
    onShowSessions: () -> Unit,
    onStop: () -> Unit,
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onClearSession: (Long) -> Unit,
    onApproveSkill: (String) -> Unit = {},
    onRejectSkill: (String) -> Unit = {},
    onDeleteSkill: (String) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenAutomation: () -> Unit = {},
) {
    var showAgentThinking by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var showRuns by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedSessionId != null) onShowSessions() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedSessionId != null) {
                IconButton(onClick = onShowSessions) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回会话列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "智能助手",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (selectedSessionId == null) "选择一个会话，或新建对话" else "Agent · 多步操作 / 工具调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onNewSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("新建")
            }
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("推理设置") },
                        onClick = {
                            moreMenuExpanded = false
                            showAgentThinking = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("经验库") },
                        onClick = {
                            moreMenuExpanded = false
                            showSkills = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Agent 运行记录") },
                        onClick = {
                            moreMenuExpanded = false
                            showRuns = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("自动化任务") },
                        onClick = {
                            moreMenuExpanded = false
                            onOpenAutomation()
                        },
                    )
                    if (selectedSessionId != null) {
                        DropdownMenuItem(
                            text = { Text("清空当前会话") },
                            onClick = {
                                moreMenuExpanded = false
                                onClearSession(selectedSessionId)
                            },
                        )
                    }
                }
            }
        }

        // 两级结构：无选中会话 = 会话列表；有选中会话 = 对话详情
        ConversationTab(
            sessions = sessions,
            messages = messages,
            agentSteps = agentSteps,
            selectedSessionId = selectedSessionId,
            isAgentBusy = isAgentBusy,
            streamText = streamText,
            reasoningText = reasoningText,
            onSelectSession = onSelectSession,
            input = input,
            onInputChange = onInputChange,
            onSend = {
                if (input.isNotBlank()) {
                    onSend(input.trim())
                    onInputChange("")
                }
            },
            onStop = onStop,
            onNewSession = onNewSession,
            onRenameSession = onRenameSession,
            onDeleteSession = onDeleteSession,
            onClearSession = onClearSession,
            showInput = selectedSessionId != null,
        )
    }

    if (showAgentThinking) {
        AlertDialog(
            onDismissRequest = { showAgentThinking = false },
            title = { Text("推理设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "深度思考", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "默认开启，让 Agent 先思考再操作；更稳但更慢/更贵",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = agentThinkingEnabled,
                            onCheckedChange = onAgentThinkingEnabledChange,
                        )
                    }
                    if (agentThinkingEnabled) {
                        Text(text = "推理强度", style = MaterialTheme.typography.bodyMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                androidx.compose.material3.RadioButton(selected = agentReasoningEffort == "low", onClick = { onAgentReasoningEffortChange("low") })
                                Text("低", style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.RadioButton(selected = agentReasoningEffort == "medium", onClick = { onAgentReasoningEffortChange("medium") })
                                Text("中", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                androidx.compose.material3.RadioButton(selected = agentReasoningEffort == "high", onClick = { onAgentReasoningEffortChange("high") })
                                Text("高", style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.RadioButton(selected = agentReasoningEffort == "max", onClick = { onAgentReasoningEffortChange("max") })
                                Text("最大", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentThinking = false }) {
                    Text("完成")
                }
            },
        )
    }

    if (showSkills) {
        SkillLibraryDialog(
            skills = agentSkills,
            onDismiss = { showSkills = false },
            onApprove = onApproveSkill,
            onReject = onRejectSkill,
            onDelete = onDeleteSkill,
        )
    }

    if (showRuns) {
        AgentRunsDialog(
            sessions = sessions,
            onDismiss = { showRuns = false },
            onSelect = { sessionId ->
                showRuns = false
                onSelectSession(sessionId)
            },
        )
    }

}

@Composable
private fun SkillLibraryDialog(
    skills: List<AgentSkill>,
    onDismiss: () -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("经验库") },
        text = {
            if (skills.isEmpty()) {
                Text("暂无经验。成功完成 Agent 任务后会自动沉淀为待审核经验。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(skills, key = { it.id }) { skill ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = skill.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = when (skill.status) {
                                            AgentSkillStatus.APPROVED -> "已通过"
                                            AgentSkillStatus.PENDING -> "待审核"
                                            AgentSkillStatus.REJECTED -> "已拒绝"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (skill.status) {
                                            AgentSkillStatus.APPROVED -> MaterialTheme.colorScheme.primary
                                            AgentSkillStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                                            AgentSkillStatus.REJECTED -> MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                                Text(
                                    text = "${skill.steps.size} 步 · 成功 ${skill.successCount} 次 · 使用 ${skill.useCount} 次",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = skill.steps.joinToString(" → ") { "${it.toolName}(${it.args.take(40)})" }.take(160),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (skill.status != AgentSkillStatus.APPROVED) {
                                        TextButton(onClick = { onApprove(skill.id) }) {
                                            Text("通过")
                                        }
                                    }
                                    if (skill.status != AgentSkillStatus.REJECTED) {
                                        TextButton(onClick = { onReject(skill.id) }) {
                                            Text("拒绝")
                                        }
                                    }
                                    TextButton(onClick = { onDelete(skill.id) }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun AgentRunsDialog(
    sessions: List<AgentSessionEntity>,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 运行记录") },
        text = {
            if (sessions.isEmpty()) {
                Text("暂无 Agent 执行会话")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Card(
                            onClick = { onSelect(session.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(session.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${session.messageCount} 条消息 · ${formatTime(session.updatedAtEpochMillis)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ConversationTab(
    sessions: List<AgentSessionEntity>,
    messages: List<AgentMessageEntity>,
    agentSteps: List<AgentStepUi> = emptyList(),
    selectedSessionId: Long?,
    isAgentBusy: Boolean,
    streamText: String,
    reasoningText: String,
    onSelectSession: (Long) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit = {},
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onClearSession: (Long) -> Unit,
    showInput: Boolean = true,
    onApproveSkill: (String) -> Unit = {},
    onRejectSkill: (String) -> Unit = {},
    onDeleteSkill: (String) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
    var clearTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedSessionId == null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupSessionsByDay(sessions).forEach { (day, daySessions) ->
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
                if (sessions.isEmpty()) {
                    item {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "还没有会话，点击“新建”开始。创建后会在会话列表中保留历史。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onNewSession) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("新建会话")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "智能助手适合多步骤、跨应用、需要工具调用的复杂指令；简单定时任务请在「自动化」中创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            ConversationMessages(
                modifier = Modifier.weight(1f),
                messages = messages,
                agentSteps = agentSteps,
                isAgentBusy = isAgentBusy,
                streamText = streamText,
                reasoningText = reasoningText,
            )
        }

        if (showInput) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入指令…") },
                    singleLine = true,
                    enabled = !isAgentBusy,
                )
                if (isAgentBusy) {
                    Button(onClick = onStop) {
                        Text("停止")
                    }
                } else {
                    Button(onClick = onSend, enabled = input.isNotBlank()) {
                        Text("发送")
                    }
                }
            }
        }
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
private fun ConversationMessages(
    modifier: Modifier = Modifier,
    messages: List<AgentMessageEntity>,
    agentSteps: List<AgentStepUi> = emptyList(),
    isAgentBusy: Boolean,
    streamText: String,
    reasoningText: String,
) {
    val listState: LazyListState = rememberLazyListState()
    val visibleMessages = remember(messages) {
        messages.filterNot { msg ->
            msg.role == "assistant" &&
                msg.content.isBlank() &&
                msg.toolCallsJson.isNullOrBlank() &&
                msg.reasoningContent.isNullOrBlank()
        }
    }
    val lastIndex = if (isAgentBusy) visibleMessages.size else (visibleMessages.size - 1).coerceAtLeast(0)
    LaunchedEffect(visibleMessages.size, isAgentBusy, streamText) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (visibleMessages.isEmpty() && !isAgentBusy) {
            item {
                Text(
                    "这是一个新会话。输入一句话，例如“帮我打开瑞幸咖啡并点一杯生椰拿铁”，Agent 会持续查看屏幕并操作，直到完成目标。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (agentSteps.isNotEmpty()) {
            item {
                AgentStepTimeline(steps = agentSteps)
            }
        }
        items(visibleMessages, key = { it.id }) { msg ->
            MessageBubble(msg)
        }
        if (isAgentBusy) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Agent 正在执行",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "可点击「停止」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (reasoningText.isNotBlank()) {
                            Text(
                                text = "思考中：$reasoningText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                            )
                        }
                        if (streamText.isNotBlank()) {
                            Text(
                                text = streamText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 8,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepTimeline(steps: List<AgentStepUi>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("执行时间线", style = MaterialTheme.typography.labelLarge)
            steps.takeLast(30).forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${step.index + 1}. ${stepStatusIcon(step.status)} ${step.toolName}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (step.message.isNotBlank()) {
                        Text(
                            text = step.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun stepStatusIcon(status: AgentStepStatus): String = when (status) {
    AgentStepStatus.RUNNING -> "⏳"
    AgentStepStatus.SUCCESS -> "✅"
    AgentStepStatus.FAILED -> "❌"
    AgentStepStatus.DECLINED -> "⛔"
}

@Composable
private fun MessageBubble(msg: AgentMessageEntity) {
    if (msg.role == "tool") {
        ToolResultCard(msg)
        return
    }
    val isUser = msg.role == "user"
    val isEmptyAssistant = msg.role == "assistant" &&
        msg.content.isBlank() &&
        msg.toolCallsJson.isNullOrBlank() &&
        msg.reasoningContent.isNullOrBlank()
    if (isEmptyAssistant) return

    val isToolCall = msg.role == "assistant" && !msg.toolCallsJson.isNullOrBlank()
    val isReasoningOnly = msg.role == "assistant" &&
        msg.content.isBlank() &&
        !msg.reasoningContent.isNullOrBlank()
    val displayText = when {
        msg.content.isNotBlank() -> msg.content
        isToolCall -> "Agent 正在调用工具…"
        isReasoningOnly -> "Agent 正在思考…"
        else -> ""
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = if (isUser) "你" else "Agent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (displayText.isNotBlank()) {
                SelectionContainer {
                    Text(displayText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ToolResultCard(msg: AgentMessageEntity) {
    val ok = msg.toolResultOk ?: false
    var expanded by remember { mutableStateOf(false) }
    val fullText = msg.content
    val showExpand = fullText.length > 200
    val displayText = if (showExpand && !expanded) fullText.take(200) + "…" else fullText
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = "${if (ok) "✅" else "❌"} 工具：${msg.toolName ?: "未知"}",
                style = MaterialTheme.typography.labelLarge,
            )
            SelectionContainer {
                Text(displayText, style = MaterialTheme.typography.bodyMedium)
            }
            if (showExpand) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起完整输出" else "展开完整输出（${fullText.length} 字）")
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

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()
        .substring(0, 16)
