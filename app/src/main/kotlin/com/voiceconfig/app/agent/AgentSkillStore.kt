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
 * 技能/经验库（P2 结构化版）。
 *
 * - 从成功且验证未失败的 run 自动生成 PENDING Skill；
 * - 每条 Skill 记录目标、步骤、每步目的/预期/UI 证据/验证/兜底、来源 run、能力要求、版本；
 * - 只有 APPROVED 且 enabled 的 Skill 才会注入 Agent prompt；
 * - 支持禁用、删除、脱敏、审计。
 */
@Singleton
class AgentSkillStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)

    private val _skills = MutableStateFlow<List<AgentSkill>>(emptyList())
    val skills: StateFlow<List<AgentSkill>> = _skills.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<AgentSkillAudit>>(emptyList())
    val auditLogs: StateFlow<List<AgentSkillAudit>> = _auditLogs.asStateFlow()

    init {
        _skills.value = load()
        _auditLogs.value = loadAudit()
        seedBuiltinSkillsIfNeeded()
    }

    fun observeSkills(): StateFlow<List<AgentSkill>> = _skills

    fun all(): List<AgentSkill> = _skills.value

    fun observeAuditLogs(): StateFlow<List<AgentSkillAudit>> = _auditLogs

    private fun seedBuiltinSkillsIfNeeded() {
        if (prefs.getBoolean(KEY_BUILTIN_SEEDED, false)) return
        val now = System.currentTimeMillis()
        val existing = load()
        val missing = BuiltinSkillCatalog.all(now).filter { seed ->
            existing.none { it.id == seed.id }
        }
        if (missing.isNotEmpty()) {
            save(existing + missing)
            missing.forEach { seed ->
                appendAudit(seed.id, "seed", "内置技能：${seed.name}")
            }
        }
        prefs.edit().putBoolean(KEY_BUILTIN_SEEDED, true).apply()
    }


    /**
     * 兼容旧接口：只保存工具名和参数，不校验验证证据。
     * 新代码请优先使用 [recordFromTurn]。
     */
    fun record(
        text: String,
        toolCalls: List<ToolCall>,
        ok: Boolean,
        runId: String = "",
        sourceSessionId: Long? = null,
    ) {
        if (!ok || toolCalls.isEmpty()) return
        val skill = AgentSkillBuilder.build(
            goal = text,
            toolCalls = toolCalls,
            toolResults = emptyList(),
            runId = runId,
            sourceSessionId = sourceSessionId,
        ) ?: return
        upsert(skill)
    }

    /**
     * 从一次实时 Agent turn 生成 PENDING Skill 候选。
     * 只有 ok 且验证没有明确失败时才会沉淀。
     */
    fun recordFromTurn(
        text: String,
        result: AgentTurnResult,
        sourceSessionId: Long? = null,
        capabilitySummary: String? = null,
    ) {
        if (!result.ok || result.toolCalls.isEmpty()) return
        val verified = computeVerified(result.toolCalls, result.toolResults)
        if (verified == false) return
        val skill = AgentSkillBuilder.build(
            goal = text,
            toolCalls = result.toolCalls,
            toolResults = result.toolResults,
            runId = result.runId,
            verified = verified,
            capabilitySummary = capabilitySummary,
            sourceSessionId = sourceSessionId,
        ) ?: return
        upsert(skill)
    }

    /**
     * 从 RunRecord + trace 事件重建 PENDING Skill，方便历史成功 run 补沉淀。
     * traceEvents 可为 AgentTrace.readRun(runId) 的原始事件列表。
     */
    fun ingestFromTrace(
        runId: String,
        userText: String,
        traceEvents: List<Map<String, Any?>>,
        verified: Boolean? = null,
        capabilitySummary: String? = null,
        sourceSessionId: Long? = null,
    ): AgentSkill? {
        val normalized = traceEvents.map { normalizeMap(it) as? Map<String, Any?> ?: emptyMap() }
        val skill = AgentSkillBuilder.buildFromTrace(
            goal = userText,
            runId = runId,
            traceEvents = normalized,
            verified = verified,
            capabilitySummary = capabilitySummary,
            sourceSessionId = sourceSessionId,
        ) ?: return null
        upsert(skill)
        return skill
    }

    fun relevant(text: String, limit: Int = 3): List<AgentSkill> {
        return load()
            .filter { it.status == AgentSkillStatus.APPROVED && it.enabled }
            .map { it to SkillRelevance.score(text, it) }
            .filter { it.second > 0.15 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun approve(id: String) = setStatus(id, AgentSkillStatus.APPROVED, "approve")
    fun reject(id: String) = setStatus(id, AgentSkillStatus.REJECTED, "reject")

    fun delete(id: String) {
        val skill = load().firstOrNull { it.id == id }
        if (skill != null) {
            appendAudit(id, "delete", "删除技能：${skill.name}")
        }
        save(load().filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val now = System.currentTimeMillis()
        val action = if (enabled) "enable" else "disable"
        val updated = load().map { skill ->
            if (skill.id == id) {
                skill.copy(
                    enabled = enabled,
                    updatedAt = now,
                    auditLog = skill.auditLog + AgentSkillAudit(
                        skillId = id,
                        at = now,
                        action = action,
                        detail = if (enabled) "启用技能" else "停用技能",
                    ),
                )
            } else {
                skill
            }
        }
        if (updated.any { it.id == id }) {
            appendAudit(id, action, if (enabled) "启用技能" else "停用技能")
            save(updated)
        }
    }

    fun redact(id: String) {
        val now = System.currentTimeMillis()
        val updated = load().map { skill ->
            if (skill.id == id) {
                skill.copy(
                    steps = skill.steps.map { step ->
                        step.copy(args = redactArgs(step.args))
                    },
                    redacted = true,
                    updatedAt = now,
                    auditLog = skill.auditLog + AgentSkillAudit(
                        skillId = id,
                        at = now,
                        action = "redact",
                        detail = "已脱敏参数中的敏感字段",
                    ),
                )
            } else {
                skill
            }
        }
        if (updated.any { it.id == id }) {
            appendAudit(id, "redact", "已脱敏参数中的敏感字段")
            save(updated)
        }
    }

    fun exportSkill(id: String): String? {
        val skill = load().firstOrNull { it.id == id } ?: return null
        return skillToJson(skill).put("format", "voiceconfig-skill").put("formatVersion", 1).toString()
    }

    fun exportAll(): String {
        val arr = JSONArray()
        load().forEach { skill ->
            arr.put(skillToJson(skill).put("format", "voiceconfig-skill").put("formatVersion", 1))
        }
        return arr.toString()
    }

    fun importSkill(json: String, source: String = "import"): AgentSkill? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val imported = skillFromJson(obj) ?: return null
        val now = System.currentTimeMillis()
        val generatedId = "skill_import_${now}_${imported.name.hashCode().toString().replace("-", "").take(6)}"
        val finalSkill = imported.copy(
            id = generatedId,
            status = AgentSkillStatus.PENDING,
            enabled = true,
            createdAt = now,
            updatedAt = now,
            sourceRunId = "",
            sourceVerified = null,
            version = 1,
            successCount = 1,
            useCount = 0,
            lastUsedAt = null,
            auditLog = listOf(
                AgentSkillAudit(
                    skillId = generatedId,
                    at = now,
                    action = "import",
                    detail = "从 $source 导入，等待审核",
                ),
            ),
        )
        val updated = load() + finalSkill
        save(updated)
        appendAudit(finalSkill.id, "import", "从 $source 导入技能：${finalSkill.name}")
        return finalSkill
    }

    private fun setStatus(id: String, status: AgentSkillStatus, action: String) {
        val now = System.currentTimeMillis()
        val updated = load().map { skill ->
            if (skill.id == id) {
                skill.copy(
                    status = status,
                    updatedAt = now,
                    auditLog = skill.auditLog + AgentSkillAudit(
                        skillId = id,
                        at = now,
                        action = action,
                        detail = when (status) {
                            AgentSkillStatus.APPROVED -> "用户通过技能"
                            AgentSkillStatus.REJECTED -> "用户拒绝技能"
                            else -> "状态变更"
                        },
                    ),
                )
            } else {
                skill
            }
        }
        if (updated.any { it.id == id }) {
            appendAudit(id, action, when (status) {
                AgentSkillStatus.APPROVED -> "用户通过技能"
                AgentSkillStatus.REJECTED -> "用户拒绝技能"
                else -> "状态变更"
            })
            save(updated)
        }
    }

    private fun upsert(candidate: AgentSkill) {
        val now = System.currentTimeMillis()
        val skills = load()
        // 防止同一 run 被重复沉淀。
        if (candidate.sourceRunId.isNotBlank() && skills.any { it.sourceRunId == candidate.sourceRunId }) {
            return
        }
        val existing = skills.firstOrNull { similarity(it.text, candidate.text) >= 0.5 }
        val updated = if (existing != null) {
            val stepsChanged = existing.steps != candidate.steps
            val version = if (stepsChanged) existing.version + 1 else existing.version
            skills.map { skill ->
                if (skill.id == existing.id) {
                    skill.copy(
                        text = candidate.text,
                        name = skill.name.ifBlank { candidate.name },
                        description = candidate.description,
                        whenToUse = candidate.whenToUse,
                        tags = (skill.tags + candidate.tags).distinct().take(8),
                        steps = candidate.steps,
                        successCount = skill.successCount + 1,
                        useCount = skill.useCount + 1,
                        updatedAt = now,
                        lastResult = "success",
                        lastRunId = candidate.lastRunId,
                        lastSessionId = candidate.lastSessionId,
                        sourceRunId = candidate.sourceRunId,
                        sourceVerified = candidate.sourceVerified,
                        requiredCapabilities = (skill.requiredCapabilities + candidate.requiredCapabilities).distinct().take(5),
                        version = version,
                        enabled = skill.enabled,
                        redacted = skill.redacted,
                        auditLog = skill.auditLog + AgentSkillAudit(
                            skillId = skill.id,
                            at = now,
                            action = "updated",
                            detail = "从 run ${candidate.sourceRunId} 刷新成功路径",
                        ),
                    )
                } else {
                    skill
                }
            }
        } else {
            skills + candidate.copy(
                auditLog = listOf(
                    AgentSkillAudit(
                        skillId = candidate.id,
                        at = now,
                        action = "created",
                        detail = "从 run ${candidate.sourceRunId} 自动生成",
                    ),
                ),
            )
        }
        save(updated)
    }

    private fun skillToJson(skill: AgentSkill): JSONObject {
        val stepsArr = JSONArray()
        skill.steps.forEach { step ->
            stepsArr.put(
                JSONObject()
                    .put("toolName", step.toolName)
                    .put("args", step.args)
                    .put("purpose", step.purpose)
                    .put("expected", step.expected)
                    .put("uiEvidence", step.uiEvidence)
                    .put("verification", step.verification)
                    .put("fallback", step.fallback)
                    .put("ok", step.ok),
            )
        }
        val tagsArr = JSONArray()
        skill.tags.forEach { tagsArr.put(it) }
        val requiredArr = JSONArray()
        skill.requiredCapabilities.forEach { requiredArr.put(it) }
        val auditArr = JSONArray()
        skill.auditLog.forEach { a ->
            auditArr.put(
                JSONObject()
                    .put("skillId", a.skillId)
                    .put("at", a.at)
                    .put("action", a.action)
                    .put("detail", a.detail),
            )
        }
        return JSONObject()
            .put("id", skill.id)
            .put("name", skill.name)
            .put("description", skill.description)
            .put("text", skill.text)
            .put("tags", tagsArr)
            .put("whenToUse", skill.whenToUse)
            .put("steps", stepsArr)
            .put("createdAt", skill.createdAt)
            .put("updatedAt", skill.updatedAt)
            .put("successCount", skill.successCount)
            .put("failCount", skill.failCount)
            .put("useCount", skill.useCount)
            .put("status", skill.status.name)
            .put("lastRunId", skill.lastRunId)
            .put("lastSessionId", skill.lastSessionId ?: 0L)
            .put("lastResult", skill.lastResult)
            .put("version", skill.version)
            .put("enabled", skill.enabled)
            .put("redacted", skill.redacted)
            .put("sourceRunId", skill.sourceRunId)
            .put("sourceVerified", skill.sourceVerified ?: JSONObject.NULL)
            .put("requiredCapabilities", requiredArr)
            .put("lastUsedAt", skill.lastUsedAt ?: 0L)
            .put("auditLog", auditArr)
    }

    private fun skillFromJson(obj: JSONObject): AgentSkill? {
        val stepsArr = obj.optJSONArray("steps") ?: return null
        val steps = buildList {
            for (i in 0 until stepsArr.length()) {
                val step = stepsArr.getJSONObject(i)
                add(
                    AgentSkillStep(
                        toolName = step.optString("toolName"),
                        args = step.optString("args"),
                        purpose = step.optString("purpose"),
                        expected = step.optString("expected"),
                        uiEvidence = step.optString("uiEvidence"),
                        verification = step.optString("verification"),
                        fallback = step.optString("fallback"),
                        ok = step.optBoolean("ok", true),
                    ),
                )
            }
        }
        if (steps.isEmpty()) return null
        val tags = runCatching {
            val arr = obj.optJSONArray("tags")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
        val required = runCatching {
            val arr = obj.optJSONArray("requiredCapabilities")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
        val audit = runCatching {
            val arr = obj.optJSONArray("auditLog")
            if (arr == null) emptyList() else (0 until arr.length()).map { k ->
                val a = arr.getJSONObject(k)
                AgentSkillAudit(
                    skillId = a.optString("skillId"),
                    at = a.optLong("at"),
                    action = a.optString("action"),
                    detail = a.optString("detail"),
                )
            }
        }.getOrDefault(emptyList())
        return AgentSkill(
            id = obj.optString("id", "skill_import"),
            name = obj.optString("name", obj.optString("text", "导入技能")),
            description = obj.optString("description"),
            text = obj.optString("text"),
            tags = tags,
            whenToUse = obj.optString("whenToUse"),
            steps = steps,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            successCount = obj.optInt("successCount", 1),
            failCount = obj.optInt("failCount", 0),
            useCount = obj.optInt("useCount", 0),
            status = runCatching {
                AgentSkillStatus.valueOf(obj.optString("status", AgentSkillStatus.PENDING.name))
            }.getOrDefault(AgentSkillStatus.PENDING),
            lastRunId = obj.optString("lastRunId"),
            lastSessionId = obj.optLong("lastSessionId").takeIf { it != 0L },
            lastResult = obj.optString("lastResult"),
            version = obj.optInt("version", 1),
            enabled = obj.optBoolean("enabled", true),
            redacted = obj.optBoolean("redacted", false),
            sourceRunId = obj.optString("sourceRunId"),
            sourceVerified = if (obj.has("sourceVerified") && !obj.isNull("sourceVerified")) {
                obj.optBoolean("sourceVerified")
            } else {
                null
            },
            requiredCapabilities = required,
            lastUsedAt = obj.optLong("lastUsedAt").takeIf { it != 0L },
            auditLog = audit,
        )
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
                                    purpose = step.optString("purpose"),
                                    expected = step.optString("expected"),
                                    uiEvidence = step.optString("uiEvidence"),
                                    verification = step.optString("verification"),
                                    fallback = step.optString("fallback"),
                                    ok = step.optBoolean("ok", true),
                                ),
                            )
                        }
                    }
                    val required = runCatching {
                        val arr = obj.optJSONArray("requiredCapabilities")
                        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
                    }.getOrDefault(emptyList())
                    val auditArr = obj.optJSONArray("auditLog")
                    val audit = runCatching {
                        if (auditArr == null) emptyList() else (0 until auditArr.length()).map { k ->
                            val a = auditArr.getJSONObject(k)
                            AgentSkillAudit(
                                skillId = a.optString("skillId"),
                                at = a.optLong("at"),
                                action = a.optString("action"),
                                detail = a.optString("detail"),
                            )
                        }
                    }.getOrDefault(emptyList())
                    add(
                        AgentSkill(
                            id = obj.optString("id"),
                            name = obj.optString("name", obj.optString("text", "技能")),
                            description = obj.optString("description"),
                            text = obj.optString("text"),
                            tags = runCatching {
                                val arr = obj.optJSONArray("tags")
                                if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
                            }.getOrDefault(emptyList()),
                            whenToUse = obj.optString("whenToUse"),
                            steps = steps,
                            createdAt = obj.optLong("createdAt"),
                            updatedAt = obj.optLong("updatedAt"),
                            successCount = obj.optInt("successCount", 1),
                            failCount = obj.optInt("failCount", 0),
                            useCount = obj.optInt("useCount", 0),
                            status = runCatching {
                                AgentSkillStatus.valueOf(obj.optString("status", AgentSkillStatus.APPROVED.name))
                            }.getOrDefault(AgentSkillStatus.APPROVED),
                            lastRunId = obj.optString("lastRunId"),
                            lastSessionId = obj.optLong("lastSessionId").takeIf { it != 0L },
                            lastResult = obj.optString("lastResult"),
                            version = obj.optInt("version", 1),
                            enabled = obj.optBoolean("enabled", true),
                            redacted = obj.optBoolean("redacted", false),
                            sourceRunId = obj.optString("sourceRunId"),
                            sourceVerified = if (obj.has("sourceVerified") && !obj.isNull("sourceVerified")) {
                                obj.optBoolean("sourceVerified")
                            } else {
                                null
                            },
                            requiredCapabilities = required,
                            lastUsedAt = obj.optLong("lastUsedAt").takeIf { it != 0L },
                            auditLog = audit,
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
                        .put("args", step.args)
                        .put("purpose", step.purpose)
                        .put("expected", step.expected)
                        .put("uiEvidence", step.uiEvidence)
                        .put("verification", step.verification)
                        .put("fallback", step.fallback)
                        .put("ok", step.ok),
                )
            }

            val tagsArr = JSONArray()
            skill.tags.forEach { tagsArr.put(it) }
            val requiredArr = JSONArray()
            skill.requiredCapabilities.forEach { requiredArr.put(it) }
            val auditArr = JSONArray()
            skill.auditLog.forEach { a ->
                auditArr.put(
                    JSONObject()
                        .put("skillId", a.skillId)
                        .put("at", a.at)
                        .put("action", a.action)
                        .put("detail", a.detail),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("name", skill.name)
                    .put("description", skill.description)
                    .put("text", skill.text)
                    .put("tags", tagsArr)
                    .put("whenToUse", skill.whenToUse)
                    .put("steps", stepsArr)
                    .put("createdAt", skill.createdAt)
                    .put("updatedAt", skill.updatedAt)
                    .put("successCount", skill.successCount)
                    .put("failCount", skill.failCount)
                    .put("useCount", skill.useCount)
                    .put("status", skill.status.name)
                    .put("lastRunId", skill.lastRunId)
                    .put("lastSessionId", skill.lastSessionId ?: 0L)
                    .put("lastResult", skill.lastResult)
                    .put("version", skill.version)
                    .put("enabled", skill.enabled)
                    .put("redacted", skill.redacted)
                    .put("sourceRunId", skill.sourceRunId)
                    .put("sourceVerified", skill.sourceVerified ?: JSONObject.NULL)
                    .put("requiredCapabilities", requiredArr)
                    .put("lastUsedAt", skill.lastUsedAt ?: 0L)
                    .put("auditLog", auditArr),
            )
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
        _skills.value = skills
    }

    private fun loadAudit(): List<AgentSkillAudit> {
        val raw = prefs.getString(KEY_AUDIT, null) ?: return emptyList()
        val result = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        AgentSkillAudit(
                            skillId = obj.optString("skillId"),
                            at = obj.optLong("at"),
                            action = obj.optString("action"),
                            detail = obj.optString("detail"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        _auditLogs.value = result
        return result
    }

    private fun saveAudit(logs: List<AgentSkillAudit>) {
        val arr = JSONArray()
        logs.forEach { a ->
            arr.put(
                JSONObject()
                    .put("skillId", a.skillId)
                    .put("at", a.at)
                    .put("action", a.action)
                    .put("detail", a.detail),
            )
        }
        prefs.edit().putString(KEY_AUDIT, arr.toString()).apply()
        _auditLogs.value = logs
    }

    private fun appendAudit(skillId: String, action: String, detail: String) {
        val list = (loadAudit() + AgentSkillAudit(skillId, System.currentTimeMillis(), action, detail)).takeLast(500)
        saveAudit(list)
    }

    private fun redactArgs(args: String): String {
        val sensitive = Regex("(?i)^(password|token|secret|card|phone|mobile|address|idCard|apiKey|auth|pin)$")
        return args.split(", ").joinToString(", ") { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val key = part.substring(0, eq).trim()
                if (sensitive.matches(key)) {
                    part.substring(0, eq + 1) + "***"
                } else {
                    part
                }
            } else {
                part
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeMap(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            value.keys().forEach { key -> map[key] = normalizeMap(value.opt(key)) }
            map
        }
        is JSONArray -> (0 until value.length()).map { normalizeMap(value.opt(it)) }
        is Map<*, *> -> value.entries.associate { it.key.toString() to normalizeMap(it.value) }
        is List<*> -> value.map { normalizeMap(it) }
        else -> value
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
        const val KEY_BUILTIN_SEEDED = "builtin_skills_seeded_v1"
        const val KEY_AUDIT = "skill_audit"
    }
}

data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val whenToUse: String = "",
    val steps: List<AgentSkillStep>,
    val createdAt: Long,
    val updatedAt: Long,
    val successCount: Int,
    val failCount: Int = 0,
    val useCount: Int = 0,
    val status: AgentSkillStatus = AgentSkillStatus.APPROVED,
    val lastRunId: String = "",
    val lastSessionId: Long? = null,
    val lastResult: String = "",
    val version: Int = 1,
    val enabled: Boolean = true,
    val redacted: Boolean = false,
    val sourceRunId: String = "",
    val sourceVerified: Boolean? = null,
    val requiredCapabilities: List<String> = emptyList(),
    val lastUsedAt: Long? = null,
    val auditLog: List<AgentSkillAudit> = emptyList(),
)

data class AgentSkillStep(
    val toolName: String,
    val args: String,
    val purpose: String = "",
    val expected: String = "",
    val uiEvidence: String = "",
    val verification: String = "",
    val fallback: String = "",
    val ok: Boolean = true,
)

data class AgentSkillAudit(
    val skillId: String,
    val at: Long,
    val action: String,
    val detail: String = "",
)
