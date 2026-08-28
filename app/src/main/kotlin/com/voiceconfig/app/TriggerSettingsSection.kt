package com.voiceconfig.app

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.voiceconfig.app.ui.VoiceSectionCard
import com.voiceconfig.core.model.TriggerRule

@Composable
internal fun TriggerSettingsSection(
    rules: List<TriggerRule>,
    onAddWifi: (String, String, String, String, String) -> Unit,
    onAddBattery: (String, Int, String, String, String) -> Unit,
    onAddLocation: (String, Double, Double, Int, String, String, String) -> Unit,
    onToggleRule: (TriggerRule) -> Unit,
    onDeleteRule: (TriggerRule) -> Unit,
) {
    val context = LocalContext.current
    var triggerType by remember { mutableStateOf("wifi") }
    var triggerName by remember { mutableStateOf("") }
    var triggerSsid by remember { mutableStateOf("") }
    var triggerLevel by remember { mutableStateOf(20) }
    var triggerLat by remember { mutableStateOf("") }
    var triggerLng by remember { mutableStateOf("") }
    var triggerRadius by remember { mutableStateOf("100") }
    var triggerPackage by remember { mutableStateOf("") }
    var triggerTap by remember { mutableStateOf("") }
    var triggerInput by remember { mutableStateOf("") }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onAddLocation(
                triggerName,
                triggerLat.toDoubleOrNull() ?: 0.0,
                triggerLng.toDoubleOrNull() ?: 0.0,
                triggerRadius.toIntOrNull() ?: 100,
                triggerPackage,
                triggerTap,
                triggerInput,
            )
        }
    }

    VoiceSectionCard(title = "条件触发器", defaultExpanded = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = triggerType == "wifi", onClick = { triggerType = "wifi" })
            Text("Wi-Fi")
            RadioButton(selected = triggerType == "battery", onClick = { triggerType = "battery" })
            Text("低电量")
            RadioButton(selected = triggerType == "location", onClick = { triggerType = "location" })
            Text("位置")
        }
        OutlinedTextField(
            value = triggerName,
            onValueChange = { triggerName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("触发器名称（可选）") },
            singleLine = true,
        )
        when (triggerType) {
            "wifi" -> OutlinedTextField(
                value = triggerSsid,
                onValueChange = { triggerSsid = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wi-Fi 名称（SSID）") },
                singleLine = true,
            )
            "battery" -> OutlinedTextField(
                value = triggerLevel.toString(),
                onValueChange = { triggerLevel = it.toIntOrNull() ?: 20 },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("低电量阈值 %（1-100）") },
                singleLine = true,
            )
            else -> {
                OutlinedTextField(
                    value = triggerLat,
                    onValueChange = { triggerLat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("纬度（如 31.2304）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = triggerLng,
                    onValueChange = { triggerLng = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("经度（如 121.4737）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = triggerRadius,
                    onValueChange = { triggerRadius = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("半径（米，10-5000）") },
                    singleLine = true,
                )
            }
        }
        OutlinedTextField(
            value = triggerPackage,
            onValueChange = { triggerPackage = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("目标包名（如 com.tencent.wework）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = triggerTap,
            onValueChange = { triggerTap = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("点击坐标（可选，格式 x,y）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = triggerInput,
            onValueChange = { triggerInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入文本（可选）") },
            singleLine = true,
        )
        Button(
            onClick = {
                if (triggerType == "location" &&
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    when (triggerType) {
                        "wifi" -> onAddWifi(triggerName, triggerSsid, triggerPackage, triggerTap, triggerInput)
                        "battery" -> onAddBattery(triggerName, triggerLevel, triggerPackage, triggerTap, triggerInput)
                        else -> onAddLocation(
                            triggerName,
                            triggerLat.toDoubleOrNull() ?: 0.0,
                            triggerLng.toDoubleOrNull() ?: 0.0,
                            triggerRadius.toIntOrNull() ?: 100,
                            triggerPackage,
                            triggerTap,
                            triggerInput,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("创建触发器")
        }
        rules.forEach { rule ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${if (rule.enabled) "已启用" else "已停用"} · ${rule.name} · ${rule.condition.type}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleRule(rule) },
                )
                TextButton(onClick = { onDeleteRule(rule) }) {
                    Text("删除")
                }
            }
        }
    }
}
