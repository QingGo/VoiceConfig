package com.voiceconfig.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import com.voiceconfig.app.service.VoiceConfigService
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import java.time.ZoneId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var taskScheduler: TaskScheduler
    @Inject lateinit var triggerRuleRepository: TriggerRuleRepository
    @Inject lateinit var triggerRuleScheduler: TriggerRuleScheduler
    @Inject lateinit var nextRunCalculator: NextRunCalculator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        VoiceConfigService.start(context)
        val pendingResult = goAsync()
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                taskRepository.getEnabledTasks().forEach { task ->
                    val nextRun = nextRunCalculator.nextRunAfter(task.schedule)
                        ?.atZone(ZoneId.systemDefault())
                        ?.toInstant()
                        ?.toEpochMilli()
                    if (nextRun != null) {
                        if (nextRun != task.nextRunAtEpochMillis) {
                            taskRepository.saveTask(
                                task.copy(
                                    nextRunAtEpochMillis = nextRun,
                                    updatedAtEpochMillis = now,
                                ),
                            )
                        }
                        taskScheduler.schedule(task.copy(nextRunAtEpochMillis = nextRun))
                    }
                }
                triggerRuleScheduler.restoreAll(triggerRuleRepository.getEnabled())
            } catch (e: Exception) {
                // 开机恢复失败不应导致系统广播崩溃
            } finally {
                pendingResult.finish()
            }
        }
    }
}
