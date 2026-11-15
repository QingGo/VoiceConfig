package com.voiceconfig.app.agent

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnlyHint: Boolean,
    val destructiveHint: Boolean,
)

data class McpServerConfig(
    val url: String,
    val token: String,
    val enabled: Boolean = false,
    val allowedTools: Set<String> = emptySet(),
    val tlsPin: String? = null,
)

class McpHttpClient(private val config: McpServerConfig) {
    private val ids = AtomicLong(1)
    private val initLock = Mutex()
    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null

    suspend fun listTools(): Result<List<McpToolDef>> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val result = rpc("tools/list", JSONObject())
                ?: return@withContext Result.failure(IllegalStateException("MCP tools/list 无结果"))
            val arr = result.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val annotations = t.optJSONObject("annotations") ?: JSONObject()
                McpToolDef(
                    name = name,
                    description = t.optString("description", name).take(500),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().apply {
                        put("type", "object"); put("properties", JSONObject()); put("additionalProperties", true)
                    },
                    readOnlyHint = annotations.optBoolean("readOnlyHint", false),
                    destructiveHint = annotations.optBoolean("destructiveHint", false),
                )
            }
            Result.success(tools)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun callTool(name: String, args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val result = rpc("tools/call", JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject(args as Map<*, *>))
            }) ?: return@withContext ToolResult.failed("MCP 工具无结果", code = "mcp.empty_result")
            if (result.optBoolean("isError", false)) {
                return@withContext ToolResult.failed(extractText(result), code = "mcp.tool_error")
            }
            val text = extractText(result)
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed != null) {
                if (parsed.optBoolean("confirmation_required", false) || parsed.optString("status") == "confirmation_required") {
                    val c = parsed.optJSONObject("confirmation") ?: parsed
                    return@withContext ToolResult.confirmationRequired(
                        message = c.optString("human_summary", "FAN 需要审批"),
                        confirmation = ToolConfirmation(
                            approvalId = c.optString("approval_id"),
                            actionDigest = c.optString("action_digest"),
                            humanSummary = c.optString("human_summary", "FAN 需要审批"),
                            expiresAt = c.optString("expires_at").takeIf { it.isNotBlank() },
                            allowedMethods = c.optJSONArray("allowed_methods")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }
                            }.orEmpty(),
                        ),
                        data = jsonMap(parsed),
                    )
                }
                if (parsed.has("status") && parsed.optString("status") == "accepted") {
                    return@withContext ToolResult.accepted(
                        parsed.optString("summary", "任务已接受"),
                        parsed.optString("task_id"),
                        jsonMap(parsed),
                    )
                }
                val ok = if (parsed.has("ok")) parsed.optBoolean("ok") else true
                if (!ok) {
                    val err = parsed.optJSONObject("error")
                    return@withContext ToolResult.failed(
                        err?.optString("message") ?: parsed.optString("message", "MCP 工具执行失败"),
                        code = err?.optString("code") ?: "mcp.tool_failed",
                        retryable = err?.optBoolean("retryable", false) ?: false,
                        data = jsonMap(parsed),
                    )
                }
                ToolResult.success(parsed.optString("summary", text.take(500)), jsonMap(parsed))
            } else {
                ToolResult.success(text.ifBlank { "MCP 工具执行成功" })
            }
        } catch (e: Exception) {
            ToolResult.failed(e.message ?: "MCP 调用失败", code = "mcp.exception", retryable = true)
        }
    }

    private suspend fun ensureInitialized() = initLock.withLock {
        if (initialized) return@withLock
        val result = rpc("initialize", JSONObject().apply {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "VoiceConfig")
                put("version", com.voiceconfig.app.BuildConfig.VERSION_NAME)
            })
        }, allowUninitialized = true) ?: error("MCP initialize 无结果")
        initialized = true
        // initialized notification：服务端可能返回 202/空 body。
        runCatching { rpc("notifications/initialized", JSONObject(), notification = true) }
    }

    private fun rpc(
        method: String,
        params: JSONObject,
        notification: Boolean = false,
        allowUninitialized: Boolean = false,
    ): JSONObject? {
        if (!allowUninitialized && !initialized) error("MCP 未初始化")
        val id = if (notification) null else ids.getAndIncrement()
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("method", method)
            if (params.length() > 0) put("params", params)
        }
        val conn = (URL(config.url).openConnection() as HttpURLConnection)
        try {
            if (conn is HttpsURLConnection && !config.tlsPin.isNullOrBlank()) applyPin(conn, config.tlsPin)
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            if (config.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${config.token}")
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            sessionId = conn.getHeaderField("Mcp-Session-Id") ?: sessionId
            val code = conn.responseCode
            if (notification && code in 200..299) return null
            val contentType = conn.contentType.orEmpty()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("MCP HTTP $code: ${text.take(300)}")
            val payload = if (contentType.contains("text/event-stream")) parseSse(text, id) else text
            val obj = JSONObject(payload)
            obj.optJSONObject("error")?.let { error("MCP ${it.optInt("code")}: ${it.optString("message")}") }
            return obj.optJSONObject("result")
        } finally {
            conn.disconnect()
        }
    }

    private fun applyPin(conn: HttpsURLConnection, pin: String) {
        val expected = pin.removePrefix("sha256:").replace(":", "").lowercase()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: error("no certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                require(actual == expected) { "MCP TLS pin 不匹配" }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        conn.sslSocketFactory = context.socketFactory
    }
    private fun parseSse(text: String, expectedId: Long?): String {
        val dataLines = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            if (line.startsWith("data:")) dataLines += line.removePrefix("data:").trim()
        }
        val candidates = dataLines.ifEmpty { listOf(text) }
        for (candidate in candidates) {
            val obj = runCatching { JSONObject(candidate) }.getOrNull() ?: continue
            if (expectedId == null || !obj.has("id") || obj.optLong("id") == expectedId) return candidate
        }
        error("MCP SSE 响应未找到匹配 id")
    }

    private fun extractText(result: JSONObject): String {
        result.optJSONArray("content")?.let { arr ->
            val parts = (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                if (item.optString("type") == "text") item.optString("text") else null
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        }
        result.optJSONObject("structuredContent")?.let { return it.toString() }
        return result.toString()
    }

    private fun jsonMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { key -> obj.opt(key) }
}
