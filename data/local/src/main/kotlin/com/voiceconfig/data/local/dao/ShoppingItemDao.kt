package com.voiceconfig.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voiceconfig.data.local.entity.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingItemDao {
    @Query("SELECT * FROM shopping_items ORDER BY updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE status = :status ORDER BY updatedAtEpochMillis DESC")
    suspend fun getByStatus(status: String): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE productId = :productId LIMIT 1")
    suspend fun getByProductId(productId: String): ShoppingItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShoppingItemEntity): Long

    @Query("UPDATE shopping_items SET status = :status, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Delete
    suspend fun delete(item: ShoppingItemEntity)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun deleteById(id: Long)
}
