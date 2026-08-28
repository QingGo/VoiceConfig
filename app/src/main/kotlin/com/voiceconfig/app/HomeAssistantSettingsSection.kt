package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.home.HomeAssistantDevice
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun HomeAssistantSettingsSection(
    configured: Boolean,
    baseUrl: String,
    token: String,
    testMessage: String?,
    controlMessage: String?,
    devices: List<HomeAssistantDevice>,
    onSaveConfig: (String, String) -> Unit,
    onTest: () -> Unit,
    onOpenPanel: () -> Unit,
    onControl: (String, String) -> Unit,
) {
    var draftBaseUrl by remember { mutableStateOf(baseUrl) }
    var draftToken by remember { mutableStateOf(token) }
    var showToken by remember { mutableStateOf(false) }

    VoiceSectionCard(title = "智能家居 / Home Assistant", defaultExpanded = false) {
        Text(
            text = if (configured) "已连接配置" else "未配置",
            style = MaterialTheme.typography.labelMedium,
            color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = draftBaseUrl,
            onValueChange = {
                draftBaseUrl = it
                onSaveConfig(it, draftToken)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home Assistant Base URL") },
            placeholder = { Text("http://192.168.1.100:8123") },
            singleLine = true,
        )
        OutlinedTextField(
            value = draftToken,
            onValueChange = {
                draftToken = it
                onSaveConfig(draftBaseUrl, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("长期访问令牌 (Long-Lived Access Token)") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "隐藏" else "显示")
                }
            },
        )
        Text(
            text = "配置后 Agent 可通过 home_devices / home_control 控制空调、灯光、窗帘、电视、音乐等 Home Assistant 已接入设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                onSaveConfig(draftBaseUrl, draftToken)
                onTest()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = draftBaseUrl.isNotBlank() && draftToken.isNotBlank(),
        ) {
            Text("测试连接")
        }
        TextButton(
            onClick = onOpenPanel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开设备面板")
        }
        testMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("已连接")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        controlMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        devices.take(12).let { preview ->
            if (preview.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "设备预览",
                    style = MaterialTheme.typography.titleSmall,
                )
                preview.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.friendlyName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "${device.domain} · ${device.state}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val sensitive = device.domain in setOf("lock", "camera", "alarm_control_panel", "siren")
                        val controllable = device.domain in setOf("light", "switch", "fan", "media_player", "input_boolean")
                        if (sensitive) {
                            Text(
                                text = "需确认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!controllable) {
                            Text(
                                text = "仅 Agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(
                                onClick = { onControl(device.entityId, device.domain) },
                            ) {
                                Text("开关")
                            }
                        }
                    }
                }
            }
        }
    }
}
