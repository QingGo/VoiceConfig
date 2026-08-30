package com.voiceconfig.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.agent.FlowScript
import com.voiceconfig.app.agent.FlowScriptStatus

@Composable
fun FlowScriptSettingsSection(
    scripts: List<FlowScript>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onImportJson: (String) -> Boolean,
) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("FlowScript 流程库", style = MaterialTheme.typography.titleMedium)
            Text(
                "已审核启用的 FlowScript 可通过 run_flow_script 执行；导入的脚本默认待审核且不会执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            scripts.forEach { script ->
                FlowScriptRow(
                    script = script,
                    onApprove = { onApprove(script.id) },
                    onReject = { onReject(script.id) },
                    onSetEnabled = { enabled -> onSetEnabled(script.id, enabled) },
                    onDelete = { onDelete(script.id) },
                )
            }

            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("粘贴 FlowScript JSON 导入") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    if (importText.isBlank()) {
                        importMessage = "请先粘贴 JSON"
                        return@TextButton
                    }
                    val ok = onImportJson(importText)
                    importMessage = if (ok) {
                        "导入成功，请在下方的待审核列表确认"
                    } else {
                        "导入失败：JSON 格式或校验未通过"
                    }
                    importText = ""
                },
            ) {
                Text("导入 FlowScript")
            }
            importMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = { copyAllToClipboard(context, scripts) },
            ) {
                Text("复制全部导出 JSON")
            }
        }
    }
}

private fun copyAllToClipboard(context: Context, scripts: List<FlowScript>) {
    val json = buildString {
        append("[")
        scripts.forEachIndexed { index, script ->
            if (index > 0) append(",")
            append(com.voiceconfig.app.agent.FlowScriptCodec.toJsonString(script))
        }
        append("]")
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("voiceconfig-flow-scripts", json))
}

@Composable
private fun FlowScriptRow(
    script: FlowScript,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isBuiltin = script.source == "builtin"
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${script.name}（${script.steps.size} 步）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${script.id} · ${script.source} · ${script.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isBuiltin) {
                if (script.status == FlowScriptStatus.APPROVED) {
                    Switch(
                        checked = script.enabled,
                        onCheckedChange = onSetEnabled,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
        if (!isBuiltin && script.status != FlowScriptStatus.APPROVED) {
            Row {
                TextButton(onClick = onApprove) {
                    Text("审核通过")
                }
                TextButton(onClick = onReject) {
                    Text("拒绝")
                }
            }
        }
    }
}
