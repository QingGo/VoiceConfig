package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val category: String,
    val configJson: String,
    val usageCount: Long = 0,
)
