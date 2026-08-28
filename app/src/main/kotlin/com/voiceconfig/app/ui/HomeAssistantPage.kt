package com.voiceconfig.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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

@Composable
fun HomeAssistantPage(
    baseUrl: String,
    token: String,
    configured: Boolean,
    devices: List<HomeAssistantDevice>,
    testMessage: String?,
    controlMessage: String?,
    onClose: () -> Unit,
    onSaveAndTest: (String, String) -> Unit,
    onControlService: (String, String, String, Map<String, Any?>) -> Unit,
) {
    var draftBaseUrl by remember { mutableStateOf(baseUrl) }
    var draftToken by remember { mutableStateOf(token) }
    var showToken by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Home Assistant", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (configured) "已连接" else "未配置",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = draftBaseUrl,
                        onValueChange = { draftBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text("http://192.168.1.100:8123") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = draftToken,
                        onValueChange = { draftToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("长期访问令牌") },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) "隐藏" else "显示")
                            }
                        },
                    )
                }
                item {
                    Button(
                        onClick = { onSaveAndTest(draftBaseUrl, draftToken) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = draftBaseUrl.isNotBlank() && draftToken.isNotBlank(),
                    ) {
                        Text("保存并测试连接")
                    }
                }
                testMessage?.let {
                    item {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("已连接")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                controlMessage?.let {
                    item {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                item {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设备", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${devices.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (devices.isEmpty()) {
                    item {
                        VoiceEmptyState(
                            title = "暂无设备",
                            message = "请先保存并测试连接，设备会自动显示在这里",
                        )
                    }
                } else {
                    items(devices, key = { it.entityId }) { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.friendlyName, style = MaterialTheme.typography.bodyLarge)
                                    val currentTemp = device.attributes["current_temperature"] as? Number
                                    Text(
                                        buildString {
                                            append("${device.domain} · ${device.state}")
                                            if (currentTemp != null) {
                                                append(" · 当前 ${currentTemp}°")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val sensitive = device.domain in setOf("lock", "camera", "alarm_control_panel", "siren")
                                val controllable = device.domain in setOf("light", "switch", "fan", "media_player", "input_boolean")
                                if (sensitive) {
                                    Text(
                                        "需确认",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (device.domain == "climate") {
                                    var temp by remember(device.entityId) {
                                        mutableStateOf(
                                            (device.attributes["temperature"] as? Number)?.toFloat() ?: 24f,
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                temp = (temp - 1f).coerceAtLeast(16f)
                                                onControlService(
                                                    device.entityId,
                                                    device.domain,
                                                    "set_temperature",
                                                    mapOf("temperature" to temp),
                                                )
                                            },
                                        ) { Text("-1°") }
                                        Text(
                                            "${temp.toInt()}°",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        TextButton(
                                            onClick = {
                                                temp = (temp + 1f).coerceAtMost(30f)
                                                onControlService(
                                                    device.entityId,
                                                    device.domain,
                                                    "set_temperature",
                                                    mapOf("temperature" to temp),
                                                )
                                            },
                                        ) { Text("+1°") }
                                    }
                                    TextButton(
                                        onClick = {
                                            onControlService(device.entityId, device.domain, "set_hvac_mode", mapOf("hvac_mode" to "auto"))
                                        },
                                    ) { Text("自动") }
                                    TextButton(
                                        onClick = {
                                            onControlService(device.entityId, device.domain, "set_hvac_mode", mapOf("hvac_mode" to "off"))
                                        },
                                    ) { Text("关闭") }
                                } else if (device.domain == "cover") {
                                    TextButton(
                                        onClick = { onControlService(device.entityId, device.domain, "open_cover", emptyMap()) },
                                    ) { Text("打开") }
                                    TextButton(
                                        onClick = { onControlService(device.entityId, device.domain, "close_cover", emptyMap()) },
                                    ) { Text("关闭") }
                                } else if (device.domain == "media_player") {
                                    TextButton(
                                        onClick = { onControlService(device.entityId, device.domain, "media_play_pause", emptyMap()) },
                                    ) { Text("播放/暂停") }
                                    TextButton(
                                        onClick = { onControlService(device.entityId, device.domain, "media_next_track", emptyMap()) },
                                    ) { Text("下一首") }
                                } else if (controllable) {
                                    TextButton(onClick = { onControlService(device.entityId, device.domain, "toggle", emptyMap()) }) {
                                        Text("开关")
                                    }
                                } else {
                                    Text(
                                        "仅 Agent",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
