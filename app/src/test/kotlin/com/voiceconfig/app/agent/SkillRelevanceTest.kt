package com.voiceconfig.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRelevanceTest {

    private fun skill(
        id: String,
        text: String,
        name: String = text,
        tags: List<String> = emptyList(),
        whenToUse: String = "",
    ) = AgentSkill(
        id = id,
        name = name,
        description = "",
        text = text,
        tags = tags,
        whenToUse = whenToUse,
        steps = listOf(AgentSkillStep(toolName = "open_app", args = "{}")),
        createdAt = 0,
        updatedAt = 0,
        successCount = 1,
    )

    @Test
    fun `tag hit boosts luckin skill for coffee request`() {
        val luckin = skill(
            id = "s1",
            text = "在瑞幸咖啡App中帮我下单买一杯咖啡",
            tags = listOf("luckin", "咖啡", "下单"),
        )
        val score = SkillRelevance.score("帮我买一杯咖啡", luckin)
        assertTrue(score > 0.15)
    }

    @Test
    fun `tag hit boosts wecom skill for enterprise message request`() {
        val wecom = skill(
            id = "s2",
            text = "通过企业微信给我发送一条消息",
            tags = listOf("企业微信", "wecom", "消息"),
        )
        val score = SkillRelevance.score("用企业微信发个工作消息", wecom)
        assertTrue(score > 0.15)
    }

    @Test
    fun `unrelated request scores lower or zero`() {
        val luckin = skill(
            id = "s3",
            text = "瑞幸咖啡下单",
            tags = listOf("luckin", "咖啡"),
        )
        val score = SkillRelevance.score("打开计算器", luckin)
        assertTrue(score < 0.15)
    }
}
