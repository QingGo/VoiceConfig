package com.voiceconfig.app

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.voiceconfig.app.service.AgentAccessibilityService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

data class PermissionStatus(
    val name: String,
    val granted: Boolean,
    val action: (() -> Unit)? = null,
)

@Composable
fun PermissionCheckSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val statuses = remember(refreshKey) {
        runCatching { buildPermissionStatuses(context) }.getOrDefault(emptyList())
    }
    val missingStatuses = statuses.filterNot { it.granted }
    if (missingStatuses.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "待开启权限（${missingStatuses.size}）",
            style = MaterialTheme.typography.titleMedium,
        )
        missingStatuses.forEach { status ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "⚠️ ",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = status.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.action != null) {
                    Button(onClick = { status.action() }) {
                        Text("去开启")
                    }
                }
            }
        }
        if (missingStatuses.any { it.name.contains("Shizuku") }) {
            ShizukuOnboardingCard(context = context, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ShizukuOnboardingCard(context: android.content.Context, modifier: Modifier = Modifier) {
    val adbCommand = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Shizuku 一键引导", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. 安装 Shizuku 并打开一次\n" +
                    "2. 电脑连接手机后执行下面命令启动服务\n" +
                    "3. 在 Shizuku 中授权言控\n" +
                    "4. 返回本页自动检测",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Shizuku ADB 命令", adbCommand))
                    },
                ) {
                    Text("复制 ADB 命令")
                }
                TextButton(
                    onClick = {
                        val intent = listOf(
                            "moe.shizuku.privileged.api",
                            "moe.shizuku.manager",
                            "moe.shizuku.api",
                        ).firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                        context.startActivity(intent)
                    },
                ) {
                    Text("打开 Shizuku")
                }
            }
            Text(
                adbCommand,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun isShizukuReady(): Boolean = runCatching {
    val clazz = Class.forName("rikka.shizuku.Shizuku")
    val ping = clazz.getMethod("pingBinder").invoke(null) as? Boolean ?: return false
    if (!ping) return false
    val permission = clazz.getMethod("checkSelfPermission").invoke(null) as? Int
    permission == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

private fun buildPermissionStatuses(context: Context): List<PermissionStatus> {
    val statuses = mutableListOf<PermissionStatus>()

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        notificationManager.areNotificationsEnabled()
    }
    statuses += PermissionStatus(
        name = "通知权限（到点提醒）",
        granted = notificationGranted,
        action = {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
            }
            context.startActivity(intent)
        },
    )

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val exactAlarmGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    statuses += PermissionStatus(
        name = "精确闹钟（准时执行）",
        granted = exactAlarmGranted,
        action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        } else {
            null
        },
    )

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    statuses += PermissionStatus(
        name = "忽略电池优化（防杀后台）",
        granted = ignoringBattery,
        action = {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
    )

    val accessibilityEnabled = AgentAccessibilityService.instance != null
    statuses += PermissionStatus(
        name = "无障碍服务（无 Shizuku 时的读屏/点击基础）",
        granted = accessibilityEnabled,
        action = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
    )

    val shizukuReady = isShizukuReady()
    statuses += PermissionStatus(
        name = "Shizuku（高级自动打开）",
        granted = shizukuReady,
        action = {
            val intent = listOf(
                "moe.shizuku.privileged.api",
                "moe.shizuku.manager",
                "moe.shizuku.api",
            ).firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
            context.startActivity(intent)
        },
    )

    return statuses
}
