package com.voiceconfig.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.ui.theme.SuccessGreen
import com.voiceconfig.app.ui.theme.WarningOrange
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.Task

@Composable
internal fun ExecutionLogRow(log: ExecutionLog, tasks: List<Task>, installedAppLabels: Map<String, String>, onOpenAgentSession: (Long) -> Unit = {}) {
    val task = tasks.firstOrNull { it.id == log.taskId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val statusColor = when (log.status) {
                    ExecutionStatus.SUCCESS -> SuccessGreen
                    ExecutionStatus.FALLBACK -> WarningOrange
                    ExecutionStatus.WAITING_HUMAN -> MaterialTheme.colorScheme.tertiary
                    ExecutionStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = formatExecutionSummary(log, task, installedAppLabels),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
                Text(
                    text = formatLogTime(log.scheduledAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                log.verified?.let { verified ->
                    Text(
                        text = if (verified) "已通过前台验证" else "未验证最终结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (verified) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                log.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                log.agentSessionId?.let { sessionId ->
                    TextButton(onClick = { onOpenAgentSession(sessionId) }) {
                        Text("查看 Agent 会话")
                    }
                }
            }
        }
    }
}

internal fun formatExecutionSummary(log: ExecutionLog, task: Task?, installedAppLabels: Map<String, String>): String {
    val action = task?.let { formatTaskTitle(it, installedAppLabels) } ?: "任务"
    val via = when (log.executionMode) {
        ExecutionMode.SHIZUKU -> "Shizuku"
        ExecutionMode.NOTIFICATION -> "通知"
        ExecutionMode.DEEP_LINK -> "深链"
        ExecutionMode.AUTO -> "自动"
        ExecutionMode.ACCESSIBILITY -> "无障碍"
        ExecutionMode.AGENT -> "智能代理"
        null -> ""
    }
    return when (log.status) {
        ExecutionStatus.SUCCESS -> "成功$action"
        ExecutionStatus.FALLBACK -> when {
            via == "通知" -> "降级（仅通知提醒，未自动打开）$action"
            via.isBlank() -> "已降级执行$action"
            else -> "降级通过$via 执行$action"
        }
        ExecutionStatus.WAITING_HUMAN -> "等待用户确认$action"
        ExecutionStatus.FAILED -> "失败$action"
        ExecutionStatus.SKIPPED -> "已跳过$action"
        ExecutionStatus.EXECUTING -> "正在执行$action"
        ExecutionStatus.SCHEDULED -> "已排定$action"
    }
}

internal fun formatLogTime(epochMillis: Long): String {
    val dateTime = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    val today = java.time.LocalDate.now()
    val date = dateTime.toLocalDate()
    val time = String.format("%02d:%02d", dateTime.hour, dateTime.minute)
    return when (date) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> String.format("%04d-%02d-%02d %s", dateTime.year, dateTime.monthValue, dateTime.dayOfMonth, time)
    }
}

internal fun displayAppName(packageName: String?, installedAppLabels: Map<String, String> = emptyMap()): String {
    if (packageName == null) return "提醒"
    val known = when (packageName) {
        "com.tencent.wework" -> "企业微信"
        "com.tencent.mm" -> "微信"
        "com.ss.android.lark" -> "飞书"
        "com.alibaba.android.rimet" -> "钉钉"
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.autonavi.minimap" -> "高德地图"
        "com.baidu.BaiduMap" -> "百度地图"
        "com.taobao.taobao" -> "淘宝"
        "com.jingdong.app.mall" -> "京东"
        "com.ss.android.ugc.aweme" -> "抖音"
        "com.sina.weibo" -> "微博"
        "com.tencent.mobileqq" -> "QQ"
        else -> null
    }
    if (known != null) return known
    installedAppLabels[packageName]?.let { return it }
    return packageName
}

internal fun formatTaskTitle(task: Task, installedAppLabels: Map<String, String> = emptyMap()): String {
    return when (task.actionType) {
        ActionType.NOTIFY -> {
            val index = task.title.indexOf("提醒")
            if (index >= 0) {
                "提醒${task.title.substring(index + 2).trim()}"
            } else {
                "提醒"
            }
        }
        ActionType.OPEN_DEEPLINK -> "打开${task.deepLink ?: "页面"}"
        ActionType.AGENT -> task.title.removePrefix("智能助手：").ifBlank { task.title }
        else -> "打开${displayAppName(task.targetPackage, installedAppLabels)}"
    }
}
