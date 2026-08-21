package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.voiceconfig.data.local.entity.TemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY usageCount DESC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Insert
    suspend fun insert(template: TemplateEntity): Long

    @Query("UPDATE templates SET usageCount = usageCount + 1 WHERE id = :templateId")
    suspend fun incrementUsage(templateId: Long)

    @Query("DELETE FROM templates WHERE id = :templateId")
    suspend fun deleteById(templateId: Long)
}
