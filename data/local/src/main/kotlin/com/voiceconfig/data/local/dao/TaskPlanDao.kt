package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voiceconfig.data.local.entity.TaskPlanEntity
import com.voiceconfig.data.local.entity.TaskPlanStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(plan: TaskPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<TaskPlanStepEntity>)

    @Query("SELECT * FROM task_plans ORDER BY updatedAtEpochMillis DESC")
    fun observePlans(): Flow<List<TaskPlanEntity>>

    @Query("SELECT * FROM task_plans WHERE id = :id")
    suspend fun getPlan(id: String): TaskPlanEntity?

    @Query("SELECT * FROM task_plans WHERE status != 'COMPLETED' AND status != 'CANCELLED' ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun getActivePlan(): TaskPlanEntity?

    @Query("SELECT * FROM task_plans WHERE status != 'COMPLETED' AND status != 'CANCELLED' ORDER BY updatedAtEpochMillis DESC")
    suspend fun getActivePlans(): List<TaskPlanEntity>

    @Query("SELECT * FROM task_plan_steps WHERE planId = :planId ORDER BY sortOrder ASC, id ASC")
    suspend fun getSteps(planId: String): List<TaskPlanStepEntity>

    @Query("SELECT * FROM task_plan_steps WHERE planId = :planId ORDER BY sortOrder ASC, id ASC")
    fun observeSteps(planId: String): Flow<List<TaskPlanStepEntity>>

    @Query("DELETE FROM task_plan_steps WHERE planId = :planId")
    suspend fun deleteSteps(planId: String)

    @Query("DELETE FROM task_plan_steps WHERE planId IN (SELECT id FROM task_plans WHERE status != 'COMPLETED' AND status != 'CANCELLED')")
    suspend fun deleteStepsForActivePlans()

    @Query("DELETE FROM task_plans WHERE status != 'COMPLETED' AND status != 'CANCELLED'")
    suspend fun deleteActivePlans()

    @Query("DELETE FROM task_plans WHERE id = :id")
    suspend fun deletePlan(id: String)
}
