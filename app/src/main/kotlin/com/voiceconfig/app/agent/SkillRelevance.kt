package com.voiceconfig.app.agent

/**
 * Skill 相关性计算。
 *
 * 除了文本字符集合相似度，还会利用技能标签做关键词命中，
 * 让“买咖啡”“企业微信发消息”“控制家里灯”等自然表达更容易匹配内置 Skill。
 */
object SkillRelevance {

    fun score(userText: String, skill: AgentSkill): Double {
        val base = characterSimilarity(userText, skill.text)
        val tagScore = keywordScore(userText, listOfNotNull(
            skill.name,
            *skill.tags.toTypedArray(),
        ))
        val whenScore = keywordScore(userText, listOf(skill.whenToUse))
        return maxOf(base, tagScore, whenScore)
    }

    private fun characterSimilarity(a: String, b: String): Double {
        val ca = a.filter { it.isLetterOrDigit() }.toSet()
        val cb = b.filter { it.isLetterOrDigit() }.toSet()
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        val common = ca.intersect(cb).size
        return common.toDouble() / maxOf(ca.size, cb.size)
    }

    private fun keywordScore(text: String, candidates: List<String>): Double {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return 0.0
        val distinct = candidates
            .flatMap { it.split("、", "，", ",", " ", "/") }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        var hits = 0
        distinct.forEach { keyword ->
            if (trimmed.contains(keyword, ignoreCase = true)) hits++
        }
        return if (hits > 0) 0.35 + 0.1 * hits else 0.0
    }
}
