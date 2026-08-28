package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun AdvancedSettingsSection(
    remoteNodesCount: Int,
    onOpenRemoteNodes: () -> Unit,
    onOpenSshConsole: () -> Unit,
    onOpenSshFile: () -> Unit,
    onOpenSshShell: () -> Unit,
    onOpenSshAudit: () -> Unit,
    onOpenSshKeys: () -> Unit,
    onOpenSshServices: () -> Unit,
    onOpenSshNodeLogs: () -> Unit,
    onOpenRemoteProjects: () -> Unit,
    aiDebugLogsSize: Int,
    lastAiError: String?,
    lastAiParseError: String?,
    lastAiRawResponse: String?,
    onCopyDebugReport: () -> Unit,
    onShareDebugReport: () -> Unit,
) {
    var showDebugSection by remember { mutableStateOf(false) }
    var showRawAi by remember { mutableStateOf(false) }

    VoiceSectionCard(title = "高级能力 / 远程", defaultExpanded = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已登记 $remoteNodesCount 个远程节点",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "SSH 命令 / 文件 / 终端 / 服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenRemoteNodes) {
                Text("管理")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteToolTile("命令终端", Icons.Default.Build, onClick = onOpenSshConsole)
            RemoteToolTile("远程文件", Icons.Default.List, onClick = onOpenSshFile)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteToolTile("交互终端", Icons.Default.Edit, onClick = onOpenSshShell)
            RemoteToolTile("SSH 审计", Icons.Default.Info, onClick = onOpenSshAudit)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteToolTile("密钥管理", Icons.Default.Lock, onClick = onOpenSshKeys)
            RemoteToolTile("系统服务", Icons.Default.Settings, onClick = onOpenSshServices)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteToolTile("节点日志", Icons.Default.List, onClick = onOpenSshNodeLogs)
            RemoteToolTile("远程节点", Icons.Default.Phone, onClick = onOpenRemoteNodes)
            RemoteToolTile("远程项目", Icons.Default.Build, onClick = onOpenRemoteProjects)
        }
    }

    VoiceSectionCard(title = "高级 / 调试", defaultExpanded = false) {
        TextButton(onClick = { showDebugSection = !showDebugSection }) {
            Text(if (showDebugSection) "收起开发者调试" else "展开开发者调试")
        }
        if (showDebugSection) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lastAiError?.let { error ->
                        Text(
                            text = "最近 AI 错误：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    lastAiParseError?.let { parseError ->
                        Text(
                            text = "JSON 解析错误：$parseError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    lastAiRawResponse?.let { raw ->
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
            lastAiRawResponse?.let {
                TextButton(onClick = { showRawAi = !showRawAi }) {
                    Text(if (showRawAi) "隐藏原始返回" else "查看原始返回")
                }
            }
            Text(
                text = "AI 错误日志（$aiDebugLogsSize 条）",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onCopyDebugReport) {
                Text("复制为 GitHub Issue 文本")
            }
            TextButton(onClick = onShareDebugReport) {
                Text("分享错误日志")
            }
        }
    }
}
