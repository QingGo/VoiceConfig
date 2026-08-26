package com.voiceconfig.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshFileResult
import com.voiceconfig.app.remote.StoredSshCredential

@Composable
fun SshFileDialog(
    onDismiss: () -> Unit,
    defaultHost: String = "",
    initialCredential: StoredSshCredential? = null,
    onList: (SshConfig, String) -> Unit = { _, _ -> },
    onRead: (SshConfig, String) -> Unit = { _, _ -> },
    onWrite: (SshConfig, String, String) -> Unit = { _, _, _ -> },
    result: SshFileResult? = null,
    onClearResult: () -> Unit = {},
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf((initialCredential?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialCredential?.username ?: "") }
    var password by remember { mutableStateOf(initialCredential?.password ?: "") }
    var privateKey by remember { mutableStateOf(initialCredential?.privateKey ?: "") }
    var path by remember { mutableStateOf("/home") }
    var content by remember { mutableStateOf("") }

    val canConnect = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())
    val canList = canConnect && path.isNotBlank()
    val canRead = canConnect && path.isNotBlank()
    val canWrite = canConnect && path.isNotBlank() && content.isNotBlank()

    val context = LocalContext.current
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { privateKey = it }
        }
    }

    fun currentConfig() = SshConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        username = username.trim(),
        password = password.ifBlank { null },
        privateKey = privateKey.ifBlank { null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 远程文件") },
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
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, label = { Text("私钥（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                TextButton(onClick = { privateKeyPicker.launch(arrayOf("*/*")) }) {
                    Text("从文件导入私钥")
                }
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("远程路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { path = "/home" }) { Text("/home") }
                    TextButton(onClick = { path = "/etc" }) { Text("/etc") }
                    TextButton(onClick = { path = "/var/log" }) { Text("/var/log") }
                    TextButton(onClick = { path = "/tmp" }) { Text("/tmp") }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容（读取后显示 / 写入前编辑）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                    Button(enabled = canList, onClick = { onList(currentConfig(), path.trim()) }) {
                        Text("列目录")
                    }
                    Button(enabled = canRead, onClick = { onRead(currentConfig(), path.trim()) }) {
                        Text("读取")
                    }
                    Button(enabled = canWrite, onClick = { onWrite(currentConfig(), path.trim(), content) }) {
                        Text("写入")
                    }
                }
                result?.let { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (r.ok) "操作成功：${r.path}" else "操作失败：${r.path}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (r.content.isNotBlank()) {
                            Text(
                                text = r.content.take(4000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.error != null) {
                            Text(
                                text = r.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = onClearResult) {
                            Text("清除结果")
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
