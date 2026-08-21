package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.Task
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>
    fun observeEnabledTasks(): Flow<List<Task>>
    suspend fun getTask(taskId: Long): Task?
    suspend fun saveTask(task: Task): Long
    suspend fun deleteTask(taskId: Long)
    suspend fun setEnabled(taskId: Long, enabled: Boolean)
    suspend fun getEnabledTasks(): List<Task>
}
