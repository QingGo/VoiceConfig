package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "remote_nodes", indices = [Index(value = ["nodeId"], unique = true)])
data class RemoteNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: String,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String = "http",
    val tokenCiphertext: String? = null,
    val tokenIv: String? = null,
    val allowedCommandsJson: String,
    val enabled: Boolean = true,
    val paused: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
)
