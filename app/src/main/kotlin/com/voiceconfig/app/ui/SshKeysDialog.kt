package com.voiceconfig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import com.voiceconfig.app.remote.SshManagedKey

@Composable
fun SshKeysDialog(
    keys: List<SshManagedKey>,
    onDismiss: () -> Unit,
    onGenerate: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var newName by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf<SshManagedKey?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 密钥管理") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "生成现代算法密钥。优先 ECDSA；Ed25519 在部分设备上会自动回退到 ECDSA。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        onGenerate("ED25519", newName.ifBlank { "voiceconfig-ed25519" })
                        newName = ""
                    }) {
                        Text("生成 Ed25519")
                    }
                    TextButton(onClick = {
                        onGenerate("ECDSA256", newName.ifBlank { "voiceconfig-ecdsa" })
                        newName = ""
                    }) {
                        Text("生成 ECDSA")
                    }
                    TextButton(onClick = {
                        onGenerate("RSA", newName.ifBlank { "voiceconfig-rsa" })
                        newName = ""
                    }) {
                        Text("生成 RSA")
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("密钥名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (keys.isEmpty()) {
                    Text(
                        text = "暂无保存的密钥",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    keys.forEach { key ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { selectedKey = key; newName = key.name }) {
                                Text("${if (selectedKey?.id == key.id) "● " else ""}${key.name} (${key.type})")
                            }
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("SSH公钥", key.publicKey))
                            }) {
                                Text("复制公钥")
                            }
                            TextButton(onClick = { onDelete(key.id) }) {
                                Text("删除")
                            }
                        }
                    }
                }
                selectedKey?.let { key ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "密钥：${key.name} (${key.type})",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            onRename(key.id, newName.ifBlank { key.name })
                            selectedKey = null
                            newName = ""
                        }) {
                            Text("确认重命名")
                        }
                        SelectionContainer {
                            Text(
                                text = key.publicKey,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
