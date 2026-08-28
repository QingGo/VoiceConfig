package com.voiceconfig.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun VoiceSettingsSection(
    agentVoiceAutoSend: Boolean,
    onAgentVoiceAutoSendChange: (Boolean) -> Unit,
    agentTtsEnabled: Boolean,
    onAgentTtsEnabledChange: (Boolean) -> Unit,
    wakeWordEnabled: Boolean,
    onWakeWordEnabledChange: (Boolean) -> Unit,
) {
    VoiceSectionCard(title = "语音", defaultExpanded = false) {
        SwitchRow(
            title = "语音输入后自动发送",
            subtitle = "语音识别完成后直接发送给智能助手，不需要再点发送",
            checked = agentVoiceAutoSend,
            onCheckedChange = onAgentVoiceAutoSendChange,
        )
        SwitchRow(
            title = "Agent 结果语音播报",
            subtitle = "Agent 完成任务后用 TTS 读出结果摘要",
            checked = agentTtsEnabled,
            onCheckedChange = onAgentTtsEnabledChange,
        )
        SwitchRow(
            title = "语音唤醒",
            subtitle = "不打开 App 也能说“言控”唤醒；需要麦克风权限",
            checked = wakeWordEnabled,
            onCheckedChange = onWakeWordEnabledChange,
        )
        Text(
            text = "唤醒词：言控 / 你好言控",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
