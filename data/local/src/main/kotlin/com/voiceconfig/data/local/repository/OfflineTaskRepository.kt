package com.voiceconfig.data.local.repository

import com.voiceconfig.core.model.Task
import com.voiceconfig.data.local.dao.TaskDao
import com.voiceconfig.data.local.mapper.TaskMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineTaskRepository(
    private val taskDao: TaskDao,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> =
        taskDao.observeAllTasks().map { entities -> entities.map(TaskMapper::toDomain) }

    override fun observeEnabledTasks(): Flow<List<Task>> =
        taskDao.observeEnabledTasks().map { entities -> entities.map(TaskMapper::toDomain) }

    override suspend fun getTask(taskId: Long): Task? =
        taskDao.getById(taskId)?.let(TaskMapper::toDomain)

    override suspend fun saveTask(task: Task): Long {
        val entity = TaskMapper.toEntity(task)
        return if (task.id == 0L) {
            taskDao.insert(entity)
        } else {
            taskDao.update(entity)
            task.id
        }
    }

    override suspend fun deleteTask(taskId: Long) {
        taskDao.deleteById(taskId)
    }

    override suspend fun setEnabled(taskId: Long, enabled: Boolean) {
        taskDao.setEnabled(taskId, enabled)
    }

    override suspend fun getEnabledTasks(): List<Task> =
        taskDao.getEnabledTasks().map(TaskMapper::toDomain)
}
