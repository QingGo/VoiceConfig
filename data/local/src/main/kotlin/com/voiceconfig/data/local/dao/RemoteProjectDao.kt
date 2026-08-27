package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.voiceconfig.data.local.entity.RemoteProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteProjectDao {
    @Query("SELECT * FROM remote_projects ORDER BY updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<RemoteProjectEntity>>

    @Query("SELECT * FROM remote_projects ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<RemoteProjectEntity>

    @Query("SELECT * FROM remote_projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getByProjectId(projectId: String): RemoteProjectEntity?

    @Query("SELECT * FROM remote_projects WHERE rootPath = :rootPath AND nodeHost = :nodeHost LIMIT 1")
    suspend fun getByPath(rootPath: String, nodeHost: String): RemoteProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteProjectEntity): Long

    @Update
    suspend fun update(entity: RemoteProjectEntity)

    @Query("DELETE FROM remote_projects WHERE id = :id")
    suspend fun deleteById(id: Long)
}
