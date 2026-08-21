package com.voiceconfig.core.scheduler

import com.voiceconfig.core.model.Task

interface TaskScheduler {
    fun schedule(task: Task)
    fun cancel(taskId: Long)
    fun restoreAll(tasks: List<Task>)
}
