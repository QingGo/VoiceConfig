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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshFileResult
import com.voiceconfig.app.remote.detectSshKeyType
import com.voiceconfig.app.remote.isRsaLikelyIncompatible
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.SshRemoteFile
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
    entries: List<SshRemoteFile>? = emptyList(),
    onMkdir: (SshConfig, String) -> Unit = { _, _ -> },
    onDelete: (SshConfig, String) -> Unit = { _, _ -> },
    onRename: (SshConfig, String, String) -> Unit = { _, _, _ -> },
    savedKeys: List<SshManagedKey> = emptyList(),
) {
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf((initialCredential?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialCredential?.username ?: "") }
    var password by remember { mutableStateOf(initialCredential?.password ?: "") }
    var privateKey by remember { mutableStateOf(initialCredential?.privateKey ?: "") }
    var path by remember { mutableStateOf("/home") }
    var content by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf<String?>(null) }

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

    fun joinPath(dir: String, name: String): String {
        val d = dir.trim().trimEnd('/')
        return if (d.isEmpty()) name.trim() else "$d/${name.trim()}"
    }

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
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("远程路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { path = "/home" }) { Text("/home") }
                    TextButton(onClick = { path = "/etc" }) { Text("/etc") }
                    TextButton(onClick = { path = "/var/log" }) { Text("/var/log") }
                    TextButton(onClick = { path = "/tmp" }) { Text("/tmp") }
                    TextButton(onClick = { selectedPath = null; onList(currentConfig(), path.trim()) }) { Text("刷新") }
                }
                Text(
                    text = "目录内容（点击目录进入，点击文件读取）",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (entries.isNullOrEmpty()) {
                    Text(
                        text = "尚未加载或目录为空",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    entries.forEach { e ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    e.isDirectory -> "📁"
                                    e.isSymlink -> "🔗"
                                    else -> "📄"
                                },
                            )
                            TextButton(
                                onClick = {
                                    selectedPath = e.path
                                    path = e.path
                                    if (e.isDirectory) {
                                        onList(currentConfig(), e.path)
                                    } else {
                                        onRead(currentConfig(), e.path)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                text = if (e.isDirectory) "-" else formatFileSize(e.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容（读取后显示 / 写入前编辑）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新名称") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(
                        enabled = canConnect && newName.isNotBlank(),
                        onClick = {
                            onMkdir(currentConfig(), joinPath(path, newName))
                            newName = ""
                        },
                    ) {
                        Text("新建目录")
                    }
                    TextButton(
                        enabled = canConnect && selectedPath != null && newName.isNotBlank(),
                        onClick = {
                            val old = selectedPath ?: return@TextButton
                            val parent = old.substringBeforeLast('/', "").ifBlank { "/" }
                            onRename(currentConfig(), old, joinPath(parent, newName))
                            selectedPath = null
                            newName = ""
                        },
                    ) {
                        Text("重命名选中")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = canConnect && selectedPath != null,
                        onClick = {
                            val target = selectedPath ?: return@TextButton
                            onDelete(currentConfig(), target)
                            selectedPath = null
                        },
                    ) {
                        Text("删除选中")
                    }
                    TextButton(onClick = onClearResult) {
                        Text("清除结果")
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
                            SelectionContainer {
                                Text(
                                    text = r.content.take(4000),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (r.error != null) {
                            Text(
                                text = r.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun formatFileSize(size: Long): String {
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
