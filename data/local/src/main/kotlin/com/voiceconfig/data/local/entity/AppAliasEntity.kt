package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voiceconfig.core.model.AppAlias.AliasSource

@Entity(tableName = "app_aliases")
data class AppAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val packageName: String,
    val activityName: String?,
    val source: AliasSource,
)
