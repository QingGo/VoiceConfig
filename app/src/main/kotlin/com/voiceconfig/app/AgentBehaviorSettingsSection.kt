package com.voiceconfig.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
) {
    VoiceSectionCard(title = "智能助手行为", defaultExpanded = false) {
        SwitchRow(
            title = "敏感操作自动执行",
            subtitle = "开启后 Agent 不再弹出确认，直接执行发送/支付/删除等操作；建议仅测试或信任场景使用",
            checked = autoConfirm,
            onCheckedChange = onAutoConfirmChange,
        )
        SwitchRow(
            title = "自动截屏验证",
            subtitle = "开启后每次改变界面的工具执行后自动截屏确认",
            checked = autoVerify,
            onCheckedChange = onAutoVerifyChange,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "每次最多自动验证次数",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onMaxAutoVerifiesChange((maxAutoVerifies - 1).coerceAtLeast(0)) }) {
                Text("-")
            }
            Text(
                text = maxAutoVerifies.toString(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TextButton(onClick = { onMaxAutoVerifiesChange((maxAutoVerifies + 1).coerceAtMost(20)) }) {
                Text("+")
            }
        }
    }
}
