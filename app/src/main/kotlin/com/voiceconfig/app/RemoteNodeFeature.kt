package com.voiceconfig.app

import com.voiceconfig.app.remote.RemoteCommandClient
import com.voiceconfig.app.remote.RemoteCommandResult
import com.voiceconfig.app.remote.RemoteMonitorClient
import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import com.voiceconfig.data.local.repository.RemoteProjectRecord
import com.voiceconfig.data.local.repository.RemoteProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteNodeFeature @Inject constructor(
    private val nodeRepository: RemoteNodeRepository,
    private val projectRepository: RemoteProjectRepository,
    private val commandClient: RemoteCommandClient,
) {
    val nodes: Flow<List<RemoteNode>> = nodeRepository.observeNodes()
    val projects: Flow<List<RemoteProjectRecord>> = projectRepository.observeProjects()

    private val _commandResult = MutableStateFlow<RemoteCommandResult?>(null)
    val commandResult: StateFlow<RemoteCommandResult?> = _commandResult.asStateFlow()

    suspend fun saveNode(node: RemoteNode) {
        runCatching { nodeRepository.saveNode(node) }
    }

    suspend fun deleteNode(id: Long) {
        runCatching { nodeRepository.deleteNode(id) }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        runCatching { nodeRepository.setEnabled(id, enabled) }
    }

    suspend fun setPaused(id: Long, paused: Boolean) {
        runCatching { nodeRepository.setPaused(id, paused) }
    }

    suspend fun getNode(id: Long): RemoteNode? = nodeRepository.getNode(id)

    suspend fun refreshNode(id: Long) {
        val node = nodeRepository.getNode(id) ?: return
        runCatching {
            val monitor = RemoteMonitorClient(nodeRepository)
            val snapshot = monitor.snapshot(node.name)
            nodeRepository.markSeen(node.id, "online", null)
            snapshot
        }
    }

    suspend fun executeCommand(node: RemoteNode, command: String) {
        _commandResult.value = null
        _commandResult.value = runCatching {
            commandClient.execute(node.name, command)
        }.getOrElse { e ->
            RemoteCommandResult(
                ok = false,
                command = command,
                stdout = "",
                stderr = "",
                exitCode = null,
                error = e.message ?: "执行失败",
            )
        }
    }

    fun clearCommandResult() {
        _commandResult.value = null
    }
}
