package com.voiceconfig.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.voiceconfig.app.service.VoiceKeepAliveService
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun VoiceSettingsSection(
    agentVoiceAutoSend: Boolean,
    onAgentVoiceAutoSendChange: (Boolean) -> Unit,
    agentTtsEnabled: Boolean,
    onAgentTtsEnabledChange: (Boolean) -> Unit,
    wakeWordEnabled: Boolean,
    onWakeWordEnabledChange: (Boolean) -> Unit,
    overlayBallEnabled: Boolean = false,
    onOverlayBallEnabledChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onWakeWordEnabledChange(true)
        }
    }
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
            onCheckedChange = { enabled ->
                if (!enabled) {
                    onWakeWordEnabledChange(false)
                } else {
                    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        onWakeWordEnabledChange(true)
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
        )
        SwitchRow(
            title = "系统级悬浮球",
            subtitle = "在任意 App 上显示可拖动悬浮球，点击后直接聆听并交给智能助手",
            checked = overlayBallEnabled,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    onOverlayBallEnabledChange(false)
                    VoiceKeepAliveService.start(context, VoiceKeepAliveService.ACTION_HIDE_GLOBAL_BALL)
                } else {
                    if (Settings.canDrawOverlays(context)) {
                        onOverlayBallEnabledChange(true)
                        VoiceKeepAliveService.start(context, VoiceKeepAliveService.ACTION_HIDE_GLOBAL_BALL)
                    } else {
                        onOverlayBallEnabledChange(false)
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            },
        )
        Text(
            text = "唤醒词：言控 / 你好言控",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
