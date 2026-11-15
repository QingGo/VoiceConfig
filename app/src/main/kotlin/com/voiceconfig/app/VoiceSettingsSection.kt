package com.voiceconfig.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    ttsProvider: String = "SYSTEM",
    onTtsProviderChange: (String) -> Unit = {},
    deepSeekTtsModel: String = "",
    onDeepSeekTtsModelChange: (String) -> Unit = {},
    deepSeekTtsVoice: String = "",
    onDeepSeekTtsVoiceChange: (String) -> Unit = {},
    deepSeekTtsFormat: String = "wav",
    onDeepSeekTtsFormatChange: (String) -> Unit = {},
    deepSeekTtsStreamingEnabled: Boolean = true,
    onDeepSeekTtsStreamingChange: (Boolean) -> Unit = {},
    fallbackToSystem: Boolean = true,
    onFallbackToSystemChange: (Boolean) -> Unit = {},
    cloudConsent: Boolean = false,
    onCloudConsentChange: (Boolean) -> Unit = {},
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
        Text("语音播报引擎", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            listOf("SYSTEM" to "Android 系统", "DEEPSEEK" to "DeepSeek").forEach { (id, label) ->
                androidx.compose.material3.TextButton(onClick = { onTtsProviderChange(id) }) {
                    Text(if (ttsProvider == id) "✓ $label" else label)
                }
            }
        }
        if (ttsProvider == "DEEPSEEK") {
            Text(
                text = "DeepSeek TTS 会把待播报文本发送到云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "DeepSeek TTS 不需要模型 ID；默认音色 stella，可选 tide；输出 wav 或 pcm；不支持调节语速。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedTextField(
                value = deepSeekTtsVoice,
                onValueChange = onDeepSeekTtsVoiceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("voice_id：stella（默认）或 tide") },
                singleLine = true,
            )
            androidx.compose.material3.OutlinedTextField(
                value = deepSeekTtsFormat,
                onValueChange = onDeepSeekTtsFormatChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("格式：wav（默认）或 pcm") },
                singleLine = true,
            )
        }
        SwitchRow(
            title = "DeepSeek TTS 流式播放",
            subtitle = "使用 WebSocket 边合成边播放，降低首字延迟；关闭后使用整段 HTTP 合成",
            checked = deepSeekTtsStreamingEnabled,
            onCheckedChange = onDeepSeekTtsStreamingChange,
        )
        SwitchRow(
            title = "语音服务失败时回退系统能力",
            subtitle = "云端 ASR/TTS 失败时回退到 Android 系统识别/播报",
            checked = fallbackToSystem,
            onCheckedChange = onFallbackToSystemChange,
        )
        SwitchRow(
            title = "允许云端语音处理",
            subtitle = "选择 DeepSeek 语音时，录音或播报文本会发送到 DeepSeek；默认关闭",
            checked = cloudConsent,
            onCheckedChange = onCloudConsentChange,
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
