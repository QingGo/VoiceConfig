package com.voiceconfig.app.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.voiceconfig.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无 Shizuku / 熄屏场景下的强提醒工具。
 *
 * 与普通 notify 不同：
 * - 高优先级 + 全屏 Intent，锁屏/熄屏也能直接亮起提醒页面；
 * - 默认同时震动 + 闹钟/通知铃声；
 * - 用于“到点无法自动打开 App，先强提醒用户点亮屏幕再继续”的降级路径。
 */
@Singleton
class StrongReminderTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "strong_remind"

    override val description: String =
        "发送强提醒：全屏高优先级通知 + 震动 + 铃声，适用于熄屏/无 Shizuku 时提醒用户；参数：{\"title\":\"可选\",\"content\":\"必填\",\"fullScreen\":true,\"vibrate\":true,\"sound\":true}"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "提醒",
        group = ToolGroup.CORE,
        risk = ToolRisk.LOW,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val content = args["content"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.failure("缺少参数 content")
        val title = args["title"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "言控强提醒"
        val fullScreen = args["fullScreen"]?.toString()?.toBooleanStrictOrNull() ?: true
        val vibrate = args["vibrate"]?.toString()?.toBooleanStrictOrNull() ?: true
        val sound = args["sound"]?.toString()?.toBooleanStrictOrNull() ?: true

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = createChannel(manager)
        if (channel != null) {
            channel.enableVibration(vibrate)
            channel.setShowBadge(true)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("strong_reminder", title)
                putExtra("strong_reminder_content", content)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreenIntent)
            .apply {
                if (fullScreen) {
                    setFullScreenIntent(fullScreenIntent, true)
                }
                if (vibrate) {
                    setVibrate(LongArray(6) { i -> longArrayOf(0L, 500L, 250L, 500L, 250L, 1000L)[i] })
                }
                if (sound) {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                }
            }
            .build()

        try {
            manager.notify(NOTIFICATION_ID, builder)
        } catch (e: SecurityException) {
            return ToolResult.failure("强提醒失败：缺少通知权限（${e.message ?: "unknown"}）")
        }

        if (vibrate) {
            runCatching { vibrateNow() }
        }

        return ToolResult.success("已发送强提醒：$title - $content")
    }

    private fun createChannel(manager: NotificationManager): NotificationChannel? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val channel = NotificationChannel(
            CHANNEL_ID,
            "言控强提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "熄屏/后台任务的强提醒：全屏+震动+铃声"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 500L, 250L, 500L, 250L, 1000L)
        }
        manager.createNotificationChannel(channel)
        return channel
    }

    private fun vibrateNow() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        val pattern = longArrayOf(0L, 500L, 250L, 500L, 250L, 1000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private companion object {
        const val CHANNEL_ID = "agent_strong_reminder"
        const val NOTIFICATION_ID = 3001
    }
}
