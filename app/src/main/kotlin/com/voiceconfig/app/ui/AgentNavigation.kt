package com.voiceconfig.app.ui

/**
 * Agent 页面内导航/交互的纯逻辑，便于单元测试。
 */
object AgentNavigation {
    const val TAB_CONVERSATION = 0
    const val TAB_TASKS = 1
    const val TAB_LOGS = 2

    /** 点击“新建会话”后应回到对话 Tab。 */
    fun tabAfterNewSession(): Int = TAB_CONVERSATION

    /** 从会话历史选中一个会话后应回到对话 Tab。 */
    fun tabAfterSelectSession(): Int = TAB_CONVERSATION
}
