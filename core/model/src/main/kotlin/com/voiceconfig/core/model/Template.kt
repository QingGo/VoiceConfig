package com.voiceconfig.core.model

data class Template(
    val id: Long = 0,
    val name: String,
    val description: String,
    val category: String,
    val configJson: String,
    val usageCount: Long = 0,
)
