package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.AppAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppAliasDao {
    @Query("SELECT * FROM app_aliases")
    fun observeAll(): Flow<List<AppAliasEntity>>

    @Query("SELECT * FROM app_aliases WHERE alias = :alias LIMIT 1")
    suspend fun findByAlias(alias: String): AppAliasEntity?

    @Insert
    suspend fun insert(alias: AppAliasEntity): Long
}
