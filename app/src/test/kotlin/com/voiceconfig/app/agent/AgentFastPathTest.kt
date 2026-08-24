package com.voiceconfig.app.agent

import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private object FastPathNoOpTrace : AgentTrace {
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    override fun startRun(userText: String): String = "test-run-${counter.incrementAndGet()}"
    override fun log(runId: String, type: String, data: Map<String, Any?>) {}
    override fun saveScreenshot(runId: String, base64: String, label: String): String = ""
}

class AgentFastPathTest {

    private class RecordingTaskRepository : TaskRepository {
        var saved: Task? = null
        override fun observeTasks(): Flow<List<Task>> = flowOf(emptyList())
        override fun observeEnabledTasks(): Flow<List<Task>> = flowOf(emptyList())
        override suspend fun getTask(taskId: Long): Task? = saved?.takeIf { it.id == taskId }
        override suspend fun saveTask(task: Task): Long {
            saved = task.copy(id = 42)
            return 42
        }
        override suspend fun deleteTask(taskId: Long) { saved = null }
        override suspend fun setEnabled(taskId: Long, enabled: Boolean) {}
        override suspend fun getEnabledTasks(): List<Task> = emptyList()
    }

    private class RecordingTaskScheduler : TaskScheduler {
        var scheduled: Task? = null
        var cancelledId: Long? = null
        override fun schedule(task: Task) { scheduled = task }
        override fun cancel(taskId: Long) { cancelledId = taskId }
        override fun restoreAll(tasks: List<Task>) {}
    }

    private class FakeToolChatClient(
        private val responses: List<AgentChatResponse?>,
    ) : AgentToolChat {
        override var lastError: String? = null
        private var index = 0
        override suspend fun completeWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
        ): AgentChatResponse? {
            val response = if (index < responses.size) responses[index] else responses.lastOrNull { it != null }
            index++
            return response
        }

        override suspend fun streamWithTools(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<AgentTool>,
            onEvent: (AgentStreamEvent) -> Unit,
        ): AgentChatResponse? {
            val response = completeWithTools(systemPrompt, messages, tools)
            onEvent(AgentStreamEvent.Done(response))
            return response
        }
    }

    private class SimpleTool(
        override val name: String,
        override val description: String = name,
    ) : AgentTool {
        override suspend fun execute(args: Map<String, Any?>): ToolResult =
            ToolResult.success("$name ok", mapOf("name" to name))
    }

    private fun sessionWith(
        tool: AgentTool,
        responses: List<AgentChatResponse?>,
        store: TaskPlanStore = TaskPlanStore(InMemoryTaskPlanPersistence()),
    ): AgentSession {
        val registry = ToolRegistry().register(tool)
        return AgentSession(registry, FakeToolChatClient(responses), FastPathNoOpTrace, store).apply {
            argumentParser = { JsonToolCallParser.parseArguments(it) }
        }
    }

    @Test
    fun `open app fast path calls only open_app and creates no task plan`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = SimpleTool("open_app")
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "open_app", """{"package":"com.tencent.wework"}""")),
                ),
                AgentChatResponse(content = "已完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            store,
        )
        val result = session.send("打开企业微信")
        assertTrue(result.ok)
        assertEquals(listOf("open_app"), result.toolCalls.map { it.tool })
        assertTrue(result.toolCalls.none { it.tool == "task_plan" })
        assertNull(store.snapshot())
    }

    @Test
    fun `create reminder fast path calls only create_reminder and creates no task plan`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = SimpleTool("create_reminder")
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(AgentToolCall("call1", "create_reminder", """{"content":"喝水","time":"08:00"}""")),
                ),
                AgentChatResponse(content = "已创建提醒", reasoningContent = null, toolCalls = emptyList()),
            ),
            store,
        )
        val result = session.send("提醒我8点喝水")
        assertTrue(result.ok)
        assertEquals(listOf("create_reminder"), result.toolCalls.map { it.tool })
        assertTrue(result.toolCalls.none { it.tool == "task_plan" })
        assertNull(store.snapshot())
    }

    @Test
    fun `complex coffee task may use task_plan`() = runBlocking {
        val planTool = object : AgentTool {
            override val name: String = "task_plan"
            override val description: String = "plan"
            override suspend fun execute(args: Map<String, Any?>): ToolResult =
                ToolResult.success("计划已创建", mapOf("plan" to "ok"))
        }
        val session = sessionWith(
            planTool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(
                        AgentToolCall("call1", "task_plan", """{"action":"create","goal":"点一杯瑞幸冰美式","steps":["打开瑞幸","选择冰美式"]}"""),
                    ),
                ),
                AgentChatResponse(content = "已完成", reasoningContent = null, toolCalls = emptyList()),
            ),
        )
        val result = session.send("帮我点一杯瑞幸冰美式")
        assertTrue(result.ok)
        assertTrue(result.toolCalls.any { it.tool == "task_plan" })
    }

    @Test
    fun `create reminder tool persists and schedules reminder`() = runBlocking {
        val repo = RecordingTaskRepository()
        val scheduler = RecordingTaskScheduler()
        val tool = CreateReminderTool(
            taskRepository = repo,
            taskScheduler = scheduler,
            nextRunCalculator = com.voiceconfig.core.scheduler.NextRunCalculator(),
        )
        val result = tool.execute(
            mapOf(
                "content" to "喝水",
                "scheduleType" to "INTERVAL",
                "intervalMinutes" to 30L,
            ),
        )
        assertTrue(result.ok)
        assertEquals(42L, repo.saved?.id)
        assertEquals(ScheduleSpec.ScheduleType.INTERVAL, repo.saved?.schedule?.type)
        assertEquals(42L, scheduler.scheduled?.id)
        assertEquals(com.voiceconfig.core.model.ActionType.NOTIFY, repo.saved?.actionType)
        assertEquals(com.voiceconfig.core.model.ExecutionMode.NOTIFICATION, repo.saved?.executionMode)
    }

    @Test
    fun `create reminder tool accepts plain 8am reminder`() = runBlocking {
        val repo = RecordingTaskRepository()
        val scheduler = RecordingTaskScheduler()
        val tool = CreateReminderTool(
            taskRepository = repo,
            taskScheduler = scheduler,
            nextRunCalculator = com.voiceconfig.core.scheduler.NextRunCalculator(),
        )
        val result = tool.execute(mapOf("content" to "喝水", "time" to "08:00"))
        assertTrue(result.ok)
        assertTrue(repo.saved != null)
        assertTrue(scheduler.scheduled != null)
    }

    @Test
    fun `create reminder tool rejects missing time`() = runBlocking {
        val tool = CreateReminderTool(
            taskRepository = RecordingTaskRepository(),
            taskScheduler = RecordingTaskScheduler(),
            nextRunCalculator = com.voiceconfig.core.scheduler.NextRunCalculator(),
        )
        val result = tool.execute(mapOf("content" to "喝水"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("时间"))
    }

    @Test
    fun `create scheduled task tools persists and schedules open app`() = runBlocking {
        val repo = RecordingTaskRepository()
        val scheduler = RecordingTaskScheduler()
        val tool = CreateScheduledTaskTool(
            taskRepository = repo,
            taskScheduler = scheduler,
            nextRunCalculator = com.voiceconfig.core.scheduler.NextRunCalculator(),
        )
        val result = tool.execute(
            mapOf(
                "action" to "open_app",
                "package" to "com.tencent.wework",
                "scheduleType" to "INTERVAL",
                "intervalMinutes" to 60L,
            ),
        )
        assertTrue(result.ok)
        assertEquals(42L, repo.saved?.id)
        assertEquals(com.voiceconfig.core.model.ActionType.OPEN_APP, repo.saved?.actionType)
        assertEquals("com.tencent.wework", repo.saved?.targetPackage)
        assertEquals(42L, scheduler.scheduled?.id)
    }

    @Test
    fun `scheduled task fast path calls only create_scheduled_task without task plan`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = SimpleTool("create_scheduled_task")
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(
                    content = null,
                    reasoningContent = null,
                    toolCalls = listOf(
                        AgentToolCall(
                            "call1",
                            "create_scheduled_task",
                            """{"action":"open_app","package":"com.tencent.wework","scheduleType":"DAILY","time":"08:00"}""",
                        ),
                    ),
                ),
                AgentChatResponse(content = "已创建定时任务", reasoningContent = null, toolCalls = emptyList()),
            ),
            store,
        )
        val result = session.send("每天早上8点打开企业微信")
        assertTrue(result.ok)
        assertEquals(listOf("create_scheduled_task"), result.toolCalls.map { it.tool })
        assertTrue(result.toolCalls.none { it.tool == "task_plan" })
        assertNull(store.snapshot())
    }

    @Test
    fun `pause stops run and leaves resumable waiting plan`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        lateinit var session: AgentSession
        val tool = object : AgentTool {
            override val name: String = "open_app"
            override val description: String = "open app"
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                session.pause()
                return ToolResult.success("opened")
            }
        }
        val registry = ToolRegistry().register(tool)
        session = AgentSession(
            registry,
            FakeToolChatClient(
                listOf(
                    AgentChatResponse(
                        content = null,
                        reasoningContent = null,
                        toolCalls = listOf(AgentToolCall("call1", "open_app", "{}")),
                    ),
                ),
            ),
            FastPathNoOpTrace,
            store,
        ).apply { argumentParser = { emptyMap() } }

        val result = session.send("打开企业微信")
        assertTrue(result.ok)
        assertEquals(AgentRunState.WAITING_CONFIRM, result.state)
        assertTrue(result.message.contains("暂停"))
        assertTrue(store.snapshot()?.waitingForHuman?.contains("暂停") == true)
        assertEquals(TaskPlanStatus.WAITING_CONFIRM, store.snapshot()?.status)
    }

    @Test
    fun `run id cancel does not leak into the next run`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = SimpleTool("echo")
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(content = "第一次完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            store,
        )
        val first = session.send("第一次")
        assertTrue(first.ok)

        val cancelledRunId = session.currentRunId()
        session.cancel(cancelledRunId)

        val second = session.send("第二次")
        assertTrue(second.ok)
        assertFalse(second.message.contains("已停止"))
        assertTrue(second.runId != first.runId)
    }

    @Test
    fun `run id pause does not leak into the next run`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = SimpleTool("echo")
        val session = sessionWith(
            tool,
            listOf(
                AgentChatResponse(content = "第一次完成", reasoningContent = null, toolCalls = emptyList()),
            ),
            store,
        )
        val first = session.send("第一次")
        assertTrue(first.ok)

        session.pause(session.currentRunId())
        val second = session.send("第二次")
        assertTrue(second.ok)
        assertTrue(second.state == AgentRunState.DONE)
    }
}

class WaitUserToolTest {
    @Test
    fun `wait_user pauses even without an existing plan`() = runBlocking {
        val store = TaskPlanStore(InMemoryTaskPlanPersistence())
        val tool = WaitUserTool(store)
        val result = tool.execute(mapOf("reason" to "请确认支付"))
        org.junit.Assert.assertTrue(result.ok)
        org.junit.Assert.assertTrue(store.snapshot()?.waitingForHuman == "请确认支付")
        org.junit.Assert.assertEquals(TaskPlanStatus.WAITING_CONFIRM, store.snapshot()?.status)
    }
}
