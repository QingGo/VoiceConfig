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
    lastRunDurationMs: Long? = null,
    agentSkills: List<AgentSkill> = emptyList(),
    agentRunRecords: List<AgentRunRecord> = emptyList(),
    agentRunDetail: List<Map<String, Any?>> = emptyList(),
    onSelectRun: (String) -> Unit = {},
    onEnableAccessibility: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    taskEvents: List<TaskEventEntity>,
    recentLogs: List<ExecutionLog>,
    tasks: List<Task>,
    selectedSessionId: Long?,
    isAgentBusy: Boolean,
    streamText: String,
    reasoningText: String,
    input: String,
    onInputChange: (String) -> Unit,
    onQuickAction: (String) -> Unit = {},
    onVoiceInput: () -> Unit = {},
    isListening: Boolean = false,
    onOpenShopping: () -> Unit = {},
    hasDeepSeekKey: Boolean = true,
    agentVoiceAutoSend: Boolean = false,
    onAgentVoiceAutoSendChange: (Boolean) -> Unit = {},
    agentTtsEnabled: Boolean = false,
    onAgentTtsEnabledChange: (Boolean) -> Unit = {},
    wakeWordEnabled: Boolean = false,
    onWakeWordEnabledChange: (Boolean) -> Unit = {},
    initialLogTaskId: Long? = null,
    canResumeTask: Boolean = false,
    activeTaskPlans: List<TaskPlan> = emptyList(),
    onResumeTask: () -> Unit = {},
    onResumeTaskPlan: (String) -> Unit = {},
    onCancelResumeTask: () -> Unit = {},
    onCancelTaskPlan: (String) -> Unit = {},
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
    onToggleSkillEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onRedactSkill: (String) -> Unit = {},
    onExportAllSkills: () -> Unit = {},
    onImportSkillsFromClipboard: () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenAutomation: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var showAgentThinking by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var showRuns by remember { mutableStateOf(false) }
    var selectedRunId by remember { mutableStateOf<String?>(null) }
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
                    text = "言控",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (selectedSessionId == null) "你的个人生活与工作智能助手" else "Agent · 多步操作 / 工具调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedSessionId != null) {
                FilledTonalButton(onClick = onNewSession) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建")
                }
            }
            if (selectedSessionId != null) {
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
                        text = { Text("全局设置") },
                        onClick = {
                            moreMenuExpanded = false
                            onOpenSettings()
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
        }

        if (!hasDeepSeekKey) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "大模型未配置",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text("去设置")
                    }
                }
            }
        }

        val latestRun = agentRunRecords.firstOrNull()
        val needsAccessibilityHelp = latestRun != null && (
            latestRun.verified == false ||
            latestRun.message.contains("无障碍") ||
            (latestRun.capabilitySummary?.contains("Accessibility=N") == true)
        )
        if (needsAccessibilityHelp) {
            AccessibilityHelpCard(
                onEnableViaShizuku = onEnableAccessibility,
                onOpenSettings = onOpenAccessibilitySettings,
            )
        }

        if (canResumeTask && activeTaskPlans.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "有未完成的任务（${activeTaskPlans.size}）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    activeTaskPlans.take(3).forEach { plan ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = plan.goal.ifBlank { "未命名任务" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = taskPlanStatusText(plan),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    if (plan.steps.isNotEmpty()) {
                                        Text(
                                            text = "${plan.steps.size} 个步骤：${plan.steps.take(3).joinToString("、") { it.title }}${if (plan.steps.size > 3) "…" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                TextButton(onClick = { onCancelTaskPlan(plan.id) }) {
                                    Text("不再提醒")
                                }
                            }
                            TextButton(onClick = { onResumeTaskPlan(plan.id) }) {
                                Text("继续此任务")
                            }
                        }
                    }
                    if (activeTaskPlans.size > 3) {
                        Text(
                            text = "还有 ${activeTaskPlans.size - 3} 个未完成任务未展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onResumeTask) {
                            Text("继续最新")
                        }
                        TextButton(onClick = onCancelResumeTask) {
                            Text("放弃全部")
                        }
                    }
                }
            }
        }

        // 两级结构：无选中会话 = 会话列表；有选中会话 = 对话详情
        ConversationTab(
            sessions = sessions,
            messages = messages,
            agentSteps = agentSteps,
            lastRunDurationMs = lastRunDurationMs,
            selectedSessionId = selectedSessionId,
            isAgentBusy = isAgentBusy,
            streamText = streamText,
            reasoningText = reasoningText,
            onSelectSession = onSelectSession,
            input = input,
            onInputChange = onInputChange,
            onQuickAction = onQuickAction,
            onVoiceInput = onVoiceInput,
            isListening = isListening,
            onOpenShopping = onOpenShopping,
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
            showInput = true,
            hasDeepSeekKey = hasDeepSeekKey,
            agentVoiceAutoSend = agentVoiceAutoSend,
            onAgentVoiceAutoSendChange = onAgentVoiceAutoSendChange,
            agentTtsEnabled = agentTtsEnabled,
            onAgentTtsEnabledChange = onAgentTtsEnabledChange,
            wakeWordEnabled = wakeWordEnabled,
            onWakeWordEnabledChange = onWakeWordEnabledChange,
            onOpenSettings = onOpenSettings,
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
            onToggleEnabled = onToggleSkillEnabled,
            onRedact = onRedactSkill,
            onExportAll = onExportAllSkills,
            onImportFromClipboard = onImportSkillsFromClipboard,
        )
    }

    if (showRuns) {
        AgentRunsDialog(
            records = agentRunRecords,
            onDismiss = { showRuns = false },
            onSelectSession = { sessionId ->
                showRuns = false
                onSelectSession(sessionId)
            },
            onSelectRun = { record ->
                showRuns = false
                selectedRunId = record.runId
                onSelectRun(record.runId)
            },
        )
    }

    if (selectedRunId != null) {
        AgentRunDetailDialog(
            runId = selectedRunId ?: "",
            events = agentRunDetail,
            onDismiss = {
                selectedRunId = null
            },
        )
    }

}

@Composable
private fun AccessibilityHelpCard(
    onEnableViaShizuku: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "无法验证当前屏幕，可能未开启无障碍服务",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "开启后，即使没有 Shizuku，也能读屏、点击并验证前台结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnableViaShizuku) {
                    Text("Shizuku 一键开启")
                }
                TextButton(onClick = onOpenSettings) {
                    Text("去系统设置")
                }
            }
        }
    }
}

@Composable
private fun SkillLibraryDialog(
    skills: List<AgentSkill>,
    onDismiss: () -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onRedact: (String) -> Unit,
    onExportAll: () -> Unit = {},
    onImportFromClipboard: () -> Unit = {},
) {
    var expandedAuditId by remember { mutableStateOf<String?>(null) }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("经验库") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onExportAll) {
                    Text("导出全部")
                }
                TextButton(onClick = onImportFromClipboard) {
                    Text("从剪贴板导入")
                }
            }
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
                                        text = skill.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = when (skill.status) {
                                            AgentSkillStatus.APPROVED -> "已通过"
                                            AgentSkillStatus.PENDING -> "待审核"
                                            AgentSkillStatus.REJECTED -> "已拒绝"
                                        } + if (!skill.enabled) " · 已停用" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!skill.enabled) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            when (skill.status) {
                                                AgentSkillStatus.APPROVED -> MaterialTheme.colorScheme.primary
                                                AgentSkillStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                                                AgentSkillStatus.REJECTED -> MaterialTheme.colorScheme.error
                                            }
                                        },
                                    )
                                }
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        text = skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Text(
                                    text = "${skill.steps.size} 步 · 成功 ${skill.successCount} 次 · 使用 ${skill.useCount} 次 · v${skill.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val meta = buildString {
                                    if (skill.sourceVerified != null) append("验证=${if (skill.sourceVerified == true) "通过" else "未通过"} · ")
                                    if (skill.requiredCapabilities.isNotEmpty()) append("能力=${skill.requiredCapabilities.joinToString("/")} · ")
                                    if (skill.redacted) append("已脱敏 · ")
                                    if (skill.auditLog.isNotEmpty()) append("审计=${skill.auditLog.size} 条")
                                }.trimEnd(' ', '·')
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Text(
                                    text = skill.steps.joinToString(" → ") {
                                        val purpose = if (it.purpose.isNotBlank()) " [${it.purpose}]" else ""
                                        "${it.toolName}(${it.args.take(40)})$purpose"
                                    }.take(180),
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
                                    if (skill.enabled) {
                                        TextButton(onClick = { onToggleEnabled(skill.id, false) }) {
                                            Text("停用")
                                        }
                                    } else {
                                        TextButton(onClick = { onToggleEnabled(skill.id, true) }) {
                                            Text("启用")
                                        }
                                    }
                                    TextButton(onClick = { onRedact(skill.id) }) {
                                        Text("脱敏")
                                    }
                                    TextButton(onClick = {
                                        expandedSkillId = if (expandedSkillId == skill.id) null else skill.id
                                    }) {
                                        Text("详情")
                                    }
                                    if (skill.auditLog.isNotEmpty()) {
                                        TextButton(onClick = {
                                            expandedAuditId = if (expandedAuditId == skill.id) null else skill.id
                                        }) {
                                            Text("审计")
                                        }
                                    }
                                    TextButton(onClick = { onDelete(skill.id) }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                if (expandedSkillId == skill.id) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        skill.steps.forEachIndexed { idx, step ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "${idx + 1}. ${step.toolName} ${step.args.take(80)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                if (step.purpose.isNotBlank()) Text("目的：${step.purpose}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                                if (step.expected.isNotBlank()) Text("预期：${step.expected}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                                if (step.verification.isNotBlank()) Text("验证：${step.verification}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                                if (step.fallback.isNotBlank()) Text("兜底：${step.fallback}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                                if (step.uiEvidence.isNotBlank()) Text("证据：${step.uiEvidence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                            }
                                        }
                                    }
                                }
                                if (expandedAuditId == skill.id && skill.auditLog.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        skill.auditLog.takeLast(10).forEach { a ->
                                            val time = Instant.ofEpochMilli(a.at).atZone(ZoneId.systemDefault()).toLocalDateTime()
                                            Text(
                                                text = "${time} · ${a.action} · ${a.detail}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
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
    records: List<AgentRunRecord>,
    onDismiss: () -> Unit,
    onSelectSession: (Long) -> Unit = {},
    onSelectRun: (AgentRunRecord) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 运行记录") },
        text = {
            if (records.isEmpty()) {
                Text("暂无 Agent 运行记录")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(records, key = { it.runId }) { record ->
                        Card(
                            onClick = { onSelectRun(record) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = record.userText.take(36),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = when {
                                            record.state == AgentRunState.WAITING_CONFIRM -> "等待确认"
                                            record.state == AgentRunState.CANCELLED -> "已取消"
                                            record.ok -> "成功"
                                            else -> "失败"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            record.state == AgentRunState.WAITING_CONFIRM -> MaterialTheme.colorScheme.tertiary
                                            record.ok -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append(record.toolCalls.size)
                                        append(" 个工具 · ")
                                        append(formatTime(record.startedAtMs))
                                        append(" · ")
                                        append(record.durationMs)
                                        append("ms")
                                        record.verified?.let { verified ->
                                            append(" · 验证")
                                            append(if (verified) "通过" else "未通过")
                                        }
                                        record.capabilitySummary?.takeIf { it.isNotBlank() }?.let {
                                            append(" · ")
                                            append(it)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (record.safetyConfirmations > 0 || record.safetyBlocks > 0) {
                                    Text(
                                        text = "安全：确认 ${record.safetyConfirmations} · 批准 ${record.safetyApprovals} · 拒绝 ${record.safetyDenials} · 硬拦截 ${record.safetyBlocks}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (record.toolCalls.isNotEmpty()) {
                                    Text(
                                        text = record.toolCalls.joinToString(" → ").take(160),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
private fun AgentRunDetailDialog(
    runId: String,
    events: List<Map<String, Any?>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = runId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (events.isEmpty()) {
                    Text("正在读取运行轨迹…")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(events) { event ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = event["type"]?.toString() ?: "",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        text = summarizeTraceEvent(event),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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

private fun summarizeTraceEvent(event: Map<String, Any?>): String {
    val keys = listOf("tool", "round", "ok", "message", "state", "error", "waiting", "content")
    val parts = keys.mapNotNull { key ->
        event[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { "$key=$it" }
    }
    val text = parts.joinToString(" · ")
    return if (text.length > 240) text.take(240) + "…" else text
}
