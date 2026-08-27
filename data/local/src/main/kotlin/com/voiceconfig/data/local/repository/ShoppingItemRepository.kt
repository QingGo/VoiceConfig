package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.ShoppingItemDao
import com.voiceconfig.data.local.entity.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ShoppingItemRecord(
    val id: Long = 0,
    val productId: String,
    val title: String,
    val platform: String,
    val price: Double,
    val originalPrice: Double? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val sales: Int? = null,
    val tags: List<String> = emptyList(),
    val url: String = "",
    val note: String = "",
    val status: String = "WATCH",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

interface ShoppingItemRepository {
    fun observeItems(): Flow<List<ShoppingItemRecord>>
    suspend fun getItems(): List<ShoppingItemRecord>
    suspend fun getByStatus(status: String): List<ShoppingItemRecord>
    suspend fun getByProductId(productId: String): ShoppingItemRecord?
    suspend fun save(item: ShoppingItemRecord): Long
    suspend fun updateStatus(id: Long, status: String)
    suspend fun delete(id: Long)
}

class OfflineShoppingItemRepository(
    private val dao: ShoppingItemDao,
) : ShoppingItemRepository {

    override fun observeItems(): Flow<List<ShoppingItemRecord>> =
        dao.observeAll().map { it.map { e -> e.toRecord() } }

    override suspend fun getItems(): List<ShoppingItemRecord> =
        dao.getAll().map { it.toRecord() }

    override suspend fun getByStatus(status: String): List<ShoppingItemRecord> =
        dao.getByStatus(status).map { it.toRecord() }

    override suspend fun getByProductId(productId: String): ShoppingItemRecord? =
        dao.getByProductId(productId)?.toRecord()

    override suspend fun save(item: ShoppingItemRecord): Long {
        val now = System.currentTimeMillis()
        val entity = item.toEntity(now)
        val existing = if (item.id != 0L) dao.getByProductId(item.productId) else null
        return if (existing == null) {
            dao.upsert(entity)
        } else {
            dao.upsert(entity.copy(id = existing.id, createdAtEpochMillis = existing.createdAtEpochMillis))
            existing.id
        }
    }

    override suspend fun updateStatus(id: Long, status: String) {
        dao.updateStatus(id, status, System.currentTimeMillis())
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

private fun ShoppingItemEntity.toRecord(): ShoppingItemRecord = ShoppingItemRecord(
    id = id,
    productId = productId,
    title = title,
    platform = platform,
    price = price,
    originalPrice = originalPrice,
    rating = rating,
    reviewCount = reviewCount,
    sales = sales,
    tags = tagsJson.split(",").map { it.trim() }.filter { it.isNotBlank() },
    url = url,
    note = note,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun ShoppingItemRecord.toEntity(now: Long): ShoppingItemEntity = ShoppingItemEntity(
    productId = productId,
    title = title,
    platform = platform,
    price = price,
    originalPrice = originalPrice,
    rating = rating,
    reviewCount = reviewCount,
    sales = sales,
    tagsJson = tags.joinToString(","),
    url = url,
    note = note,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = now,
)
