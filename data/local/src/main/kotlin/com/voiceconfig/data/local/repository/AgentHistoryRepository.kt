package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentStepEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import kotlinx.coroutines.flow.Flow

interface AgentHistoryRepository {
    fun observeSessions(): Flow<List<AgentSessionEntity>>
    fun observeMessages(sessionId: Long): Flow<List<AgentMessageEntity>>
    fun observeSteps(sessionId: Long): Flow<List<AgentStepEntity>>
    fun observeTaskEvents(): Flow<List<TaskEventEntity>>
    fun observeTaskEvents(taskId: Long): Flow<List<TaskEventEntity>>
    suspend fun getMessages(sessionId: Long): List<AgentMessageEntity>
    suspend fun getSteps(sessionId: Long): List<AgentStepEntity>
    suspend fun getSession(sessionId: Long): AgentSessionEntity?
    suspend fun createSession(title: String, now: Long): Long
    suspend fun addMessage(message: AgentMessageEntity): Long
    suspend fun updateSession(sessionId: Long, title: String, now: Long, messageCount: Int)
    suspend fun updateSessionDuration(sessionId: Long, durationMs: Long?, now: Long)
    suspend fun renameSession(sessionId: Long, title: String)
    suspend fun deleteSession(sessionId: Long)
    suspend fun clearMessages(sessionId: Long)
    suspend fun upsertStep(step: AgentStepEntity): Long
    suspend fun clearSteps(sessionId: Long)
    suspend fun addTaskEvent(event: TaskEventEntity): Long
}
