package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

data class ExternalToolPack(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: String = "none",
    val authToken: String? = null,
    val tlsMode: String = "ca",
    val tlsPin: String? = null,
    val enabled: Boolean = false,
    val tools: List<ExternalToolSpec>,
)

data class ExternalToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val method: String,
    val path: String,
    val bodyTemplate: JSONObject?,
    val risk: String,
    val confirmation: String,
    val timeoutMs: Long,
    val async: AsyncSpec?,
)

data class AsyncSpec(
    val pollMethod: String = "GET",
    val pollPath: String,
    val taskIdField: String = "task_id",
    val statusField: String = "status",
    val successValues: Set<String> = setOf("completed", "succeeded", "done"),
    val failureValues: Set<String> = setOf("failed", "cancelled", "canceled"),
    val intervalMs: Long = 1_000L,
    val maxWaitMs: Long = 60_000L,
)

object ExternalToolPackParser {
    fun parse(raw: String): Result<ExternalToolPack> = runCatching {
        val root = JSONObject(raw)
        require(root.optString("schema_version") == "voiceconfig.toolpack/1") { "不支持的 schema_version" }
        val id = root.optString("id").trim()
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "pack id 非法" }
        val baseUrl = root.optString("base_url").trim().trimEnd('/')
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://localhost") || baseUrl.startsWith("http://127.0.0.1")) {
            "仅允许 HTTPS；localhost 调试可用明文 HTTP"
        }
        val auth = root.optJSONObject("auth")
        val tls = root.optJSONObject("tls")
        val toolsArr = root.optJSONArray("tools") ?: JSONArray()
        require(toolsArr.length() in 1..32) { "工具数量必须在 1..32" }
        val tools = (0 until toolsArr.length()).map { i ->
            val t = toolsArr.getJSONObject(i)
            val name = t.optString("name").trim()
            require(name.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) { "工具名非法：$name" }
            val request = t.optJSONObject("request") ?: JSONObject()
            val async = t.optJSONObject("async")?.let { a ->
                val poll = a.optJSONObject("poll") ?: JSONObject()
                AsyncSpec(
                    pollMethod = poll.optString("method", "GET"),
                    pollPath = poll.optString("path", "").ifBlank { error("async.poll.path 必填") },
                    taskIdField = a.optString("task_id_field", "task_id"),
                    statusField = a.optString("progress_field", "status"),
                    intervalMs = a.optLong("interval_ms", 1_000L).coerceIn(200L, 10_000L),
                    maxWaitMs = a.optLong("max_wait_ms", 60_000L).coerceIn(1_000L, 180_000L),
                )
            }
            ExternalToolSpec(
                name = name,
                description = t.optString("description", name).take(500),
                inputSchema = t.optJSONObject("input_schema") ?: JSONObject().apply {
                    put("type", "object"); put("properties", JSONObject()); put("additionalProperties", true)
                },
                method = request.optString("method", "POST").uppercase(),
                path = request.optString("path", "").ifBlank { error("request.path 必填") },
                bodyTemplate = request.optJSONObject("body_template"),
                risk = t.optString("risk", "write").lowercase(),
                confirmation = t.optString("confirmation", "local").lowercase(),
                timeoutMs = t.optLong("timeout_ms", 30_000L).coerceIn(1_000L, 180_000L),
                async = async,
            )
        }
        ExternalToolPack(
            id = id,
            name = root.optString("name", id),
            baseUrl = baseUrl,
            authType = auth?.optString("type", "none") ?: "none",
            authToken = auth?.optString("token")?.takeIf { it.isNotBlank() },
            tlsMode = tls?.optString("mode", "ca") ?: "ca",
            tlsPin = tls?.optString("sha256")?.takeIf { it.isNotBlank() },
            enabled = root.optBoolean("enabled", false),
            tools = tools,
        )
    }

    fun toStorageJson(pack: ExternalToolPack): JSONObject = JSONObject().apply {
        put("schema_version", "voiceconfig.toolpack/1")
        put("id", pack.id)
        put("name", pack.name)
        put("base_url", pack.baseUrl)
        put("auth", JSONObject().put("type", pack.authType))
        put("tls", JSONObject().apply {
            put("mode", pack.tlsMode)
            pack.tlsPin?.let { put("sha256", it) }
        })
        put("enabled", pack.enabled)
        put("tools", JSONArray().apply {
            pack.tools.forEach { spec ->
                put(JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("input_schema", spec.inputSchema)
                    put("request", JSONObject().apply {
                        put("method", spec.method); put("path", spec.path)
                        spec.bodyTemplate?.let { put("body_template", it) }
                    })
                    put("risk", spec.risk); put("confirmation", spec.confirmation)
                    put("timeout_ms", spec.timeoutMs)
                    spec.async?.let { a ->
                        put("async", JSONObject().apply {
                            put("poll", JSONObject().apply {
                                put("method", a.pollMethod); put("path", a.pollPath)
                            })
                            put("task_id_field", a.taskIdField)
                            put("progress_field", a.statusField)
                            put("interval_ms", a.intervalMs); put("max_wait_ms", a.maxWaitMs)
                        })
                    }
                })
            }
        })
    }
}

internal fun externalRiskToMetadata(name: String, description: String, risk: String, timeoutMs: Long): AgentToolMetadata {
    val r = risk.lowercase()
    return when (r) {
        "read", "read_only", "readonly" -> AgentToolMetadata(
            category = "外部工具", group = ToolGroup.REMOTE, risk = ToolRisk.READ_ONLY,
            effect = ToolEffect.READ, confirmationMode = ConfirmationMode.AUTO,
            executionTimeoutMs = timeoutMs,
        )
        "irreversible" -> AgentToolMetadata(
            category = "外部工具", group = ToolGroup.REMOTE, risk = ToolRisk.SENSITIVE,
            sensitive = true, autoConfirmable = false, effect = ToolEffect.DESTRUCTIVE,
            reversibility = ToolReversibility.IRREVERSIBLE, confirmationMode = ConfirmationMode.STRONG,
            executionTimeoutMs = timeoutMs,
        )
        else -> AgentToolMetadata(
            category = "外部工具", group = ToolGroup.REMOTE, risk = ToolRisk.HIGH,
            sensitive = true, autoConfirmable = false, effect = ToolEffect.COMMIT,
            reversibility = ToolReversibility.COMPENSATABLE, confirmationMode = ConfirmationMode.PER_ACTION,
            executionTimeoutMs = timeoutMs,
        )
    }
}
