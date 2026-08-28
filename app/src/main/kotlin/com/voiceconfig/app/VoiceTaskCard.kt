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

@Composable
internal fun TaskRow(
    task: Task,
    installedAppLabels: Map<String, String>,
    lastLog: ExecutionLog? = null,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRunNow: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
        var taskMenuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTaskTitle(task, installedAppLabels),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (task.actionType == ActionType.AGENT) {
                    Text(
                        text = "智能助手 · 自动执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = formatScheduleText(task.schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.nextRunAtEpochMillis?.let { nextRun ->
                    Text(
                        text = "下次执行：${formatLogTime(nextRun)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                lastLog?.let { log ->
                    val statusText = when (log.status) {
                        ExecutionStatus.SUCCESS -> "成功"
                        ExecutionStatus.FALLBACK -> "降级完成"
                        ExecutionStatus.FAILED -> "失败"
                        ExecutionStatus.SCHEDULED -> "已计划"
                        ExecutionStatus.EXECUTING -> "执行中"
                        ExecutionStatus.SKIPPED -> "已跳过"
                        ExecutionStatus.WAITING_HUMAN -> "等待确认"
                    }
                    Text(
                        text = "上次：${statusText} · ${formatLogTime(log.scheduledAtEpochMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (log.status == ExecutionStatus.SUCCESS || log.status == ExecutionStatus.FALLBACK) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Switch(checked = task.enabled, onCheckedChange = { onToggle() })
            Box {
                IconButton(onClick = { taskMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = taskMenuExpanded,
                    onDismissRequest = { taskMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("立即执行") },
                        onClick = {
                            taskMenuExpanded = false
                            onRunNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            taskMenuExpanded = false
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("查看运行记录") },
                        onClick = {
                            taskMenuExpanded = false
                            onOpenLogs()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            taskMenuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

internal fun formatScheduleText(schedule: ScheduleSpec?): String {
    if (schedule == null) return "未设置时间"
    val time = schedule.time?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: ""
    return when (schedule.type) {
        ScheduleSpec.ScheduleType.DAILY -> "每天 $time"
        ScheduleSpec.ScheduleType.WEEKLY -> {
            val days = schedule.daysOfWeek.sortedBy { it.value }
            val workdays = setOf(
                java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY,
            )
            val dayNames = when {
                days.containsAll(workdays) && days.size == 5 -> "工作日"
                days.size == 7 -> "每天"
                else -> days.joinToString("、") { day ->
                    when (day) {
                        java.time.DayOfWeek.MONDAY -> "周一"
                        java.time.DayOfWeek.TUESDAY -> "周二"
                        java.time.DayOfWeek.WEDNESDAY -> "周三"
                        java.time.DayOfWeek.THURSDAY -> "周四"
                        java.time.DayOfWeek.FRIDAY -> "周五"
                        java.time.DayOfWeek.SATURDAY -> "周六"
                        else -> "周日"
                    }
                }
            }
            if (dayNames == "每天") "每天 $time" else "每周$dayNames $time"
        }
        ScheduleSpec.ScheduleType.ONCE -> {
            val date = schedule.date ?: java.time.LocalDate.now()
            val dateText = when (date) {
                java.time.LocalDate.now() -> "今天"
                java.time.LocalDate.now().plusDays(1) -> "明天"
                else -> date.toString()
            }
            "$dateText $time"
        }
        ScheduleSpec.ScheduleType.INTERVAL -> {
            val minutes = schedule.intervalMinutes ?: return "间隔执行"
            if (minutes % 60 == 0L) "每 ${minutes / 60} 小时" else "每 $minutes 分钟"
        }
    }
}
