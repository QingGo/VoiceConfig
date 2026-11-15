package com.voiceconfig.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun AgentBehaviorSettingsSection(
    autoConfirm: Boolean,
    onAutoConfirmChange: (Boolean) -> Unit,
    autoVerify: Boolean,
    onAutoVerifyChange: (Boolean) -> Unit,
    maxAutoVerifies: Int,
    onMaxAutoVerifiesChange: (Int) -> Unit,
    advancedToolsEnabled: Boolean = false,
    onAdvancedToolsChange: (Boolean) -> Unit = {},
    remoteNodeEnabled: Boolean = false,
    onRemoteNodeChange: (Boolean) -> Unit = {},
    visibleModelTools: List<String> = emptyList(),
) {
    var pendingDangerousToggle by remember { mutableStateOf<String?>(null) }
    VoiceSectionCard(title = "智能助手行为", defaultExpanded = false) {
        SwitchRow(
            title = "敏感操作自动执行",
            subtitle = "仅对可撤销操作生效；支付、删除、发送、shell、远程写入等不可逆动作仍会强制确认",
            checked = autoConfirm,
            onCheckedChange = onAutoConfirmChange,
        )
        SwitchRow(
            title = "允许模型调用高级工具",
            subtitle = "高级工具默认不进入模型工具表。请仅在理解风险时开启。",
            checked = advancedToolsEnabled,
            onCheckedChange = { enabled ->
                if (enabled) pendingDangerousToggle = "advanced" else onAdvancedToolsChange(false)
            },
        )
        SwitchRow(
            title = "远程节点工具",
            subtitle = "允许模型查询或执行远程节点允许列表中的命令；exec 每次都会请求确认",
            checked = remoteNodeEnabled,
            onCheckedChange = { enabled ->
                if (enabled) pendingDangerousToggle = "remote" else onRemoteNodeChange(false)
            },
        )
        SwitchRow(
            title = "自动截屏验证",
            subtitle = "开启后每次改变界面的工具执行后自动截屏确认",
            checked = autoVerify,
            onCheckedChange = onAutoVerifyChange,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每次最多自动验证次数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onMaxAutoVerifiesChange((maxAutoVerifies - 1).coerceAtLeast(0)) }) { Text("-") }
            Text(maxAutoVerifies.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
            TextButton(onClick = { onMaxAutoVerifiesChange((maxAutoVerifies + 1).coerceAtMost(20)) }) { Text("+") }
        }
        Text(
            text = "本次模型可用工具（${visibleModelTools.size}）：${visibleModelTools.joinToString(", ").take(300)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pendingDangerousToggle?.let { kind ->
        AlertDialog(
            onDismissRequest = { pendingDangerousToggle = null },
            title = { Text("确认开启高风险能力") },
            text = {
                Text(
                    if (kind == "remote") "远程节点工具允许模型调用已登记节点上的命令。每次 exec 仍会要求确认。"
                    else "高级工具可能扩大模型可执行范围。不可逆操作仍受强确认保护。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (kind == "remote") onRemoteNodeChange(true) else onAdvancedToolsChange(true)
                    pendingDangerousToggle = null
                }) { Text("确认开启") }
            },
            dismissButton = { TextButton(onClick = { pendingDangerousToggle = null }) { Text("取消") } },
        )
    }
}
