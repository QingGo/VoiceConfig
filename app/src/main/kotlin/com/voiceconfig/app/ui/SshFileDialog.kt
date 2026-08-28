package com.voiceconfig.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshFileResult
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.SshRemoteFile
import com.voiceconfig.app.remote.StoredSshCredential
import com.voiceconfig.app.remote.detectSshKeyType
import com.voiceconfig.app.remote.isRsaLikelyIncompatible

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
    var editorPath by remember { mutableStateOf<String?>(null) }

    val canConnect = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())
    val canList = canConnect && path.isNotBlank()
    val canRead = canConnect && path.isNotBlank()
    val canWrite = canConnect && path.isNotBlank() && content.isNotBlank()

    LaunchedEffect(result?.path, result?.content) {
        if (result?.ok == true && result.path == editorPath && !result.content.isNullOrBlank()) {
            content = result.content
        }
    }

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

    fun goParent() {
        val parent = path.trim().substringBeforeLast('/', "").ifBlank { "/" }
        path = parent
        selectedPath = null
        onList(currentConfig(), parent)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "SSH 远程文件",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }

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
                    verticalAlignment = Alignment.CenterVertically,
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

            

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("路径") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(onClick = { goParent() }) { Text("上一级") }
                    TextButton(onClick = {
                        selectedPath = null
                        onList(currentConfig(), path.trim())
                    }) { Text("刷新") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { path = "/home"; onList(currentConfig(), "/home") }) { Text("/home") }
                    TextButton(onClick = { path = "/etc"; onList(currentConfig(), "/etc") }) { Text("/etc") }
                    TextButton(onClick = { path = "/var/log"; onList(currentConfig(), "/var/log") }) { Text("/var/log") }
                    TextButton(onClick = { path = "/tmp"; onList(currentConfig(), "/tmp") }) { Text("/tmp") }
                }

                if (entries.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无目录内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(entries) { e ->
                            FileRow(
                                file = e,
                                onClick = {
                                    selectedPath = e.path
                                    path = e.path
                                    if (e.isDirectory) {
                                        editorPath = null
                                        onList(currentConfig(), e.path)
                                    } else {
                                        editorPath = e.path
                                        onRead(currentConfig(), e.path)
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                if (editorPath != null && result?.ok == true && result.path == editorPath && !result.content.isNullOrBlank()) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("文件内容") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        minLines = 4,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = canList, onClick = { onList(currentConfig(), path.trim()) }) {
                        Text("列目录")
                    }
                    Button(enabled = canRead, onClick = {
                        editorPath = path.trim()
                        onRead(currentConfig(), path.trim())
                    }) {
                        Text("读取")
                    }
                    Button(enabled = canWrite, onClick = { onWrite(currentConfig(), path.trim(), content) }) {
                        Text("写入")
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        Text("重命名")
                    }
                    TextButton(
                        enabled = canConnect && selectedPath != null,
                        onClick = {
                            val target = selectedPath ?: return@TextButton
                            onDelete(currentConfig(), target)
                            selectedPath = null
                        },
                    ) {
                        Text("删除")
                    }
                }

                result?.let { r ->
                    SelectionContainer {
                        Text(
                            text = when {
                                r.ok && r.content.isNotBlank() -> "已读取：${r.path}"
                                r.ok -> "操作成功：${r.path}"
                                else -> "操作失败：${r.error ?: r.path}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onClearResult) {
                        Text("清除结果")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: SshRemoteFile,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when {
                file.isDirectory -> "DIR"
                file.isSymlink -> "LNK"
                else -> "FILE"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (file.isDirectory) "目录" else formatFileSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (file.isDirectory) ">" else "›",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatFileSize(size: Long): String {
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
