package com.voiceconfig.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.remote.SshBootstrapResult
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshExecResult

@Composable
fun SshConsoleDialog(
    onDismiss: () -> Unit,
    onRun: (SshConfig, String) -> Unit,
    result: SshExecResult?,
    onClearResult: () -> Unit,
    defaultHost: String = "",
    onInstall: (SshConfig, String) -> Unit = { _, _ -> },
    bootstrapResult: SshBootstrapResult? = null,
    onClearBootstrapResult: () -> Unit = {},
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("uname -a") }
    var bindMode by remember { mutableStateOf("tailscale") }

    val canRun = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank()) && command.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 远程终端") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("树莓派地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（可选，私钥为空时使用）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, label = { Text("私钥（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("命令") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                    Button(
                        enabled = canRun,
                        onClick = {
                            onRun(
                                SshConfig(
                                    host = host.trim(),
                                    port = port.toIntOrNull() ?: 22,
                                    username = username.trim(),
                                    password = password.ifBlank { null },
                                    privateKey = privateKey.ifBlank { null },
                                ),
                                command.trim(),
                            )
                        },
                    ) {
                        Text("执行")
                    }
                    Button(
                        enabled = canRun,
                        onClick = {
                            onInstall(
                                SshConfig(
                                    host = host.trim(),
                                    port = port.toIntOrNull() ?: 22,
                                    username = username.trim(),
                                    password = password.ifBlank { null },
                                    privateKey = privateKey.ifBlank { null },
                                ),
                                bindMode,
                            )
                        },
                    ) {
                        Text("安装节点")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { bindMode = "tailscale" }) {
                        Text(if (bindMode == "tailscale") "● Tailscale" else "Tailscale")
                    }
                    TextButton(onClick = { bindMode = "lan" }) {
                        Text(if (bindMode == "lan") "● 局域网" else "局域网")
                    }
                }
                result?.let { r ->
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "exit=${r.exitCode}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = if (r.stdout.isNotBlank()) r.stdout else "(无输出)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (r.stderr.isNotBlank()) {
                                Text(
                                    text = r.stderr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = onClearResult) {
                                Text("清除")
                            }
                        }
                    }
                }
                bootstrapResult?.let { b ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (b.ok) "安装结果：${b.message}" else "安装结果：${b.message}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (b.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (b.token != null) {
                            Text("Token: ${b.token.take(12)}…", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onClearBootstrapResult) {
                            Text("清除安装结果")
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
