package com.voiceconfig.app.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.voiceconfig.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知工具：向用户发送一条提醒。
 * 参数：title（可选）、content（必填）
 */
@Singleton
class NotifyTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "notify"
    override val description: String = "发送通知提醒用户，参数：{\"title\": \"可选标题\", \"content\": \"提醒内容\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val content = args["content"]?.toString()?.ifBlank { null } ?: return ToolResult.failure("缺少参数 content")
        val title = args["title"]?.toString()?.ifBlank { null } ?: "言控提醒"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Agent 提醒", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return ToolResult.success("已发送通知：$title - $content")
    }

    companion object {
        private const val CHANNEL_ID = "agent_notify"
        private const val NOTIFICATION_ID = 2001
    }
}
