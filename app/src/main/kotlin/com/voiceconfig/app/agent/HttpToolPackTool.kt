package com.voiceconfig.app.agent

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpToolPackTool(
    private val pack: ExternalToolPack,
    private val spec: ExternalToolSpec,
) : AgentTool {
    override val name: String = "ext_${pack.id}_${spec.name}".take(64)
    override val description: String = "[${pack.name}] ${spec.description}"
    override val inputSchema: JSONObject get() = spec.inputSchema
    override val metadata: AgentToolMetadata =
        externalRiskToMetadata(spec.name, spec.description, spec.risk, spec.timeoutMs)

    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val path = renderText(spec.path, args)
            val body = spec.bodyTemplate?.let { renderJson(it, args) }
            val first = request(spec.method, path, body)
            if (first.code !in 200..299) {
                return@withContext ToolResult.failed(
                    "外部工具 ${spec.name} HTTP ${first.code}: ${first.text.take(300)}",
                    code = "external_tool.http_${first.code}",
                    retryable = first.code == 0 || first.code == 429 || first.code >= 500,
                )
            }
            val root = runCatching { JSONObject(first.text) }.getOrNull()
            val payload = root ?: JSONObject().put("raw", first.text)
            if (payload.optBoolean("confirmation_required", false) || payload.optString("status") == "confirmation_required") {
                val c = payload.optJSONObject("confirmation") ?: payload
                return@withContext ToolResult.confirmationRequired(
                    message = c.optString("human_summary", "外部平台需要审批"),
                    confirmation = ToolConfirmation(
                        approvalId = c.optString("approval_id", ""),
                        actionDigest = c.optString("action_digest", ""),
                        humanSummary = c.optString("human_summary", "外部平台需要审批"),
                        expiresAt = c.optString("expires_at").takeIf { it.isNotBlank() },
                    ),
                    data = jsonMap(payload),
                )
            }
            val async = spec.async
            if (async != null) {
                val taskId = readPath(payload, async.taskIdField)
                if (taskId.isNotBlank()) {
                    return@withContext pollTask(taskId, async, payload)
                }
            }
            if (payload.optString("status") == "accepted") {
                return@withContext ToolResult.accepted(
                    payload.optString("summary", "任务已接受"),
                    payload.optString("task_id"),
                    jsonMap(payload),
                )
            }
            val ok = if (payload.has("ok")) payload.optBoolean("ok") else true
            if (!ok) {
                val err = payload.optJSONObject("error")
                return@withContext ToolResult.failed(
                    err?.optString("message") ?: payload.optString("message", "外部工具执行失败"),
                    code = err?.optString("code") ?: "external_tool.failed",
                    retryable = err?.optBoolean("retryable", false) ?: false,
                    data = jsonMap(payload),
                )
            }
            ToolResult.success(
                payload.optString("summary").ifBlank { "${pack.name}/${spec.name} 执行成功" },
                jsonMap(payload),
            )
        } catch (e: Exception) {
            ToolResult.failed(e.message ?: "外部工具执行失败", code = "external_tool.exception", retryable = true)
        }
    }

    private suspend fun pollTask(taskId: String, async: AsyncSpec, initial: JSONObject): ToolResult {
        val start = System.currentTimeMillis()
        var latest = initial
        while (System.currentTimeMillis() - start < async.maxWaitMs) {
            delay(async.intervalMs)
            val path = async.pollPath.replace("{task_id}", taskId).replace("${'$'}{task_id}", taskId)
            val response = request(async.pollMethod, path, null)
            if (response.code !in 200..299) continue
            val obj = runCatching { JSONObject(response.text) }.getOrNull() ?: continue
            latest = obj
            val status = readPath(obj, async.statusField).lowercase()
            if (status in async.successValues) {
                return ToolResult.success(obj.optString("summary").ifBlank { "任务 $taskId 已完成" }, jsonMap(obj))
            }
            if (status in async.failureValues) {
                return ToolResult.failed(obj.optString("message", "任务 $taskId 失败"), code = "external_tool.task_failed", data = jsonMap(obj))
            }
        }
        return ToolResult.accepted("任务已提交，正在处理中", taskId, jsonMap(latest))
    }

    private data class HttpResult(val code: Int, val text: String)

    private fun request(method: String, path: String, body: JSONObject?): HttpResult {
        val url = URL(if (path.startsWith("http")) path else pack.baseUrl.trimEnd('/') + '/' + path.trimStart('/'))
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection && pack.tlsMode == "pin" && !pack.tlsPin.isNullOrBlank()) {
                pinConnection(conn, pack.tlsPin)
            }
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = spec.timeoutMs.toInt()
            conn.setRequestProperty("Accept", "application/json")
            if (pack.authType == "bearer" && !pack.authToken.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${pack.authToken}")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            return HttpResult(0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun pinConnection(conn: HttpsURLConnection, pin: String) {
        val expected = pin.removePrefix("sha256:").replace(":", "").lowercase()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: error("no certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                require(actual == expected) { "TLS pin 不匹配" }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), SecureRandom())
        }
        conn.sslSocketFactory = context.socketFactory
    }

    private fun renderJson(value: Any?, args: Map<String, Any?>): JSONObject =
        when (value) {
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key -> put(key, renderAny(value.opt(key), args)) }
            }
            else -> JSONObject()
        }

    private fun renderAny(value: Any?, args: Map<String, Any?>): Any? = when (value) {
        is JSONObject -> renderJson(value, args)
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(renderAny(value.opt(i), args)) }
        is String -> renderText(value, args)
        else -> value
    }

    private fun renderText(text: String, args: Map<String, Any?>): String {
        if (text == "${'$'}{args}") return JSONObject(args as Map<*, *>).toString()
        var result = text
        Regex("""\$\{args\.([a-zA-Z0-9_]+)\}""").findAll(text).forEach { m ->
            val key = m.groupValues[1]
            result = result.replace(m.value, args[key]?.toString().orEmpty())
        }
        Regex("""\{\{args\.([a-zA-Z0-9_]+)\}\}""").findAll(text).forEach { m ->
            val key = m.groupValues[1]
            result = result.replace(m.value, args[key]?.toString().orEmpty())
        }
        return result
    }

    private fun readPath(obj: JSONObject, path: String): String =
        path.split('.').fold(obj as Any?) { acc, key ->
            when (acc) {
                is JSONObject -> acc.opt(key)
                else -> null
            }
        }?.toString().orEmpty()

    private fun jsonMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { key -> obj.opt(key) }
}
