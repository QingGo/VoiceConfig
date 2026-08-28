package com.voiceconfig.app

import com.voiceconfig.data.local.repository.ShoppingItemRecord
import com.voiceconfig.data.local.repository.ShoppingItemRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingFeature @Inject constructor(
    private val repository: ShoppingItemRepository,
) {
    val items: Flow<List<ShoppingItemRecord>> = repository.observeItems()

    suspend fun getByProductId(productId: String): ShoppingItemRecord? =
        repository.getByProductId(productId)

    suspend fun updateStatus(productId: String, status: String) {
        repository.getByProductId(productId)?.let {
            repository.updateStatus(it.id, status)
        }
    }

    suspend fun delete(productId: String) {
        repository.getByProductId(productId)?.let {
            repository.delete(it.id)
        }
    }

    suspend fun clear() {
        repository.getItems().forEach { repository.delete(it.id) }
    }
}
