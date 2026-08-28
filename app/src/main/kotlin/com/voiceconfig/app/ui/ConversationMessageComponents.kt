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

@Composable
internal fun ConversationMessages(
    modifier: Modifier = Modifier,
    messages: List<AgentMessageEntity>,
    agentSteps: List<AgentStepUi> = emptyList(),
    lastRunDurationMs: Long? = null,
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
        // 运行中自动跟随底部；运行结束后保留用户当前位置，方便查看上方执行时间线。
        if (visibleMessages.isNotEmpty() && (isAgentBusy || streamText.isNotBlank())) {
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
                AgentStepTimeline(steps = agentSteps, runDurationMs = lastRunDurationMs)
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
private fun AgentStepTimeline(steps: List<AgentStepUi>, runDurationMs: Long? = null) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "执行时间线（${steps.size} 步）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded && steps.isNotEmpty()) {
                    val last = steps.lastOrNull()
                    Text(
                        text = "最后工具结束 @ ${formatDurationMs((last?.startedAtElapsedMs ?: 0) + (last?.durationMs ?: 0))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Text(
                    text = "开始执行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                steps.filter { it.toolName.isNotBlank() }.takeLast(30).forEach { step ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${step.index + 1}. ${stepStatusIcon(step.status)} ${step.toolName}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.36f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (step.message.isNotBlank()) {
                                Text(
                                    text = step.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.50f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (step.durationMs > 0) {
                                Text(
                                    text = formatDurationMs(step.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (step.gapBeforeMs > 0 || step.startedAtElapsedMs > 0) {
                            Text(
                                text = buildString {
                                    if (step.gapBeforeMs > 0) {
                                        append("距上一步 ")
                                        append(formatDurationMs(step.gapBeforeMs))
                                    }
                                    if (step.startedAtElapsedMs > 0) {
                                        if (length > 0) append(" · ")
                                        append("运行后 ")
                                        append(formatDurationMs(step.startedAtElapsedMs))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 20.dp),
                            )
                        }
                    }
                }
                val last = steps.lastOrNull()
                if (last != null && last.startedAtElapsedMs > 0) {
                    Text(
                        text = "最后工具结束 @ ${formatDurationMs(last.startedAtElapsedMs + last.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (runDurationMs != null && runDurationMs > 0) {
                    Text(
                        text = "运行总耗时 ${formatDurationMs(runDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private fun stepStatusIcon(status: AgentStepStatus): String = when (status) {
    AgentStepStatus.RUNNING -> "[进行中]"
    AgentStepStatus.SUCCESS -> "[成功]"
    AgentStepStatus.FAILED -> "[失败]"
    AgentStepStatus.DECLINED -> "[已拒绝]"
}

private fun formatDurationMs(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    else -> String.format("%.1fs", ms / 1000.0)
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
            if (isUser) {
                // 用户消息不展示耗时
            } else if (msg.durationMs > 0 && msg.toolName == null) {
                val streamRemainingMs = (msg.durationMs - msg.ttftMs).coerceAtLeast(0)
                val detail = buildString {
                    append("LLM ")
                    append(formatDurationMs(msg.durationMs))
                    if (msg.ttftMs > 0) {
                        append("｜TTFT ")
                        append(formatDurationMs(msg.ttftMs))
                    }
                    if (streamRemainingMs > 0) {
                        append("｜流式 ")
                        append(formatDurationMs(streamRemainingMs))
                    }
                    if (msg.thinkingMs > 0) {
                        append("｜思考 ")
                        append(formatDurationMs(msg.thinkingMs))
                    }
                    if (msg.outputMs > 0) {
                        append("｜输出 ")
                        append(formatDurationMs(msg.outputMs))
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
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
                text = "${if (ok) "[成功]" else "[失败]"} 工具：${msg.toolName ?: "未知"}",
                style = MaterialTheme.typography.labelLarge,
            )
            if (msg.durationMs > 0) {
                Text(
                    text = "工具耗时 ${formatDurationMs(msg.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
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
