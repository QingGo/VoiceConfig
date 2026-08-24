package com.voiceconfig.app.agent

/**
 * Agent 任务计划：把一句话目标拆成可跟踪步骤。
 *
 * 这是复杂长任务的地基：
 * - 步骤状态决定了 Agent 是否还需要继续；
 * - waitingForHuman 表示当前必须等待用户确认；
 * - StopVerifier 基于计划状态决定“结束 / 继续 / 等待用户 / 失败”。
 */
enum class TaskStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED,
    SKIPPED,
}

enum class TaskPlanStatus {
    ACTIVE,
    WAITING_CONFIRM,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TaskStep(
    val id: String,
    val title: String,
    val status: TaskStepStatus = TaskStepStatus.PENDING,
    val evidence: String = "",
    val note: String = "",
)

data class TaskPlan(
    val id: String = "",
    val goal: String,
    val steps: MutableList<TaskStep> = mutableListOf(),
    val waitingForHuman: String? = null,
    val status: TaskPlanStatus = TaskPlanStatus.ACTIVE,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun allCompleted(): Boolean =
        steps.isNotEmpty() &&
            steps.all { it.status == TaskStepStatus.COMPLETED || it.status == TaskStepStatus.SKIPPED }

    fun hasFailedOrBlocked(): Boolean =
        steps.any { it.status == TaskStepStatus.FAILED || it.status == TaskStepStatus.BLOCKED }

    fun pendingSteps(): List<TaskStep> =
        steps.filter { it.status == TaskStepStatus.PENDING || it.status == TaskStepStatus.IN_PROGRESS }

    fun markUpdated() {
        // data class copy not used; we keep mutable current in memory for now.
        // updatedAtMs is set by TaskPlanStore on mutations.
    }
}

/**
 * TaskPlan 持久化抽象，便于测试和后续替换存储。
 */
interface TaskPlanPersistence {
    suspend fun save(plan: TaskPlan)
    suspend fun loadActive(): TaskPlan?
    suspend fun loadAllActive(): List<TaskPlan>
    suspend fun delete(id: String)
    suspend fun deleteAllActive()
}

/**
 * 纯内存 TaskPlanPersistence，主要用于测试/无需 Room 的场景。
 */
class InMemoryTaskPlanPersistence : TaskPlanPersistence {
    private var saved: TaskPlan? = null

    override suspend fun save(plan: TaskPlan) {
        saved = plan
    }

    override suspend fun loadActive(): TaskPlan? = saved

    override suspend fun loadAllActive(): List<TaskPlan> =
        listOfNotNull(saved)

    override suspend fun delete(id: String) {
        if (saved?.id == id) saved = null
    }

    override suspend fun deleteAllActive() {
        saved = null
    }
}

/**
 * 当前运行的 TaskPlan 内存态 + Room 持久化。
 *
 * AgentSession 每次 send 开始时设置 current；task_plan 工具修改后调用 saveCurrent()
 * 保存到 Room，保证 App 重启后仍可恢复。
 */
@javax.inject.Singleton
class TaskPlanStore @javax.inject.Inject constructor(
    private val repository: TaskPlanPersistence,
) {
    @Volatile
    var current: TaskPlan? = null

    fun snapshot(): TaskPlan? = current

    fun set(plan: TaskPlan?) {
        current = if (plan != null && plan.id.isBlank()) {
            plan.copy(id = "plan_${System.currentTimeMillis()}")
        } else {
            plan
        }
    }

    fun update(transform: (TaskPlan) -> TaskPlan) {
        val plan = current ?: return
        val updated = transform(plan)
        current = updated.copy(updatedAtMs = System.currentTimeMillis())
    }

    suspend fun saveCurrent() {
        val plan = current ?: return
        repository.save(plan)
    }

    suspend fun loadActive(): TaskPlan? = repository.loadActive()

    suspend fun loadActivePlans(): List<TaskPlan> = repository.loadAllActive()

    suspend fun delete(id: String) {
        if (id.isBlank()) return
        repository.delete(id)
        if (current?.id == id) current = null
    }

    suspend fun deleteCurrent() {
        val id = current?.id ?: return
        delete(id)
    }

    suspend fun deleteAllActive() {
        repository.deleteAllActive()
        current = null
    }
}

enum class StopDecision {
    /** 没有足够计划信息，回退到原有 completion_check。 */
    UNKNOWN,

    /** 计划未完成，应该继续让 Agent 干活。 */
    CONTINUE,

    /** 到达人类确认点，应暂停并等待用户。 */
    WAIT_USER,

    /** 计划已完成，可以结束。 */
    DONE,

    /** 计划出现阻塞/失败，应停止并报告。 */
    FAILED,
}

/**
 * StopVerifier：基于 TaskPlan 判断是否应该停止。
 *
 * 它不替代 LLM 的语义判断，而是给“停止”加一个可验证的骨架：
 * 只有计划全部完成才允许 DONE；只要存在 waitingForHuman，就必须等待用户。
 */
class StopVerifier {

    /**
     * 基于计划状态判断停止。
     *
     * uiEvidence 是最近一次 read_ui / get_screen_state / read_screen 的结果文本。
     * 当计划步骤全部完成时，如果没有任何 UI 证据且步骤本身也未填写 evidence，
     * 我们仍认为“未验证通过”，返回 CONTINUE，避免模型直接说完成。
     */
    fun evaluate(plan: TaskPlan?, uiEvidence: String = ""): StopDecision {
        if (plan == null) return StopDecision.UNKNOWN
        if (plan.waitingForHuman != null) return StopDecision.WAIT_USER
        if (plan.allCompleted()) {
            val hasStepEvidence = plan.steps.any {
                it.status == TaskStepStatus.COMPLETED && (it.evidence.isNotBlank() || it.note.isNotBlank())
            }
            return if (hasStepEvidence || uiEvidence.isNotBlank()) StopDecision.DONE else StopDecision.CONTINUE
        }
        if (plan.hasFailedOrBlocked()) return StopDecision.FAILED
        if (plan.steps.isNotEmpty()) return StopDecision.CONTINUE
        return StopDecision.UNKNOWN
    }
}
