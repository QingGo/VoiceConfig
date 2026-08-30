package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSkillCatalogTest {

    @Test
    fun `catalog contains four approved reusable skills`() {
        val now = 1_700_000_000_000L
        val skills = BuiltinSkillCatalog.all(now)
        assertEquals(4, skills.size)
        assertTrue(skills.all { it.status == AgentSkillStatus.APPROVED })
        assertTrue(skills.all { it.enabled })
        assertTrue(skills.all { it.steps.isNotEmpty() })
    }

    @Test
    fun `catalog covers luckin wecom home and remote paths`() {
        val ids = BuiltinSkillCatalog.all().map { it.id }.toSet()
        assertTrue("builtin_luckin_order_to_payment" in ids)
        assertTrue("builtin_wecom_send_message" in ids)
        assertTrue("builtin_home_assistant_control" in ids)
        assertTrue("builtin_remote_project_verify" in ids)
    }

    @Test
    fun `dependency install is not included in remote verification skill`() {
        val remote = BuiltinSkillCatalog.all().first { it.id == "builtin_remote_project_verify" }
        assertTrue(remote.steps.none { it.toolName == "remote_project_install" })
        assertTrue(remote.steps.any { it.toolName == "remote_project_verify" })
    }

    @Test
    fun `personal wechat send is not part of approved builtins`() {
        val allSteps = BuiltinSkillCatalog.all().flatMap { it.steps.map { s -> s.toolName } }
        assertTrue("wechat_send_reply" !in allSteps)
        assertTrue("wechat_open" !in allSteps)
    }

    @Test
    fun `luckin builtin uses the real device package name`() {
        val luckin = BuiltinSkillCatalog.all().first { it.id == "builtin_luckin_order_to_payment" }
        val openStep = luckin.steps.first { it.toolName == "open_app" }
        assertTrue(openStep.args.contains("com.lucky.luckyclient"))
        assertTrue(openStep.expected.contains("com.lucky.luckyclient"))
    }

}