package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.voiceconfig.data.local.entity.RemoteNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteNodeDao {
    @Query("SELECT * FROM remote_nodes ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<RemoteNodeEntity>>

    @Query("SELECT * FROM remote_nodes ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<RemoteNodeEntity>

    @Query("SELECT * FROM remote_nodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RemoteNodeEntity?

    @Query("SELECT * FROM remote_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getByNodeId(nodeId: String): RemoteNodeEntity?

    @Insert
    suspend fun insert(entity: RemoteNodeEntity): Long

    @Update
    suspend fun update(entity: RemoteNodeEntity)

    @Delete
    suspend fun delete(entity: RemoteNodeEntity)

    @Query("DELETE FROM remote_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE remote_nodes SET enabled = :enabled, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE remote_nodes SET paused = :paused, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean, updatedAt: Long)

    @Query("UPDATE remote_nodes SET lastSeenAtEpochMillis = :seenAt, lastStatus = :status, lastError = :error, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun markSeen(id: Long, seenAt: Long, status: String?, error: String?, updatedAt: Long)
}
