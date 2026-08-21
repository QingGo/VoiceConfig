package com.voiceconfig.app.executor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.voiceconfig.core.executor.ExecutionChannel
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.executor.ExecutionResult
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationExecutionChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExecutionChannel {

    override val supportedMode: ExecutionMode = ExecutionMode.NOTIFICATION

    override fun canExecute(request: ExecutionRequest): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        val task = request.task
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)

        val contentIntent = buildContentIntent(task, task.actionType, task.targetPackage, task.deepLink)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(task.title)
            .setContentText(task.rawText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply {
                if (contentIntent != null) {
                    setFullScreenIntent(contentIntent, true)
                }
            }
            .build()

        notificationManager.notify(task.id.toInt(), notification)
        return ExecutionResult.success(ExecutionMode.NOTIFICATION)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "任务执行",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "定时自动化任务执行提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildContentIntent(
        task: com.voiceconfig.core.model.Task,
        actionType: ActionType,
        targetPackage: String?,
        deepLink: String?,
    ): PendingIntent? {
        val intent = when (actionType) {
            ActionType.OPEN_DEEPLINK -> deepLink?.let {
                Intent(Intent.ACTION_VIEW, Uri.parse(it))
            }
            ActionType.OPEN_APP -> targetPackage?.let { pkg ->
                val activity = task.targetActivity
                if (activity != null) {
                    Intent().setClassName(pkg, activity)
                } else {
                    context.packageManager.getLaunchIntentForPackage(pkg)
                }
            }
            else -> null
        } ?: Intent(context, com.voiceconfig.app.MainActivity::class.java)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            targetPackage?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "task_execution"
    }
}
