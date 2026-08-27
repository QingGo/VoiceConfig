package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.RemoteProjectDao
import com.voiceconfig.data.local.entity.RemoteProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RemoteProjectRecord(
    val id: Long = 0,
    val projectId: String,
    val nodeHost: String,
    val name: String,
    val rootPath: String,
    val repoType: String,
    val buildCommand: String? = null,
    val testCommand: String? = null,
    val installCommand: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

interface RemoteProjectRepository {
    fun observeProjects(): Flow<List<RemoteProjectRecord>>
    suspend fun getProjects(): List<RemoteProjectRecord>
    suspend fun getByProjectId(projectId: String): RemoteProjectRecord?
    suspend fun getByPath(rootPath: String, nodeHost: String): RemoteProjectRecord?
    suspend fun save(project: RemoteProjectRecord): Long
    suspend fun delete(id: Long)
}

class OfflineRemoteProjectRepository(
    private val dao: RemoteProjectDao,
) : RemoteProjectRepository {

    override fun observeProjects(): Flow<List<RemoteProjectRecord>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getProjects(): List<RemoteProjectRecord> =
        dao.getAll().map { it.toDomain() }

    override suspend fun getByProjectId(projectId: String): RemoteProjectRecord? =
        dao.getByProjectId(projectId)?.toDomain()

    override suspend fun getByPath(rootPath: String, nodeHost: String): RemoteProjectRecord? =
        dao.getByPath(rootPath, nodeHost)?.toDomain()

    override suspend fun save(project: RemoteProjectRecord): Long {
        val now = System.currentTimeMillis()
        val entity = project.toEntity(now)
        val existing = if (project.id != 0L) {
            dao.getByProjectId(project.projectId)
        } else {
            dao.getByProjectId(project.projectId)
        }
        return if (existing == null) {
            dao.upsert(entity)
        } else {
            dao.update(entity.copy(id = existing.id, createdAtEpochMillis = existing.createdAtEpochMillis))
            existing.id
        }
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

private fun RemoteProjectEntity.toDomain(): RemoteProjectRecord = RemoteProjectRecord(
    id = id,
    projectId = projectId,
    nodeHost = nodeHost,
    name = name,
    rootPath = rootPath,
    repoType = repoType,
    buildCommand = buildCommand,
    testCommand = testCommand,
    installCommand = installCommand,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun RemoteProjectRecord.toEntity(now: Long): RemoteProjectEntity = RemoteProjectEntity(
    projectId = projectId,
    nodeHost = nodeHost,
    name = name,
    rootPath = rootPath,
    repoType = repoType,
    buildCommand = buildCommand,
    testCommand = testCommand,
    installCommand = installCommand,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = now,
)
