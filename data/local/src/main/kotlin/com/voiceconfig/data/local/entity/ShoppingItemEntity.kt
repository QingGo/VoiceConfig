package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shopping_items",
    indices = [Index(value = ["productId"], unique = true), Index(value = ["platform"])],
)
data class ShoppingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val title: String,
    val platform: String,
    val price: Double,
    val originalPrice: Double? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val sales: Int? = null,
    val tagsJson: String = "[]",
    val url: String = "",
    val note: String = "",
    val status: String = "WATCH",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
