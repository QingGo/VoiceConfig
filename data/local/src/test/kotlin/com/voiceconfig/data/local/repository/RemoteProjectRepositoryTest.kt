package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.RemoteProjectDao
import com.voiceconfig.data.local.entity.RemoteProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteProjectRepositoryTest {

    private class FakeRemoteProjectDao : RemoteProjectDao {
        private val store = MutableStateFlow<List<RemoteProjectEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<RemoteProjectEntity>> = store
        override suspend fun getAll(): List<RemoteProjectEntity> = store.value
        override suspend fun getByProjectId(projectId: String): RemoteProjectEntity? =
            store.value.firstOrNull { it.projectId == projectId }
        override suspend fun getByPath(rootPath: String, nodeHost: String): RemoteProjectEntity? =
            store.value.firstOrNull { it.rootPath == rootPath && it.nodeHost == nodeHost }
        override suspend fun upsert(entity: RemoteProjectEntity): Long {
            val id = if (entity.id == 0L) nextId++ else entity.id
            val stored = entity.copy(id = id)
            store.value = store.value.filterNot { it.projectId == stored.projectId } + stored
            return id
        }
        override suspend fun update(entity: RemoteProjectEntity) {
            store.value = store.value.map {
                if (it.projectId == entity.projectId) entity.copy(id = it.id, createdAtEpochMillis = it.createdAtEpochMillis) else it
            }
        }
        override suspend fun deleteById(id: Long) {
            store.value = store.value.filterNot { it.id == id }
        }
    }

    @Test
    fun `save and load remote project by id and path`() = runBlocking {
        val repository = OfflineRemoteProjectRepository(FakeRemoteProjectDao())
        val id = repository.save(
            RemoteProjectRecord(
                projectId = "rp_pi_gomoku",
                nodeHost = "192.168.31.110",
                name = "gomoku-mobile",
                rootPath = "/home/zeng/gomoku-mobile",
                repoType = "PYTHON",
                buildCommand = "python -m build",
                testCommand = "python -m pytest",
                installCommand = "pip install -r requirements.txt",
            ),
        )
        val byId = repository.getByProjectId("rp_pi_gomoku")
        assertNotNull(byId)
        assertEquals("gomoku-mobile", byId?.name)
        assertEquals("192.168.31.110", byId?.nodeHost)

        val byPath = repository.getByPath("/home/zeng/gomoku-mobile", "192.168.31.110")
        assertEquals("rp_pi_gomoku", byPath?.projectId)
        assertEquals(id, byPath?.id)
    }

    @Test
    fun `update preserves project id and created time`() = runBlocking {
        val repository = OfflineRemoteProjectRepository(FakeRemoteProjectDao())
        val original = RemoteProjectRecord(
            projectId = "rp_test",
            nodeHost = "pi",
            name = "origin",
            rootPath = "/tmp/origin",
            repoType = "NODE",
            buildCommand = "npm run build",
            testCommand = "npm test",
            installCommand = "npm install",
        )
        repository.save(original)
        val loaded = repository.getByProjectId("rp_test")!!
        val updated = loaded.copy(
            name = "renamed",
            buildCommand = "npm run build --prod",
        )
        repository.save(updated)
        val reloaded = repository.getByProjectId("rp_test")!!
        assertEquals("renamed", reloaded.name)
        assertEquals("npm run build --prod", reloaded.buildCommand)
        assertEquals(original.createdAtEpochMillis, reloaded.createdAtEpochMillis)
        assertEquals(loaded.id, reloaded.id)
    }

    @Test
    fun `delete remote project`() = runBlocking {
        val repository = OfflineRemoteProjectRepository(FakeRemoteProjectDao())
        val id = repository.save(RemoteProjectRecord(
            projectId = "rp_del",
            nodeHost = "pi",
            name = "tmp",
            rootPath = "/tmp/del",
            repoType = "GENERIC",
        ))
        repository.delete(id)
        assertNull(repository.getByProjectId("rp_del"))
    }
}
