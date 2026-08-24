package com.voiceconfig.app.agent

import com.voiceconfig.data.local.dao.TaskPlanDao
import com.voiceconfig.data.local.entity.TaskPlanEntity
import com.voiceconfig.data.local.entity.TaskPlanStepEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TaskPlan 的 Room 持久化仓库。
 *
 * 当前 TaskPlan / TaskStep 是 app 层模型；这里负责与 data.local 的 entity 互转，
 * 使 Agent 在重启后仍能恢复未完成任务。
 */
@Singleton
class TaskPlanRepository @Inject constructor(
    private val dao: TaskPlanDao,
) : TaskPlanPersistence {

    override suspend fun save(plan: TaskPlan) {
        val planId = plan.id.ifBlank { "plan_${System.currentTimeMillis()}" }
        dao.upsertPlan(
            TaskPlanEntity(
                id = planId,
                goal = plan.goal,
                status = plan.status.name,
                waitingForHuman = plan.waitingForHuman,
                createdAtEpochMillis = plan.createdAtMs,
                updatedAtEpochMillis = plan.updatedAtMs,
            ),
        )
        dao.deleteSteps(planId)
        dao.upsertSteps(
            plan.steps.mapIndexed { index, step ->
                TaskPlanStepEntity(
                    planId = planId,
                    stepId = step.id,
                    title = step.title,
                    status = step.status.name,
                    evidence = step.evidence,
                    note = step.note,
                    sortOrder = index,
                )
            },
        )
    }

    suspend fun load(id: String): TaskPlan? {
        val entity = dao.getPlan(id) ?: return null
        val steps = dao.getSteps(id)
        return entity.toPlan(steps)
    }

    override suspend fun loadActive(): TaskPlan? {
        val entity = dao.getActivePlan() ?: return null
        val steps = dao.getSteps(entity.id)
        return entity.toPlan(steps)
    }

    override suspend fun loadAllActive(): List<TaskPlan> =
        dao.getActivePlans().map { entity ->
            entity.toPlan(dao.getSteps(entity.id))
        }

    override suspend fun delete(id: String) {
        dao.deletePlan(id)
    }

    override suspend fun deleteAllActive() {
        dao.deleteStepsForActivePlans()
        dao.deleteActivePlans()
    }

    private fun TaskPlanEntity.toPlan(steps: List<TaskPlanStepEntity>): TaskPlan = TaskPlan(
        id = id,
        goal = goal,
        steps = steps.map { step ->
            TaskStep(
                id = step.stepId,
                title = step.title,
                status = runCatching { TaskStepStatus.valueOf(step.status) }
                    .getOrDefault(TaskStepStatus.PENDING),
                evidence = step.evidence,
                note = step.note,
            )
        }.toMutableList(),
        waitingForHuman = waitingForHuman,
        status = runCatching { TaskPlanStatus.valueOf(status) }
            .getOrDefault(TaskPlanStatus.ACTIVE),
        createdAtMs = createdAtEpochMillis,
        updatedAtMs = updatedAtEpochMillis,
    )
}
