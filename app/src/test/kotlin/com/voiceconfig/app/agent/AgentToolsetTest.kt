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

}
