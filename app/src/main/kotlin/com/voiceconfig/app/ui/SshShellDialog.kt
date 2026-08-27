package com.voiceconfig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.detectSshKeyType
import com.voiceconfig.app.remote.isRsaLikelyIncompatible
import com.voiceconfig.app.remote.StoredSshCredential

@Composable
fun SshShellDialog(
    onDismiss: () -> Unit,
    defaultHost: String = "",
    initialCredential: StoredSshCredential? = null,
    onStart: (SshConfig) -> Unit = {},
    onSend: (String) -> Unit = {},
    onCloseSession: () -> Unit = {},
    output: String = "",
    running: Boolean = false,
    error: String? = null,
    onClearOutput: () -> Unit = {},
    onClearError: () -> Unit = {},
    savedKeys: List<SshManagedKey> = emptyList(),
    onResize: (Int, Int) -> Unit = { _, _ -> },
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf((initialCredential?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialCredential?.username ?: "") }
    var password by remember { mutableStateOf(initialCredential?.password ?: "") }
    var privateKey by remember { mutableStateOf(initialCredential?.privateKey ?: "") }
    var command by remember { mutableStateOf("") }

    val canConnect = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())
    val visibleOutput = remember(output) { stripAnsi(output) }

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
        onDismissRequest = {
            onCloseSession()
            onDismiss()
        },
        title = { Text("SSH 交互终端") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("地址") }, modifier = Modifier.weight(2f), singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { privateKeyPicker.launch(arrayOf("*/*")) }) {
                        Text("导入私钥")
                    }
                    if (savedKeys.isNotEmpty()) {
                        Text("密钥库:", style = MaterialTheme.typography.bodySmall)
                        savedKeys.take(3).forEach { key ->
                            TextButton(onClick = { privateKey = key.privateKey }) {
                                Text(key.name)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text("私钥") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                val detectedKeyType = remember(privateKey) { detectSshKeyType(privateKey) }
                if (isRsaLikelyIncompatible(detectedKeyType)) {
                    Text(
                        text = "旧 RSA/DSA 可能被拒绝，建议 Ed25519/ECDSA。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onSend("uptime") }) { Text("uptime") }
                        TextButton(onClick = { onSend("ps aux") }) { Text("ps") }
                        TextButton(onClick = { onSend("free -h") }) { Text("free") }
                        TextButton(onClick = { onSend("tail -n 50 ~/.voiceconfig-node/node.log") }) { Text("node日志") }
                    }
                }
                Text(
                    text = "终端输出",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("SSH输出", visibleOutput))
                    }) {
                        Text("复制输出")
                    }
                    TextButton(onClick = { onResize(80, 24) }) { Text("80×24") }
                    TextButton(onClick = { onResize(120, 40) }) { Text("120×40") }
                }
                SelectionContainer {
                    Text(
                        text = visibleOutput.ifBlank { "(暂无输出)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("命令") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        enabled = running && command.isNotBlank(),
                        onClick = {
                            onSend(command.trim())
                            command = ""
                        },
                    ) {
                        Text("发送")
                    }
                }
                TextButton(onClick = onClearOutput) {
                    Text("清空输出")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(
                        enabled = canConnect,
                        onClick = {
                            onClearOutput()
                            onClearError()
                            onStart(currentConfig())
                        },
                    ) {
                        Text("连接")
                    }
                } else {
                    Button(onClick = {
                        onCloseSession()
                        onClearOutput()
                    }) {
                        Text("断开")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onCloseSession()
                onDismiss()
            }) {
                Text("关闭")
            }
        },
    )
}

private fun stripAnsi(text: String): String {
    val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    val osc = Regex("\u001B][^\u0007]*\u0007")
    return text.replace(csi, "").replace(osc, "").replace("\r\n", "\n").replace("\r", "\n")
}
