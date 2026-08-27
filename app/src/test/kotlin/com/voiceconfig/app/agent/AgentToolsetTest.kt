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
    }

    @Test
    fun `debug and experimental tools do not pollute core tools`() {
        val debugTools = listOf("file_write", "file_read", "clipboard_read", "logcat_read", "open_file")
        debugTools.forEach { name ->
            assertTrue("$name should not be CORE", AgentToolMetadataRegistry.of(name).group != ToolGroup.CORE)
        }
    }

    @Test
    fun `default model groups include core phone and remote but not research or debug`() {
        assertEquals(
            setOf(ToolGroup.CORE, ToolGroup.PHONE, ToolGroup.REMOTE),
            ToolRegistry.DEFAULT_MODEL_GROUPS,
        )
        assertEquals(ToolGroup.PHONE, AgentToolMetadataRegistry.of("tap").group)
        assertEquals(ToolGroup.PHONE, AgentToolMetadataRegistry.of("input_text").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_ssh_exec").group)
        assertEquals(ToolGroup.RESEARCH, AgentToolMetadataRegistry.of("web_search").group)
        assertEquals(ToolGroup.DEBUG, AgentToolMetadataRegistry.of("file_read").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_project_inspect").group)
        assertEquals(ToolGroup.REMOTE, AgentToolMetadataRegistry.of("remote_project_install").group)
    }

}
