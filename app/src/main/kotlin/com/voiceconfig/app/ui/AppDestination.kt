package com.voiceconfig.app.ui

/** 顶层与二级页面的导航路由，统一由 Navigation Compose 管理。 */
object AppRoutes {
    const val CONVERSATION = "conversation"
    const val AUTOMATION = "automation"
    const val PROFILE = "profile"
    const val SHOPPING = "shopping"
    const val HOME_ASSISTANT = "home_assistant"
}

/** 页面身份（保留给将来的深层链接 / 导航测试使用）。 */
sealed class AppDestination {
    data object Conversation : AppDestination()
    data object Automation : AppDestination()
    data object Profile : AppDestination()
    data object Shopping : AppDestination()
    data object HomeAssistant : AppDestination()
}
