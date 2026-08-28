package com.voiceconfig.app.ui

sealed class AppDestination {
    data object Conversation : AppDestination()
    data object Automation : AppDestination()
    data object Profile : AppDestination()
}
