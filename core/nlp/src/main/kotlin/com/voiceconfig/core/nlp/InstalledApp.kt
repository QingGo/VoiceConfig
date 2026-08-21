package com.voiceconfig.core.nlp

data class InstalledApp(
    val packageName: String,
    val label: String,
    val activityName: String? = null,
)
