package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_projects",
    indices = [Index(value = ["projectId"], unique = true), Index(value = ["rootPath"])],
)
data class RemoteProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val nodeHost: String,
    val name: String,
    val rootPath: String,
    val repoType: String,
    val buildCommand: String? = null,
    val testCommand: String? = null,
    val installCommand: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
