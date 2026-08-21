package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.TaskEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskEventDao {
    @Query("SELECT * FROM task_events ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<TaskEventEntity>>

    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY createdAtEpochMillis DESC")
    fun observeByTask(taskId: Long): Flow<List<TaskEventEntity>>

    @Insert
    suspend fun insert(event: TaskEventEntity): Long
}
