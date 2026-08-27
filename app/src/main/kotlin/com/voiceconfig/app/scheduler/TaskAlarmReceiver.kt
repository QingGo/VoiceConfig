package com.voiceconfig.app.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.service.VoiceConfigService
import androidx.core.app.NotificationCompat
import com.voiceconfig.app.agent.AgentCapabilityInspector
import com.voiceconfig.app.agent.AgentPreflight
import com.voiceconfig.app.agent.AgentRunState
import com.voiceconfig.app.agent.AgentStepStatus
import com.voiceconfig.app.agent.AgentStepUi
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.AgentSkillStore
import com.voiceconfig.app.agent.AgentVerificationPolicy
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.core.executor.ExecutionEngine
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.executor.ExecutionResult
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.ExecutionLogRepository
import com.voiceconfig.data.local.repository.TaskRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TaskAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var executionLogRepository: ExecutionLogRepository
    @Inject lateinit var executionEngine: ExecutionEngine
    @Inject lateinit var taskScheduler: TaskScheduler
    @Inject lateinit var nextRunCalculator: NextRunCalculator
    @Inject lateinit var agentSession: AgentSession
    @Inject lateinit var agentSkillStore: AgentSkillStore
    @Inject lateinit var apiKeyStore: ApiKeyStore
    @Inject lateinit var agentCapabilityInspector: AgentCapabilityInspector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAUSE_AGENT) {
            val runId = intent.getStringExtra(EXTRA_RUN_ID)
            if (!runId.isNullOrBlank()) agentSession.pause(runId) else agentSession.pause()
            return
        }
        if (intent.action == ACTION_CANCEL_AGENT) {
            val runId = intent.getStringExtra(EXTRA_RUN_ID)
            if (!runId.isNullOrBlank()) agentSession.cancel(runId) else agentSession.cancel()
            return
        }
        if (intent.action != ACTION_EXECUTE_TASK) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val task = taskRepository.getTask(taskId) ?: return@launch
                if (task.actionType == ActionType.AGENT) {
                    // 确保前台保活服务已启动，降低长任务执行中被系统回收的风险。
                    runCatching { VoiceConfigService.start(context) }
                    notifyAgentStarted(context, task)
                }
                val startedAt = System.currentTimeMillis()
                val result = if (task.actionType == ActionType.AGENT) {
                    executeAgentTask(context, task)
                } else {
                    executionEngine.execute(
                        ExecutionRequest(
                            task = task,
                            requestedMode = when (task.executionMode) {
                                ExecutionMode.AUTO ->
                                    if (!task.deepLink.isNullOrBlank()) ExecutionMode.DEEP_LINK
                                    else ExecutionMode.SHIZUKU
                                else -> task.executionMode
                            },
                        ),
                    )
                }
                val requestedMode = when (task.executionMode) {
                    ExecutionMode.AUTO ->
                        if (!task.deepLink.isNullOrBlank()) ExecutionMode.DEEP_LINK
                        else ExecutionMode.SHIZUKU
                    else -> task.executionMode
                }
                executionLogRepository.add(
                    ExecutionLog(
                        taskId = task.id,
                        scheduledAtEpochMillis = task.nextRunAtEpochMillis ?: startedAt,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = System.currentTimeMillis(),
                        status = result.status,
                        executionMode = result.usedMode,
                        requestedMode = requestedMode,
                        verified = result.verified,
                        errorCode = result.errorCode,
                        message = result.message,
                    ),
                )
                if (task.actionType == ActionType.AGENT) {
                    notifyAgentFinished(context, task, result)
                }
                if (task.schedule.type != com.voiceconfig.core.model.ScheduleSpec.ScheduleType.ONCE) {
                    val nextRun = nextRunCalculator.nextRunAfter(task.schedule)
                    val nextRunAt = nextRun?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                    taskRepository.saveTask(
                        task.copy(
                            nextRunAtEpochMillis = nextRunAt,
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    taskScheduler.schedule(task.copy(nextRunAtEpochMillis = nextRunAt))
                } else if (result.status == ExecutionStatus.SUCCESS || result.status == ExecutionStatus.FALLBACK) {
                    taskRepository.setEnabled(task.id, false)
                }
            } catch (e: Exception) {
                executionLogRepository.add(
                    ExecutionLog(
                        taskId = taskId,
                        scheduledAtEpochMillis = System.currentTimeMillis(),
                        startedAtEpochMillis = System.currentTimeMillis(),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                        status = ExecutionStatus.FAILED,
                        errorCode = "UNEXPECTED_ERROR",
                        message = e.message,
                    ),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeAgentTask(
        context: Context,
        task: com.voiceconfig.core.model.Task,
    ): ExecutionResult {
        if (apiKeyStore.deepSeekApiKey.isBlank()) {
            return ExecutionResult.failure(
                mode = ExecutionMode.AGENT,
                errorCode = "NO_API_KEY",
                message = "未配置 DeepSeek API Key，无法执行智能助手任务",
            )
        }
        val prompt = task.agentPrompt ?: task.rawText
        val capability = agentCapabilityInspector.snapshot()
        val preflight = AgentPreflight.evaluate(capability, prompt)
        if (!preflight.ready) {
            return ExecutionResult.failure(
                mode = ExecutionMode.AGENT,
                errorCode = "CAPABILITY_PREFLIGHT",
                message = preflight.summary(),
            )
        }
        val skills = agentSkillStore.relevant(prompt)
        val capabilitySummary = capability.summary()
        val result = agentSession.sendIsolated(
            userText = prompt,
            skills = skills,
            verifyPolicy = AgentVerificationPolicy(
                enabled = apiKeyStore.agentAutoVerifyEnabled,
                maxPerRun = apiKeyStore.agentMaxAutoVerifies,
            ),
            capabilitySummary = capabilitySummary,
            onStep = { step ->
                if (step.status != AgentStepStatus.RUNNING) {
                    notifyAgentProgress(context, task, step)
                }
            },
            onSensitiveAction = {
                apiKeyStore.agentAutoConfirmSensitiveActions
            },
        )
        if (result.ok) {
            agentSkillStore.recordFromTurn(
                text = prompt,
                result = result,
                capabilitySummary = capabilitySummary,
            )
        }
        return when {
            !result.ok -> ExecutionResult.failure(
                mode = ExecutionMode.AGENT,
                errorCode = "AGENT_FAILED",
                message = result.message,
            )
            result.state == AgentRunState.WAITING_CONFIRM -> ExecutionResult(
                status = ExecutionStatus.WAITING_HUMAN,
                usedMode = ExecutionMode.AGENT,
                message = result.message,
                errorCode = "WAITING_HUMAN",
            )
            else -> ExecutionResult.success(ExecutionMode.AGENT).copy(message = result.message)
        }
    }


    private fun notifyAgentStarted(context: Context, task: com.voiceconfig.core.model.Task) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureBackgroundChannel(manager)
        val notification = NotificationCompat.Builder(context, AGENT_BACKGROUND_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("言控 Agent 正在执行")
            .setContentText(task.rawText.take(80))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "暂停", pauseAgentIntent(context, task.id))
            .addAction(0, "取消", cancelAgentIntent(context, task.id))
            .build()
        manager.notify(notificationId(task.id), notification)
    }

    private fun notifyAgentProgress(
        context: Context,
        task: com.voiceconfig.core.model.Task,
        step: AgentStepUi,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureBackgroundChannel(manager)
        val stepStatus = when (step.status) {
            AgentStepStatus.SUCCESS -> "成功"
            AgentStepStatus.FAILED -> "失败"
            AgentStepStatus.DECLINED -> "已拒绝"
            AgentStepStatus.RUNNING -> "执行中"
        }
        val notification = NotificationCompat.Builder(context, AGENT_BACKGROUND_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("言控 Agent 执行中")
            .setContentText("${step.toolName} $stepStatus：${step.message.take(60)}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "暂停", pauseAgentIntent(context, task.id))
            .addAction(0, "取消", cancelAgentIntent(context, task.id))
            .build()
        manager.notify(notificationId(task.id), notification)
    }

    private fun notifyAgentFinished(
        context: Context,
        task: com.voiceconfig.core.model.Task,
        result: ExecutionResult,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureBackgroundChannel(manager)
        val resultText = when (result.status) {
            ExecutionStatus.SUCCESS -> "执行成功"
            ExecutionStatus.FALLBACK -> "已降级完成"
            ExecutionStatus.FAILED -> "执行失败"
            ExecutionStatus.WAITING_HUMAN -> "等待用户确认"
            ExecutionStatus.SCHEDULED, ExecutionStatus.EXECUTING, ExecutionStatus.SKIPPED -> "已更新"
        }
        val message = resultText + (result.message?.takeIf { it.isNotBlank() }?.let { "：${it.take(80)}" } ?: "")
        val notification = NotificationCompat.Builder(context, AGENT_BACKGROUND_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("言控 Agent ${resultText}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "取消", cancelAgentIntent(context, task.id))
            .build()
        manager.notify(notificationId(task.id), notification)
    }

    private fun ensureBackgroundChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AGENT_BACKGROUND_CHANNEL,
                    "后台 Agent 进度",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "定时/后台 Agent 任务的开始与结果"
                },
            )
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pauseAgentIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt() + 1,
            Intent(context, TaskAlarmReceiver::class.java).apply {
                action = ACTION_PAUSE_AGENT
                putExtra(EXTRA_TASK_ID, taskId)
                agentSession.currentRunId()?.let { putExtra(EXTRA_RUN_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelAgentIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, TaskAlarmReceiver::class.java).apply {
                action = ACTION_CANCEL_AGENT
                putExtra(EXTRA_TASK_ID, taskId)
                agentSession.currentRunId()?.let { putExtra(EXTRA_RUN_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(taskId: Long): Int = (taskId.toInt() + 3000).coerceAtLeast(0)

    companion object {
        const val ACTION_EXECUTE_TASK = "com.voiceconfig.app.action.EXECUTE_TASK"
        const val ACTION_PAUSE_AGENT = "com.voiceconfig.app.action.PAUSE_AGENT"
        const val ACTION_CANCEL_AGENT = "com.voiceconfig.app.action.CANCEL_AGENT"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_RUN_ID = "extra_run_id"
        private const val AGENT_BACKGROUND_CHANNEL = "agent_background_progress"
    }
}
