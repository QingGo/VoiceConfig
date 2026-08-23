package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanTest {

    @Test
    fun `stop verifier returns unknown when no plan`() {
        assertEquals(StopDecision.UNKNOWN, StopVerifier().evaluate(null))
    }

    @Test
    fun `stop verifier returns done only when all steps completed`() {
        val plan = TaskPlan(
            goal = "买咖啡",
            steps = mutableListOf(
                TaskStep("step_1", "打开App", TaskStepStatus.COMPLETED, "已打开"),
                TaskStep("step_2", "下单", TaskStepStatus.COMPLETED, "已到确认页"),
            ),
        )
        assertEquals(StopDecision.DONE, StopVerifier().evaluate(plan))
    }

    @Test
    fun `stop verifier returns continue when steps pending`() {
        val plan = TaskPlan(
            goal = "买咖啡",
            steps = mutableListOf(
                TaskStep("step_1", "打开App", TaskStepStatus.COMPLETED, "已打开"),
                TaskStep("step_2", "下单", TaskStepStatus.PENDING),
            ),
        )
        assertEquals(StopDecision.CONTINUE, StopVerifier().evaluate(plan))
    }

    @Test
    fun `stop verifier returns wait user when waiting`() {
        val plan = TaskPlan(
            goal = "买咖啡",
            steps = mutableListOf(TaskStep("step_1", "下单", TaskStepStatus.COMPLETED)),
            waitingForHuman = "请确认支付",
        )
        assertEquals(StopDecision.WAIT_USER, StopVerifier().evaluate(plan))
    }

    @Test
    fun `task plan tool create update and wait`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = TaskPlanTool(store)

        val create = tool.execute(mapOf("action" to "create", "goal" to "买齐母婴用品", "steps" to listOf("搜索", "比价", "加购")))
        assertTrue(create.ok)
        assertEquals(3, store.snapshot()?.steps?.size)

        val update = tool.execute(mapOf("action" to "update", "stepId" to "step_1", "status" to "COMPLETED", "evidence" to "已搜索到商品"))
        assertTrue(update.ok)
        assertEquals(TaskStepStatus.COMPLETED, store.snapshot()?.steps?.get(0)?.status)

        val wait = tool.execute(mapOf("action" to "wait_user", "reason" to "等待用户确认购物清单"))
        assertTrue(wait.ok)
        assertEquals("等待用户确认购物清单", store.snapshot()?.waitingForHuman)
    }
    @Test
    fun `stop verifier requires evidence before done`() {
        val plan = TaskPlan(
            goal = "测试",
            steps = mutableListOf(
                TaskStep("step_1", "完成", TaskStepStatus.COMPLETED),
            ),
        )
        assertEquals(StopDecision.CONTINUE, StopVerifier().evaluate(plan))
        assertEquals(StopDecision.DONE, StopVerifier().evaluate(plan, uiEvidence = "已看到确认页"))
    }

    @Test
    fun `task plan store persists through in memory persistence`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        store.set(TaskPlan("plan_1", "买咖啡", mutableListOf(TaskStep("s1", "搜索", TaskStepStatus.COMPLETED, "已搜索"))))
        store.saveCurrent()
        val loaded = store.loadActive()
        assertTrue(loaded != null)
        assertEquals("买咖啡", loaded?.goal)
        assertEquals(1, loaded?.steps?.size)
    }

}
