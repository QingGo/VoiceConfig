package com.voiceconfig.app.agent

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentSkillStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

/**
 * 技能/经验库：把用户确认过的成功 Agent 路径沉淀为可复用参考。
 *
 * Phase 1.5 增强：
 * - 每条技能带 runId / 来源会话 / 使用次数 / 审核状态。
 * - 新技能默认进入 PENDING，只有 APPROVED 的技能会注入 Agent prompt。
 * - 用户可以在 Agent 页“技能库”中审核/拒绝/删除。
 */
@Singleton
class AgentSkillStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)

    private val _skills = MutableStateFlow<List<AgentSkill>>(emptyList())
    val skills: StateFlow<List<AgentSkill>> = _skills.asStateFlow()

    init {
        _skills.value = load()
    }

    fun observeSkills(): StateFlow<List<AgentSkill>> = _skills

    fun all(): List<AgentSkill> = load()

    fun record(
        text: String,
        toolCalls: List<ToolCall>,
        ok: Boolean,
        runId: String = "",
        sourceSessionId: Long? = null,
    ) {
        if (!ok || toolCalls.isEmpty()) return
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        val newSteps = toolCalls.map { AgentSkillStep(it.tool, it.args.toString()) }
        val skills = load()
        val existing = skills.firstOrNull { similarity(it.text, normalized) >= 0.5 }
        val updatedList = if (existing != null) {
            skills.map { skill ->
                if (skill.id == existing.id) {
                    skill.copy(
                        text = normalized,
                        steps = newSteps,
                        successCount = skill.successCount + 1,
                        useCount = skill.useCount + 1,
                        updatedAt = now,
                        lastResult = "success",
                        lastRunId = runId,
                        lastSessionId = sourceSessionId,
                    )
                } else {
                    skill
                }
            }
        } else {
            val skill = AgentSkill(
                id = "skill_${now}",
                text = normalized,
                steps = newSteps,
                createdAt = now,
                updatedAt = now,
                successCount = 1,
                useCount = 1,
                status = AgentSkillStatus.PENDING,
                lastRunId = runId,
                lastSessionId = sourceSessionId,
                lastResult = "success",
            )
            skills + skill
        }
        save(updatedList)
    }

    fun relevant(text: String, limit: Int = 3): List<AgentSkill> {
        val skills = load()
        return skills
            .filter { it.status == AgentSkillStatus.APPROVED }
            .map { it to similarity(it.text, text) }
            .filter { it.second > 0.15 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun approve(id: String) = setStatus(id, AgentSkillStatus.APPROVED)
    fun reject(id: String) = setStatus(id, AgentSkillStatus.REJECTED)
    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun setStatus(id: String, status: AgentSkillStatus) {
        save(load().map { if (it.id == id) it.copy(status = status, updatedAt = System.currentTimeMillis()) else it })
    }

    private fun load(): List<AgentSkill> {
        val raw = prefs.getString(KEY_SKILLS, null) ?: return emptyList()
        val result = runCatching {
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
                            useCount = obj.optInt("useCount", 0),
                            status = runCatching {
                                AgentSkillStatus.valueOf(obj.optString("status", AgentSkillStatus.APPROVED.name))
                            }.getOrDefault(AgentSkillStatus.APPROVED),
                            lastRunId = obj.optString("lastRunId"),
                            lastSessionId = obj.optLong("lastSessionId").takeIf { it != 0L },
                            lastResult = obj.optString("lastResult"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        _skills.value = result
        return result
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
                    .put("successCount", skill.successCount)
                    .put("useCount", skill.useCount)
                    .put("status", skill.status.name)
                    .put("lastRunId", skill.lastRunId)
                    .put("lastSessionId", skill.lastSessionId ?: 0L)
                    .put("lastResult", skill.lastResult),
            )
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
        _skills.value = skills
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
    val useCount: Int = 0,
    val status: AgentSkillStatus = AgentSkillStatus.APPROVED,
    val lastRunId: String = "",
    val lastSessionId: Long? = null,
    val lastResult: String = "",
)

data class AgentSkillStep(
    val toolName: String,
    val args: String,
)
