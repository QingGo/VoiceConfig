package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.RemoteNodeDao
import com.voiceconfig.data.local.entity.RemoteNodeEntity
import com.voiceconfig.data.local.security.RemoteNodeTokenCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RemoteNode(
    val id: Long = 0,
    val nodeId: String,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String = "http",
    val token: String? = null,
    val allowedCommands: List<String> = emptyList(),
    val enabled: Boolean = true,
    val paused: Boolean = false,
    val createdAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val lastSeenAtEpochMillis: Long? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
)

interface RemoteNodeRepository {
    fun observeNodes(): Flow<List<RemoteNode>>
    suspend fun getNodes(): List<RemoteNode>
    suspend fun getNode(id: Long): RemoteNode?
    suspend fun getByNodeId(nodeId: String): RemoteNode?
    suspend fun saveNode(node: RemoteNode): Long
    suspend fun deleteNode(id: Long)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun setPaused(id: Long, paused: Boolean)
    suspend fun markSeen(id: Long, status: String?, error: String?)
}

class OfflineRemoteNodeRepository(
    private val dao: RemoteNodeDao,
    private val cipher: RemoteNodeTokenCipher,
) : RemoteNodeRepository {

    override fun observeNodes(): Flow<List<RemoteNode>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain(cipher) } }

    override suspend fun getNodes(): List<RemoteNode> =
        dao.getAll().map { it.toDomain(cipher) }

    override suspend fun getNode(id: Long): RemoteNode? =
        dao.getById(id)?.toDomain(cipher)

    override suspend fun getByNodeId(nodeId: String): RemoteNode? =
        dao.getByNodeId(nodeId)?.toDomain(cipher)

    override suspend fun saveNode(node: RemoteNode): Long {
        val existing = if (node.id != 0L) dao.getById(node.id) else null
        val now = System.currentTimeMillis()
        val entity = node.toEntity(
            existing = existing,
            cipher = cipher,
            now = now,
        )
        return if (existing == null && node.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            node.id
        }
    }

    override suspend fun deleteNode(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    override suspend fun setPaused(id: Long, paused: Boolean) {
        dao.setPaused(id, paused, System.currentTimeMillis())
    }

    override suspend fun markSeen(id: Long, status: String?, error: String?) {
        dao.markSeen(id, System.currentTimeMillis(), status, error, System.currentTimeMillis())
    }
}

private fun RemoteNodeEntity.toDomain(cipher: RemoteNodeTokenCipher): RemoteNode {
    val ct = tokenCiphertext
    val iv = tokenIv
    val plainToken = if (ct != null && iv != null) {
        runCatching { cipher.decrypt(ct, iv) }.getOrNull()
    } else {
        null
    }
    return RemoteNode(
        id = id,
        nodeId = nodeId,
        name = name,
        host = host,
        port = port,
        scheme = scheme,
        token = plainToken,
        allowedCommands = parseAllowedCommands(allowedCommandsJson),
        enabled = enabled,
        paused = paused,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
        lastStatus = lastStatus,
        lastError = lastError,
    )
}

private fun RemoteNode.toEntity(
    existing: RemoteNodeEntity?,
    cipher: RemoteNodeTokenCipher,
    now: Long,
): RemoteNodeEntity {
    val tokenCiphertext: String?
    val tokenIv: String?
    when {
        token != null -> {
            val encrypted = cipher.encrypt(token)
            tokenCiphertext = encrypted.ciphertext
            tokenIv = encrypted.iv
        }
        existing?.tokenCiphertext != null -> {
            tokenCiphertext = existing.tokenCiphertext
            tokenIv = existing.tokenIv
        }
        else -> {
            tokenCiphertext = null
            tokenIv = null
        }
    }
    return RemoteNodeEntity(
        id = id,
        nodeId = nodeId,
        name = name,
        host = host,
        port = port,
        scheme = scheme,
        tokenCiphertext = tokenCiphertext,
        tokenIv = tokenIv,
        allowedCommandsJson = allowedCommands.joinToString(",") { it },
        enabled = enabled,
        paused = paused,
        createdAtEpochMillis = if (id == 0L) now else (existing?.createdAtEpochMillis ?: now),
        updatedAtEpochMillis = now,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis ?: existing?.lastSeenAtEpochMillis,
        lastStatus = lastStatus ?: existing?.lastStatus,
        lastError = lastError ?: existing?.lastError,
    )
}

private fun parseAllowedCommands(raw: String): List<String> =
    raw.split(",", ";", "\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
