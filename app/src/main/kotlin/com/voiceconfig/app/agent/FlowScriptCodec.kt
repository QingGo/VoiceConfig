package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

object FlowScriptCodec {

    const val FORMAT = "voiceconfig-flow-script"
    const val CURRENT_SCHEMA_VERSION = 1

    fun toJson(script: FlowScript): JSONObject {
        val steps = JSONArray()
        script.steps.forEach { step ->
            steps.put(
                JSONObject()
                    .put("id", step.id)
                    .put("name", step.name)
                    .put("label", step.label)
                    .put("whenContains", stringsToArray(step.whenContains))
                    .put("whenNotContains", stringsToArray(step.whenNotContains))
                    .put("once", step.once)
                    .put("action", actionToJson(step.action)),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", script.schemaVersion)
            .put("version", script.version)
            .put("id", script.id)
            .put("name", script.name)
            .put("description", script.description)
            .put("openPackage", script.openPackage ?: JSONObject.NULL)
            .put("status", script.status.name)
            .put("enabled", script.enabled)
            .put("source", script.source)
            .put("terminalMarkers", stringsToArray(script.terminalMarkers))
            .put("maxIterations", script.maxIterations)
            .put("forbiddenActionTokens", stringsToArray(script.forbiddenActionTokens))
            .put("steps", steps)
            .put("createdAtMs", script.createdAtMs)
            .put("updatedAtMs", script.updatedAtMs)
    }

    fun toJsonString(script: FlowScript): String = toJson(script).toString()

    fun fromJson(obj: JSONObject): FlowScript? {
        if (obj.optString("format") != FORMAT) return null
        val id = obj.optString("id").ifBlank { return null }
        val name = obj.optString("name").ifBlank { return null }
        val stepsArray = obj.optJSONArray("steps") ?: return null
        val steps = mutableListOf<FlowStep>()
        for (i in 0 until stepsArray.length()) {
            val stepObj = stepsArray.optJSONObject(i) ?: return null
            val actionObj = stepObj.optJSONObject("action") ?: return null
            val action = actionFromJson(actionObj) ?: return null
            val stepId = stepObj.optString("id").ifBlank { return null }
            steps += FlowStep(
                id = stepId,
                name = stepObj.optString("name"),
                label = stepObj.optString("label"),
                whenContains = optStringList(stepObj, "whenContains"),
                whenNotContains = optStringList(stepObj, "whenNotContains"),
                action = action,
                once = stepObj.optBoolean("once", true),
            )
        }
        if (steps.isEmpty()) return null
        val status = runCatching {
            FlowScriptStatus.valueOf(obj.optString("status", FlowScriptStatus.APPROVED.name))
        }.getOrDefault(FlowScriptStatus.APPROVED)
        return FlowScript(
            id = id,
            name = name,
            description = obj.optString("description"),
            openPackage = obj.optString("openPackage").takeIf { it.isNotBlank() },
            steps = steps,
            terminalMarkers = optStringList(obj, "terminalMarkers").ifEmpty {
                listOf("免密支付", "确认订单")
            },
            maxIterations = obj.optInt("maxIterations", 20).coerceAtLeast(1),
            version = obj.optInt("version", 1).coerceAtLeast(1),
            schemaVersion = obj.optInt("schemaVersion", CURRENT_SCHEMA_VERSION),
            status = status,
            enabled = obj.optBoolean("enabled", true),
            source = obj.optString("source", "import"),
            forbiddenActionTokens = optStringList(obj, "forbiddenActionTokens"),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
        )
    }

    fun parse(json: String): FlowScript? =
        runCatching { JSONObject(json) }.getOrNull()?.let { fromJson(it) }

    fun validate(script: FlowScript): List<String> {
        val errors = mutableListOf<String>()
        if (script.id.isBlank()) errors += "id 不能为空"
        if (script.name.isBlank()) errors += "name 不能为空"
        if (script.steps.isEmpty()) errors += "steps 不能为空"
        if (script.maxIterations <= 0) errors += "maxIterations 必须大于 0"
        val duplicateIds = script.steps
            .map { it.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            errors += "存在重复 step id：${duplicateIds.joinToString()}"
        }
        script.steps.forEachIndexed { index, step ->
            val where = "第 ${index + 1} 步"
            if (step.id.isBlank()) errors += "$where id 不能为空"
            if (step.whenContains.isEmpty() && step.whenNotContains.isEmpty()) {
                errors += "$where 至少要有一个 whenContains 或 whenNotContains 条件"
            }
            when (step.action) {
                is FlowAction.TapText -> if (step.action.candidates.isEmpty()) {
                    errors += "$where tap_text 候选项不能为空"
                }
                is FlowAction.TapId -> if (step.action.resourceIds.isEmpty()) {
                    errors += "$where tap_id 资源 id 不能为空"
                }
                is FlowAction.TapTextOrBack -> if (step.action.candidates.isEmpty()) {
                    errors += "$where tap_text_or_back 候选项不能为空"
                }
                FlowAction.Back, FlowAction.DismissPopups -> Unit
            }
        }
        return errors
    }

    fun isValid(script: FlowScript): Boolean = validate(script).isEmpty()

    fun actionToJson(action: FlowAction): JSONObject = when (action) {
        is FlowAction.TapText -> JSONObject()
            .put("type", "tap_text")
            .put("candidates", stringsToArray(action.candidates))
        is FlowAction.TapId -> JSONObject()
            .put("type", "tap_id")
            .put("resourceIds", stringsToArray(action.resourceIds))
        FlowAction.Back -> JSONObject().put("type", "back")
        FlowAction.DismissPopups -> JSONObject().put("type", "dismiss_popups")
        is FlowAction.TapTextOrBack -> JSONObject()
            .put("type", "tap_text_or_back")
            .put("candidates", stringsToArray(action.candidates))
    }

    fun actionFromJson(obj: JSONObject): FlowAction? = when (obj.optString("type")) {
        "tap_text" -> FlowAction.TapText(optStringList(obj, "candidates"))
        "tap_id" -> FlowAction.TapId(optStringList(obj, "resourceIds"))
        "back" -> FlowAction.Back
        "dismiss_popups" -> FlowAction.DismissPopups
        "tap_text_or_back" -> FlowAction.TapTextOrBack(optStringList(obj, "candidates"))
        else -> null
    }

    private fun stringsToArray(values: List<String>): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        }
    }
}
