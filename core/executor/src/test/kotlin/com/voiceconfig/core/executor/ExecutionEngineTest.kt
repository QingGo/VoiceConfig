package com.voiceconfig.core.executor

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionEngineTest {

    private val task = Task(
        id = 1,
        rawText = "test",
        title = "test",
        schedule = ScheduleSpec.daily(LocalTime.of(8, 25)),
        actionType = ActionType.OPEN_APP,
        targetPackage = "com.example",
        executionMode = ExecutionMode.NOTIFICATION,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    @Test
    fun `success uses requested channel`() {
        val channel = FakeChannel(ExecutionMode.NOTIFICATION, success = true)
        val engine = ExecutionEngine(listOf(channel))
        val result = engine.execute(ExecutionRequest(task, ExecutionMode.NOTIFICATION))
        assertEquals(ExecutionStatus.SUCCESS, result.status)
        assertEquals(ExecutionMode.NOTIFICATION, result.usedMode)
    }

    @Test
    fun `fallback to notification when requested fails`() {
        val shizuku = FakeChannel(ExecutionMode.SHIZUKU, success = false)
        val notification = FakeChannel(ExecutionMode.NOTIFICATION, success = true)
        val engine = ExecutionEngine(listOf(shizuku, notification))
        val result = engine.execute(ExecutionRequest(task, ExecutionMode.SHIZUKU))
        assertEquals(ExecutionStatus.FALLBACK, result.status)
        assertEquals(ExecutionMode.NOTIFICATION, result.usedMode)
        assertEquals("FAILED", result.errorCode)
    }

    @Test
    fun `fallback keeps unavailable reason when requested channel cannot execute`() {
        val shizuku = FakeChannel(
            ExecutionMode.SHIZUKU,
            success = true,
            canExecute = false,
            unavailableReason = "Shizuku 服务未运行或 Binder 不可用",
        )
        val notification = FakeChannel(ExecutionMode.NOTIFICATION, success = true)
        val engine = ExecutionEngine(listOf(shizuku, notification))
        val result = engine.execute(ExecutionRequest(task, ExecutionMode.SHIZUKU))
        assertEquals(ExecutionStatus.FALLBACK, result.status)
        assertEquals(ExecutionMode.NOTIFICATION, result.usedMode)
        assertEquals("SHIZUKU_UNAVAILABLE", result.errorCode)
        assertEquals("已从 SHIZUKU 降级到 NOTIFICATION：Shizuku 服务未运行或 Binder 不可用", result.message)
    }

    private class FakeChannel(
        override val supportedMode: ExecutionMode,
        private val success: Boolean,
        private val canExecute: Boolean = true,
        private val unavailableReason: String? = null,
    ) : ExecutionChannel {
        override fun canExecute(request: ExecutionRequest): Boolean = canExecute
        override fun unavailableReason(request: ExecutionRequest): String? = unavailableReason
        override fun execute(request: ExecutionRequest): ExecutionResult =
            if (success) ExecutionResult.success(supportedMode)
            else ExecutionResult.failure(supportedMode, "FAILED")
    }

    @Test
    fun `fallback to shizuku before notification`() {
        val deepLink = FakeChannel(ExecutionMode.DEEP_LINK, success = false)
        val shizuku = FakeChannel(ExecutionMode.SHIZUKU, success = true)
        val notification = FakeChannel(ExecutionMode.NOTIFICATION, success = true)
        val engine = ExecutionEngine(listOf(deepLink, shizuku, notification))
        val result = engine.execute(ExecutionRequest(task, ExecutionMode.DEEP_LINK))
        assertEquals(ExecutionStatus.FALLBACK, result.status)
        assertEquals(ExecutionMode.SHIZUKU, result.usedMode)
    }

    @Test
    fun `no channels returns failure`() {
        val engine = ExecutionEngine(emptyList())
        val result = engine.execute(ExecutionRequest(task, ExecutionMode.NOTIFICATION))
        assertEquals(ExecutionStatus.FAILED, result.status)
    }
}
