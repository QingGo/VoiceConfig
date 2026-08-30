package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsetTest {

    @Test
    fun `primary single-step tools are in core group`() {
        val primary = listOf(
            "open_app", "open_search", "create_reminder", "create_scheduled_task",
            "task_plan", "read_ui", "dismiss_popups", "wait_user",
        )
        primary.forEach { name ->
            assertEquals("$name should be CORE", ToolGroup.CORE, AgentToolMetadataRegistry.of(name).group)
        }
        assertEquals(ToolGroup.CORE, AgentToolMetadataRegistry.of("ui_assert").group)
        assertEquals(ToolGroup.CORE, AgentToolMetadataRegistry.of("ui_wait").group)
        assertEquals(ToolRisk.READ_ONLY, AgentToolMetadataRegistry.of("ui_wait").risk)
    }

    @Test
    fun `debug and experimental tools do not pollute core tools`() {
        val debugTools = listOf("file_write", "file_read", "clipboard_read", "logcat_read", "open_file")
        debugTools.forEach { name ->
            assertTrue("$name should not be CORE", AgentToolMetadataRegistry.of(name).group != ToolGroup.CORE)
        }
    }

    @Test
    fun `default model groups include core phone remote and home but not research or debug`() {
        assertEquals(
            setOf(ToolGroup.CORE, ToolGroup.PHONE, ToolGroup.REMOTE, ToolGroup.HOME, ToolGroup.RESEARCH, ToolGroup.APP_SKILL),
            ToolRegistry.DEFAULT_MODEL_GROUPS,
        )
        assertEquals(ToolGroup.PHONE, AgentToolMetadataRegistry.of("tap").group)
        assertEquals(ToolGroup.PHONE, AgentToolMetadataRegistry.of("input_text").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_ssh_exec").group)
        assertEquals(ToolGroup.HOME, AgentToolMetadataRegistry.of("home_devices").group)
        assertEquals(ToolGroup.HOME, AgentToolMetadataRegistry.of("home_control").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("web_search").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("product_compare").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("product_search").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("product_extract").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("shopping_save").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("shopping_list").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("luckin_prepare_order").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("luckin_open").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("luckin_quick_order").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("wechat_draft_reply").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("wechat_open").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("wework_open").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("wechat_read_messages").group)
        assertEquals(ToolGroup.APP_SKILL, AgentToolMetadataRegistry.of("wechat_send_reply").group)
        assertEquals(ToolGroup.DEBUG, AgentToolMetadataRegistry.of("file_read").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_project_inspect").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_project_install").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_project_verify").group)
    }


    @Test
    fun `phone UI mutation tools require automatic visible evidence`() {
        val tools = listOf("tap", "tap_text", "swipe", "press_key", "input_text")
        tools.forEach { name ->
            val meta = AgentToolMetadataRegistry.of(name)
            assertTrue("$name should enable automatic UI verification", meta.requiresAutoVerify)
            assertTrue("$name should be a UI-mutating phone tool", meta.mutatesUi)
            assertEquals("$name should be in PHONE group", ToolGroup.PHONE, meta.group)
        }
    }
}