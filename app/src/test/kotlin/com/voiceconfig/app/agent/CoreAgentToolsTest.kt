package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAgentToolsTest {

    @Test
    fun `run_shell rejects disallowed command`() = runBlocking {
        val tool = RunShellTool(ShizukuCommandRunner())
        val result = tool.execute(mapOf("command" to "rm -rf /data"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("不允许的命令"))
    }

    @Test
    fun `run_shell rejects dangerous pattern`() = runBlocking {
        val tool = RunShellTool(ShizukuCommandRunner())
        // am 是白名单，但后面带 pm 危险子串应被拦截
        val result = tool.execute(mapOf("command" to "am start -n com.example/.Main pm uninstall com.example"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("禁止"))
    }

    @Test
    fun `run_shell without shizuku returns unavailable`() = runBlocking {
        val tool = RunShellTool(ShizukuCommandRunner())
        val result = tool.execute(mapOf("command" to "getprop ro.product.model"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("Shizuku"))
    }
}
