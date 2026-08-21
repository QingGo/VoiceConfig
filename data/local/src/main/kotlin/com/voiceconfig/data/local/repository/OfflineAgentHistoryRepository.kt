package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.AgentMessageDao
import com.voiceconfig.data.local.dao.AgentSessionDao
import com.voiceconfig.data.local.dao.TaskEventDao
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import kotlinx.coroutines.flow.Flow

class OfflineAgentHistoryRepository(
    private val sessionDao: AgentSessionDao,
    private val messageDao: AgentMessageDao,
    private val taskEventDao: TaskEventDao,
) : AgentHistoryRepository {

    override fun observeSessions(): Flow<List<AgentSessionEntity>> = sessionDao.observeAll()

    override fun observeMessages(sessionId: Long): Flow<List<AgentMessageEntity>> =
        messageDao.observeBySession(sessionId)

    override fun observeTaskEvents(): Flow<List<TaskEventEntity>> = taskEventDao.observeAll()

    override fun observeTaskEvents(taskId: Long): Flow<List<TaskEventEntity>> =
        taskEventDao.observeByTask(taskId)

    override suspend fun getMessages(sessionId: Long): List<AgentMessageEntity> =
        messageDao.getBySession(sessionId)

    override suspend fun getSession(sessionId: Long): AgentSessionEntity? =
        sessionDao.getById(sessionId)

    override suspend fun createSession(title: String, now: Long): Long =
        sessionDao.insert(
            AgentSessionEntity(
                title = title,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                messageCount = 0,
            ),
        )

    override suspend fun addMessage(message: AgentMessageEntity): Long =
        messageDao.insert(message)

    override suspend fun updateSession(sessionId: Long, title: String, now: Long, messageCount: Int) {
        sessionDao.updateSummary(sessionId, title, now, messageCount)
    }

    override suspend fun renameSession(sessionId: Long, title: String) {
        sessionDao.rename(sessionId, title)
    }

    override suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteById(sessionId)
    }

    override suspend fun clearMessages(sessionId: Long) {
        messageDao.deleteBySession(sessionId)
        val title = sessionDao.getById(sessionId)?.title ?: ""
        sessionDao.updateSummary(sessionId, title, System.currentTimeMillis(), 0)
    }

    override suspend fun addTaskEvent(event: TaskEventEntity): Long =
        taskEventDao.insert(event)
}
