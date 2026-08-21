package com.voiceconfig.app.agent

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能/经验库：把用户确认过的成功 Agent 路径沉淀为可复用参考。
 *
 * 不写死某个 App，而是保存“用户输入 → 工具调用序列”的通用经验。
 * 下次遇到相似任务时，把这些步骤作为参考注入 Agent prompt。
 */
@Singleton
class AgentSkillStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)

    fun record(text: String, toolCalls: List<ToolCall>, ok: Boolean) {
        if (!ok || toolCalls.isEmpty()) return
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val skills = load()
        val existing = skills.firstOrNull { similarity(it.text, normalized) >= 0.5 }
        if (existing != null) {
            val updated = existing.copy(
                text = normalized,
                steps = toolCalls.map { AgentSkillStep(it.tool, it.args.toString()) },
                successCount = existing.successCount + 1,
                updatedAt = System.currentTimeMillis(),
            )
            save(skills.map { if (it.id == existing.id) updated else it })
        } else {
            val skill = AgentSkill(
                id = "skill_${System.currentTimeMillis()}",
                text = normalized,
                steps = toolCalls.map { AgentSkillStep(it.tool, it.args.toString()) },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                successCount = 1,
            )
            save(skills + skill)
        }
    }

    fun relevant(text: String, limit: Int = 3): List<AgentSkill> {
        val skills = load()
        return skills
            .map { it to similarity(it.text, text) }
            .filter { it.second > 0.15 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun load(): List<AgentSkill> {
        val raw = prefs.getString(KEY_SKILLS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val stepsArr = obj.getJSONArray("steps")
                    val steps = buildList {
                        for (j in 0 until stepsArr.length()) {
                            val step = stepsArr.getJSONObject(j)
                            add(
                                AgentSkillStep(
                                    toolName = step.optString("toolName"),
                                    args = step.optString("args"),
                                ),
                            )
                        }
                    }
                    add(
                        AgentSkill(
                            id = obj.optString("id"),
                            text = obj.optString("text"),
                            steps = steps,
                            createdAt = obj.optLong("createdAt"),
                            updatedAt = obj.optLong("updatedAt"),
                            successCount = obj.optInt("successCount", 1),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(skills: List<AgentSkill>) {
        val arr = JSONArray()
        skills.forEach { skill ->
            val stepsArr = JSONArray()
            skill.steps.forEach { step ->
                stepsArr.put(
                    JSONObject()
                        .put("toolName", step.toolName)
                        .put("args", step.args),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("text", skill.text)
                    .put("steps", stepsArr)
                    .put("createdAt", skill.createdAt)
                    .put("updatedAt", skill.updatedAt)
                    .put("successCount", skill.successCount),
            )
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    private fun similarity(a: String, b: String): Double {
        val ca = a.filter { it.isLetterOrDigit() }.toSet()
        val cb = b.filter { it.isLetterOrDigit() }.toSet()
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        val common = ca.intersect(cb).size
        return common.toDouble() / maxOf(ca.size, cb.size)
    }

    private companion object {
        const val KEY_SKILLS = "skills"
    }
}

data class AgentSkill(
    val id: String,
    val text: String,
    val steps: List<AgentSkillStep>,
    val createdAt: Long,
    val updatedAt: Long,
    val successCount: Int,
)

data class AgentSkillStep(
    val toolName: String,
    val args: String,
)
