package com.voiceconfig.data.local.repository

import com.voiceconfig.data.local.dao.ShoppingItemDao
import com.voiceconfig.data.local.entity.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingItemRepositoryTest {

    private class FakeShoppingItemDao : ShoppingItemDao {
        private val store = MutableStateFlow<List<ShoppingItemEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<ShoppingItemEntity>> = store
        override suspend fun getAll(): List<ShoppingItemEntity> = store.value
        override suspend fun getByStatus(status: String): List<ShoppingItemEntity> =
            store.value.filter { it.status == status }
        override suspend fun getByProductId(productId: String): ShoppingItemEntity? =
            store.value.firstOrNull { it.productId == productId }
        override suspend fun upsert(item: ShoppingItemEntity): Long {
            val id = if (item.id == 0L) nextId++ else item.id
            val stored = item.copy(id = id)
            store.value = store.value.filterNot { it.productId == stored.productId } + stored
            return id
        }
        override suspend fun updateStatus(id: Long, status: String, updatedAt: Long) {
            store.value = store.value.map {
                if (it.id == id) it.copy(status = status, updatedAtEpochMillis = updatedAt) else it
            }
        }
        override suspend fun delete(item: ShoppingItemEntity) {
            store.value = store.value.filterNot { it.id == item.id }
        }
        override suspend fun deleteById(id: Long) {
            store.value = store.value.filterNot { it.id == id }
        }
    }

    @Test
    fun `save and load shopping item`() = runBlocking {
        val repo = OfflineShoppingItemRepository(FakeShoppingItemDao())
        repo.save(ShoppingItemRecord(
            productId = "p1",
            title = "婴儿奶粉",
            platform = "京东",
            price = 200.0,
            rating = 4.8,
            tags = listOf("奶粉", "婴儿"),
        ))
        val loaded = repo.getByProductId("p1")
        assertNotNull(loaded)
        assertEquals("婴儿奶粉", loaded?.title)
        assertEquals(listOf("奶粉", "婴儿"), loaded?.tags)
    }

    @Test
    fun `update status and delete`() = runBlocking {
        val repo = OfflineShoppingItemRepository(FakeShoppingItemDao())
        val id = repo.save(ShoppingItemRecord(
            productId = "p2",
            title = "纸尿裤",
            platform = "淘宝",
            price = 80.0,
        ))
        repo.updateStatus(id, "BOUGHT")
        assertEquals("BOUGHT", repo.getByProductId("p2")?.status)
        repo.delete(id)
        assertNull(repo.getByProductId("p2"))
    }
}
