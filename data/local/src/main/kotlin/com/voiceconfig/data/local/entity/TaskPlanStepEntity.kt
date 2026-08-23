package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_plan_steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId")],
)
data class TaskPlanStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: String,
    val stepId: String,
    val title: String,
    val status: String,
    val evidence: String = "",
    val note: String = "",
    val sortOrder: Int = 0,
)
