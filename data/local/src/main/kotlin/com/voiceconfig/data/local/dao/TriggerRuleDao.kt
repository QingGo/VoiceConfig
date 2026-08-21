package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.voiceconfig.data.local.entity.TriggerRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerRuleDao {
    @Query("SELECT * FROM trigger_rules WHERE enabled = 1 ORDER BY createdAtEpochMillis DESC")
    fun observeEnabled(): Flow<List<TriggerRuleEntity>>

    @Query("SELECT * FROM trigger_rules ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<TriggerRuleEntity>>

    @Query("SELECT * FROM trigger_rules WHERE id = :id")
    suspend fun getById(id: Long): TriggerRuleEntity?

    @Query("SELECT * FROM trigger_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<TriggerRuleEntity>

    @Insert
    suspend fun insert(rule: TriggerRuleEntity): Long

    @Update
    suspend fun update(rule: TriggerRuleEntity)

    @Query("UPDATE trigger_rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun setEnabled(ruleId: Long, enabled: Boolean)

    @Query("DELETE FROM trigger_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: Long)
}
