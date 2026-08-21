package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionLogMapperTest {

    @Test
    fun `execution log round trip`() {
        val log = ExecutionLog(
            id = 5,
            taskId = 9,
            scheduledAtEpochMillis = 1000L,
            startedAtEpochMillis = 1001L,
            finishedAtEpochMillis = 1002L,
            status = ExecutionStatus.SUCCESS,
            executionMode = ExecutionMode.NOTIFICATION,
            errorCode = null,
            message = "ok",
        )

        val entity = ExecutionLogMapper.toEntity(log)
        assertEquals(5L, entity.id)
        assertEquals(ExecutionStatus.SUCCESS, entity.status)

        val restored = ExecutionLogMapper.toDomain(entity)
        assertEquals(log, restored)
    }
}
