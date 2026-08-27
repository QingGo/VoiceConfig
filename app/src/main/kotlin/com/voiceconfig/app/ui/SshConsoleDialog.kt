package com.voiceconfig.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.remote.SshBootstrapResult
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshExecResult
import com.voiceconfig.app.remote.detectSshKeyType
import com.voiceconfig.app.remote.isRsaLikelyIncompatible
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.StoredSshCredential

@Composable
fun SshConsoleDialog(
    onDismiss: () -> Unit,
    onRun: (SshConfig, String) -> Unit,
    result: SshExecResult?,
    onClearResult: () -> Unit,
    defaultHost: String = "",
    initialCredential: StoredSshCredential? = null,
    onInstall: (SshConfig, String) -> Unit = { _, _ -> },
    bootstrapResult: SshBootstrapResult? = null,
    onClearBootstrapResult: () -> Unit = {},
    onClearHostKey: (SshConfig) -> Unit = {},
    savedKeys: List<SshManagedKey> = emptyList(),
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf((initialCredential?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialCredential?.username ?: "") }
    var password by remember { mutableStateOf(initialCredential?.password ?: "") }
    var privateKey by remember { mutableStateOf(initialCredential?.privateKey ?: "") }
    var command by remember { mutableStateOf("uname -a") }
    var bindMode by remember { mutableStateOf("tailscale") }

    val canRun = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank()) && command.isNotBlank()

    fun currentConfig() = SshConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        username = username.trim(),
        password = password.ifBlank { null },
        privateKey = privateKey.ifBlank { null },
    )

    val context = LocalContext.current
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { privateKey = it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 远程终端") },
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
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("命令") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { bindMode = "tailscale" }) {
                        Text(if (bindMode == "tailscale") "● Tailscale" else "Tailscale")
                    }
                    TextButton(onClick = { bindMode = "lan" }) {
                        Text(if (bindMode == "lan") "● 局域网" else "局域网")
                    }
                    TextButton(onClick = {
                        onClearHostKey(
                            SshConfig(
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 22,
                                username = username.trim(),
                                password = password.ifBlank { null },
                                privateKey = privateKey.ifBlank { null },
                            ),
                        )
                    }) {
                        Text("清除指纹")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canRun,
                    onClick = {
                        onRun(currentConfig(), command.trim())
                    },
                ) {
                    Text("执行")
                }
                Button(
                    enabled = canRun,
                    onClick = {
                        onInstall(currentConfig(), bindMode)
                    },
                ) {
                    Text("安装节点")
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
