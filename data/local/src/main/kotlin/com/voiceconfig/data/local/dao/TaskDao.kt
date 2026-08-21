package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.voiceconfig.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY nextRunAtEpochMillis ASC")
    fun observeEnabledTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAtEpochMillis DESC")
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getById(taskId: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET enabled = :enabled WHERE id = :taskId")
    suspend fun setEnabled(taskId: Long, enabled: Boolean)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    @Query("SELECT * FROM tasks WHERE enabled = 1")
    suspend fun getEnabledTasks(): List<TaskEntity>
}
