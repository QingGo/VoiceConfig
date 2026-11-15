package com.voiceconfig.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.voiceconfig.app.agent.ExternalToolSummary
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun ExternalToolsSettingsSection(
    summaries: List<ExternalToolSummary>,
    message: String?,
    onImportPack: (String) -> Unit,
    onConfigureMcp: (String, String, String, String) -> Unit,
    onRemovePack: (String) -> Unit,
    onClearMcp: () -> Unit,
) {
    var showImport by remember { mutableStateOf(false) }
    var packJson by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var mcpToken by remember { mutableStateOf("") }
    var mcpAllowed by remember { mutableStateOf("") }
    var mcpPin by remember { mutableStateOf("") }

    VoiceSectionCard(title = "外部工具", defaultExpanded = false) {
        Text(
            text = "外部工具默认关闭。导入 manifest 或配置 MCP 后，工具才会进入模型工具表；Token 只保存在本机 Keystore 加密存储中。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summaries.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${item.name} · ${item.source} · ${if (item.enabled) "已启用" else "已停用"}", modifier = Modifier.weight(1f))
                if (item.source == "MCP") {
                    TextButton(onClick = onClearMcp) { Text("停用") }
                } else {
                    TextButton(onClick = { onRemovePack(item.id) }) { Text("删除") }
                }
            }
        }
        TextButton(onClick = { showImport = !showImport }) {
            Text(if (showImport) "收起导入" else "导入 HTTP Tool Pack")
        }
        if (showImport) {
            OutlinedTextField(
                value = packJson,
                onValueChange = { packJson = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("voiceconfig.toolpack.json") },
                minLines = 5,
            )
            Button(onClick = { onImportPack(packJson) }) { Text("导入并启用") }
        }
        Text("MCP 服务器", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = mcpUrl,
            onValueChange = { mcpUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MCP URL，例如 https://host/mcp") },
            singleLine = true,
        )
        OutlinedTextField(
            value = mcpToken,
            onValueChange = { mcpToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer Token") },
            singleLine = true,
        )
        OutlinedTextField(
            value = mcpAllowed,
            onValueChange = { mcpAllowed = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("工具 allowlist，逗号分隔；留空表示全部") },
            singleLine = true,
        )
        OutlinedTextField(
            value = mcpPin,
            onValueChange = { mcpPin = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TLS SHA-256 pin，可选") },
            singleLine = true,
        )
        Button(onClick = { onConfigureMcp(mcpUrl, mcpToken, mcpAllowed, mcpPin) }) { Text("保存并连接 MCP") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
