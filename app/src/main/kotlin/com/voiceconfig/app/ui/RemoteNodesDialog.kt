package com.voiceconfig.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.voiceconfig.data.local.repository.RemoteNode

@Composable
fun RemoteNodesDialog(
    nodes: List<RemoteNode>,
    onDismiss: () -> Unit,
    onSave: (RemoteNode) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onTogglePaused: (Long, Boolean) -> Unit,
) {
    var editingId by remember { mutableStateOf<Long?>(null) }
    var showForm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("远程节点") },
        text = {
            if (showForm) {
                NodeEditForm(
                    editing = nodes.firstOrNull { it.id == editingId },
                    onCancel = { showForm = false; editingId = null },
                    onSave = { node ->
                        onSave(node)
                        showForm = false
                        editingId = null
                    },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            editingId = null
                            showForm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("添加节点")
                    }
                    if (nodes.isEmpty()) {
                        Text("暂无远程节点。请先通过 SSH 安装节点，再在此登记。")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(nodes, key = { it.id }) { node ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = node.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = when {
                                                    !node.enabled -> "已停用"
                                                    node.paused -> "已暂停"
                                                    else -> "在线"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when {
                                                    !node.enabled -> MaterialTheme.colorScheme.outline
                                                    node.paused -> MaterialTheme.colorScheme.tertiary
                                                    else -> MaterialTheme.colorScheme.primary
                                                },
                                            )
                                        }
                                        Text(
                                            text = "${node.scheme}://${node.host}:${node.port} · ${node.nodeId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "允许命令：" + node.allowedCommands.joinToString("/"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { onToggleEnabled(node.id, !node.enabled) }) {
                                                Text(if (node.enabled) "停用" else "启用")
                                            }
                                            TextButton(onClick = { onTogglePaused(node.id, !node.paused) }) {
                                                Text(if (node.paused) "恢复" else "暂停")
                                            }
                                            TextButton(onClick = {
                                                editingId = node.id
                                                showForm = true
                                            }) {
                                                Text("编辑")
                                            }
                                            TextButton(onClick = { onDelete(node.id) }) {
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun NodeEditForm(
    editing: RemoteNode?,
    onCancel: () -> Unit,
    onSave: (RemoteNode) -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var host by remember { mutableStateOf(editing?.host ?: "") }
    var port by remember { mutableStateOf((editing?.port ?: 8787).toString()) }
    var scheme by remember { mutableStateOf(editing?.scheme ?: "http") }
    var token by remember { mutableStateOf("") }
    var commands by remember {
        mutableStateOf(editing?.allowedCommands?.joinToString(",") ?: "hostname,uptime,free,df,ps,network,tailscale,os_release,uname")
    }

    val canSave = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() != null && commands.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (editing == null) "添加远程节点" else "编辑远程节点", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / Tailscale IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = scheme, onValueChange = { scheme = it }, label = { Text("协议 http/https") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (editing == null) "Token" else "Token（留空保持不变）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(value = commands, onValueChange = { commands = it }, label = { Text("允许命令（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            Button(
                enabled = canSave,
                onClick = {
                    val allowed = commands.split(",", ";", "\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onSave(
                        RemoteNode(
                            id = editing?.id ?: 0,
                            nodeId = editing?.nodeId ?: ("node_" + System.currentTimeMillis()),
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 8787,
                            scheme = scheme.trim().ifBlank { "http" },
                            token = token.trim().ifBlank { null },
                            allowedCommands = allowed,
                            enabled = editing?.enabled ?: true,
                            paused = editing?.paused ?: false,
                            createdAtEpochMillis = editing?.createdAtEpochMillis ?: 0,
                            updatedAtEpochMillis = editing?.updatedAtEpochMillis ?: 0,
                            lastSeenAtEpochMillis = editing?.lastSeenAtEpochMillis,
                            lastStatus = editing?.lastStatus,
                            lastError = editing?.lastError,
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        }
    }
}
