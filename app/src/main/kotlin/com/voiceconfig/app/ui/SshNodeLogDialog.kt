package com.voiceconfig.app.ui

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
import com.voiceconfig.app.remote.SshExecResult
import com.voiceconfig.app.remote.detectSshKeyType
import com.voiceconfig.app.remote.isRsaLikelyIncompatible
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.StoredSshCredential

@Composable
fun SshNodeLogDialog(
    onDismiss: () -> Unit,
    defaultHost: String = "",
    initialCredential: StoredSshCredential? = null,
    result: SshExecResult? = null,
    onClearResult: () -> Unit = {},
    onReadAudit: (SshConfig) -> Unit = {},
    onReadLog: (SshConfig) -> Unit = {},
    savedKeys: List<SshManagedKey> = emptyList(),
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf((initialCredential?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialCredential?.username ?: "") }
    var password by remember { mutableStateOf(initialCredential?.password ?: "") }
    var privateKey by remember { mutableStateOf(initialCredential?.privateKey ?: "") }

    val canConnect = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())
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
        title = { Text("SSH 节点日志") },
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
                OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, label = { Text("私钥（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                val detectedKeyType = remember(privateKey) { detectSshKeyType(privateKey) }
                if (isRsaLikelyIncompatible(detectedKeyType)) {
                    Text(
                        text = "检测到 ${detectedKeyType} 私钥；建议生成 Ed25519 或 ECDSA。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { privateKeyPicker.launch(arrayOf("*/*")) }) {
                    Text("从文件导入私钥")
                }
                if (savedKeys.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("密钥库：", style = MaterialTheme.typography.bodySmall)
                        savedKeys.take(5).forEach { key ->
                            TextButton(onClick = { privateKey = key.privateKey }) {
                                Text(key.name)
                            }
                        }
                    }
                }
                TextButton(onClick = onClearResult) {
                    Text("清除结果")
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canConnect, onClick = { onReadAudit(currentConfig()) }) {
                    Text("节点审计")
                }
                Button(enabled = canConnect, onClick = { onReadLog(currentConfig()) }) {
                    Text("节点日志")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
