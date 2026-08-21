package com.voiceconfig.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nextRunCalculator: NextRunCalculator = NextRunCalculator(zoneId = ZoneId.systemDefault()),
) : TaskScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(task: Task) {
        val nextRun = nextRunCalculator.nextRunAfter(task.schedule)
            ?: return
        val triggerAt = nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = createPendingIntent(task.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                10 * 60 * 1000L,
                pendingIntent,
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        }
    }

    override fun cancel(taskId: Long) {
        alarmManager.cancel(createPendingIntent(taskId))
    }

    override fun restoreAll(tasks: List<Task>) {
        tasks.forEach(::schedule)
    }

    private fun createPendingIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_EXECUTE_TASK
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
