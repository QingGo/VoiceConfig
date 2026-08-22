package com.voiceconfig.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_TASK) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val task = taskRepository.getTask(taskId) ?: return@launch
                val startedAt = System.currentTimeMillis()
                val result = if (task.actionType == ActionType.AGENT) {
                    executeAgentTask(task)
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
                executionLogRepository.add(
                    ExecutionLog(
                        taskId = task.id,
                        scheduledAtEpochMillis = task.nextRunAtEpochMillis ?: startedAt,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = System.currentTimeMillis(),
                        status = result.status,
                        executionMode = result.usedMode,
                        errorCode = result.errorCode,
                        message = result.message,
                    ),
                )
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

    private suspend fun executeAgentTask(task: com.voiceconfig.core.model.Task): ExecutionResult {
        if (apiKeyStore.deepSeekApiKey.isBlank()) {
            return ExecutionResult.failure(
                mode = ExecutionMode.AGENT,
                errorCode = "NO_API_KEY",
                message = "未配置 DeepSeek API Key，无法执行智能助手任务",
            )
        }
        val prompt = task.agentPrompt ?: task.rawText
        val skills = agentSkillStore.relevant(prompt)
        val result = agentSession.sendIsolated(
            userText = prompt,
            skills = skills,
            verifyPolicy = AgentVerificationPolicy(
                enabled = apiKeyStore.agentAutoVerifyEnabled,
                maxPerRun = apiKeyStore.agentMaxAutoVerifies,
            ),
            onSensitiveAction = {
                apiKeyStore.agentAutoConfirmSensitiveActions
            },
        )
        return if (result.ok) {
            ExecutionResult.success(ExecutionMode.AGENT).copy(message = result.message)
        } else {
            ExecutionResult.failure(
                mode = ExecutionMode.AGENT,
                errorCode = "AGENT_FAILED",
                message = result.message,
            )
        }
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.voiceconfig.app.action.EXECUTE_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
