package com.voiceconfig.app.agent

import android.util.Log
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 供 Agent 会话使用的 DeepSeek Chat Completions 客户端。
 *
 * 同时支持：
 * - 原生 function calling（tools / tool_calls）
 * - 旧版 JSON 数组（保留兼容）
 * - SSE 流式（后续加入）
 */
interface AgentChat {
    suspend fun complete(systemPrompt: String, messages: List<AgentMessage>): String?
}

interface AgentToolChat {
    val lastError: String?

    suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
    ): AgentChatResponse?

    suspend fun streamWithTools(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        onEvent: (AgentStreamEvent) -> Unit,
    ): AgentChatResponse?
}

@Singleton
open class AgentChatClient @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
    private val aiDebugLogRepository: AiDebugLogRepository,
) : AgentChat, AgentToolChat, AgentModelBackend {
    override val modelId: String get() = apiKeyStore.deepSeekModel
    override val supportsVision: Boolean get() = modelId.contains("vision", ignoreCase = true)


    @Volatile
    override var lastError: String? = null

    companion object {
        private const val TAG = "AgentChatClient"
    }

    private suspend fun recordError(
        messages: List<AgentMessage>,
        rawResponse: String?,
        error: String,
    ) {
        runCatching {
            val input = messages.lastOrNull { it.role == "user" }?.content?.take(200) ?: "Agent"
            aiDebugLogRepository.add(
                AiDebugLogEntity(
                    createdAtEpochMillis = System.currentTimeMillis(),
                    input = input,
                    model = apiKeyStore.deepSeekModel,
                    thinkingEnabled = apiKeyStore.agentDeepSeekThinkingEnabled,
                    reasoningEffort = apiKeyStore.agentDeepSeekReasoningEffort,
                    rawResponse = rawResponse,
                    parseError = error,
                ),
            )
            aiDebugLogRepository.trim(200)
        }
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastError = "未配置 DeepSeek API Key"
            recordError(messages, null, lastError ?: "未配置 DeepSeek API Key")
            return@withContext null
        }
        try {
            val url = URL("https://api.deepseek.com/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 45_000

                val thinkingEnabled = apiKeyStore.agentDeepSeekThinkingEnabled
                val body = JSONObject().apply {
                    put("model", apiKeyStore.deepSeekModel)
                    put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
                    if (thinkingEnabled) {
                        put("reasoning_effort", apiKeyStore.agentDeepSeekReasoningEffort)
                    } else {
                        put("temperature", 0)
                    }
                    put("response_format", JSONObject().put("type", "json_object"))
                    put("messages", buildMessages(systemPrompt, messages))
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    lastError = "HTTP $code ${errorBody?.take(300) ?: ""}"
                    recordError(messages, errorBody, lastError ?: "HTTP $code")
                    return@withContext null
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(response)
                root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .takeIf { it.isNotBlank() && it != "null" }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            recordError(messages, null, lastError ?: e.javaClass.simpleName)
            null
        }
    }

    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
    ): AgentChatResponse? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastError = "未配置 DeepSeek API Key"
            recordError(messages, null, lastError ?: "未配置 DeepSeek API Key")
            return@withContext null
        }
        try {
            val url = URL("https://api.deepseek.com/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000

                val thinkingEnabled = apiKeyStore.agentDeepSeekThinkingEnabled
                val body = JSONObject().apply {
                    put("model", apiKeyStore.deepSeekModel)
                    put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
                    if (thinkingEnabled) {
                        put("reasoning_effort", apiKeyStore.agentDeepSeekReasoningEffort)
                    } else {
                        put("temperature", 0)
                    }
                    put("messages", buildMessages(systemPrompt, messages))
                    put("tools", AgentToolSchemas.build(tools))
                    put("tool_choice", "auto")
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    lastError = "HTTP $code ${errorBody?.take(300) ?: ""}"
                    recordError(messages, errorBody, lastError ?: "HTTP $code")
                    return@withContext null
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                parseChatResponse(response)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            recordError(messages, null, lastError ?: e.javaClass.simpleName)
            null
        }
    }

    override suspend fun streamWithTools(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        onEvent: (AgentStreamEvent) -> Unit,
    ): AgentChatResponse? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastError = "未配置 DeepSeek API Key"
            recordError(messages, null, lastError ?: "未配置 DeepSeek API Key")
            return@withContext null
        }
        try {
            val requestStartMs = System.currentTimeMillis()
            val url = URL("https://api.deepseek.com/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000

                val thinkingEnabled = apiKeyStore.agentDeepSeekThinkingEnabled
                val body = JSONObject().apply {
                    put("model", apiKeyStore.deepSeekModel)
                    put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
                    if (thinkingEnabled) {
                        put("reasoning_effort", apiKeyStore.agentDeepSeekReasoningEffort)
                    } else {
                        put("temperature", 0)
                    }
                    put("messages", buildMessages(systemPrompt, messages))
                    put("tools", AgentToolSchemas.build(tools))
                    put("tool_choice", "auto")
                    put("stream", true)
                    put("stream_options", JSONObject().put("include_usage", true))
                }
                val requestBytes = body.toString().toByteArray(Charsets.UTF_8).size

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    lastError = "HTTP $code ${errorBody?.take(300) ?: ""}"
                    recordError(messages, errorBody, lastError ?: "HTTP $code")
                    return@withContext null
                }

                val streamStartMs = System.currentTimeMillis()
                var usageJson: JSONObject? = null
                var firstTokenAtMs: Long? = null
                var reasoningStartedAt: Long? = null
                var contentStartedAt: Long? = null
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val toolCalls = LinkedHashMap<Int, MutableAgentToolCall>()
                var finishReason: String? = null

                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        Log.d(TAG, "SSE: $data")
                        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        chunk.optJSONObject("usage")?.let { usageJson = it }
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let {
                            finishReason = it
                        }
                        val delta = choice.optJSONObject("delta") ?: continue
                        delta.optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }?.let {
                            if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis()
                            if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
                            reasoning.append(it)
                            onEvent(AgentStreamEvent.Reasoning(it))
                        }
                        delta.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let {
                            if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis()
                            if (contentStartedAt == null) contentStartedAt = System.currentTimeMillis()
                            content.append(it)
                            onEvent(AgentStreamEvent.Content(it))
                        }
                        delta.optJSONArray("tool_calls")?.let { arr ->
                            if (firstTokenAtMs == null && arr.length() > 0) firstTokenAtMs = System.currentTimeMillis()
                            for (i in 0 until arr.length()) {
                                val tc = arr.optJSONObject(i) ?: continue
                                val index = tc.optInt("index", i)
                                val call = toolCalls.getOrPut(index) { MutableAgentToolCall() }
                                tc.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { call.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { call.name = it }
                                    fn.optString("arguments").takeIf { it.isNotBlank() && it != "null" }?.let {
                                        call.arguments.append(it)
                                        onEvent(AgentStreamEvent.ToolCallDelta(index, call.id, call.name, it))
                                    }
                                }
                            }
                        }
                    }
                }

                val nowMs = System.currentTimeMillis()
                val thinkingMs = when {
                    reasoningStartedAt != null && contentStartedAt != null -> contentStartedAt - streamStartMs
                    reasoningStartedAt != null -> nowMs - streamStartMs
                    else -> 0
                }
                val outputMs = (nowMs - streamStartMs - thinkingMs).coerceAtLeast(0)
                val ttftMs = (firstTokenAtMs?.minus(requestStartMs) ?: nowMs - requestStartMs).coerceAtLeast(0)
                val usage = usageJson ?: JSONObject()
                val response = AgentChatResponse(
                    content = content.toString().takeIf { it.isNotBlank() },
                    reasoningContent = reasoning.toString().takeIf { it.isNotBlank() },
                    toolCalls = toolCalls.values.map { AgentToolCall(it.id, it.name, it.arguments.toString()) },
                    finishReason = finishReason,
                    thinkingMs = thinkingMs,
                    outputMs = outputMs,
                    ttftMs = ttftMs,
                    requestBytes = requestBytes,
                    promptCacheHitTokens = usage.optLong("prompt_cache_hit_tokens", 0L),
                    promptCacheMissTokens = usage.optLong("prompt_cache_miss_tokens", 0L),
                    promptTokens = usage.optLong("prompt_tokens", 0L),
                    completionTokens = usage.optLong("completion_tokens", 0L),
                    totalTokens = usage.optLong("total_tokens", 0L),
                )
                onEvent(AgentStreamEvent.Done(response))
                response
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            recordError(messages, null, lastError ?: e.javaClass.simpleName)
            null
        }
    }

    private fun buildMessages(systemPrompt: String, messages: List<AgentMessage>): JSONArray =
        JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { msg ->
                val obj = JSONObject().apply {
                    put("role", msg.role)
                    if (msg.role == "user" && !msg.imageBase64.isNullOrBlank()) {
                        put("content", JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", msg.content.ifBlank { "当前屏幕截图" }))
                            put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject().apply {
                                            put("url", "data:image/png;base64,${msg.imageBase64}")
                                            if (apiKeyStore.agentImageDetailLow) {
                                                put("detail", "low")
                                            }
                                        },
                                    ),
                            )
                        })
                    } else {
                        put("content", msg.content)
                    }
                    msg.toolCallId?.let { put("tool_call_id", it) }
                }
                if (msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank()) {
                    obj.put("reasoning_content", msg.reasoningContent)
                }
                if (msg.role == "assistant" && !msg.toolCallsJson.isNullOrBlank()) {
                    val calls = runCatching { JSONArray(msg.toolCallsJson) }.getOrNull()
                    if (calls != null) {
                        obj.put("tool_calls", calls)
                    }
                }
                put(obj)
            }
        }

    private fun parseChatResponse(json: String): AgentChatResponse? {
        val root = JSONObject(json)
        val message = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
        val finishReason = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optString("finish_reason")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val content = message.optString("content").takeIf { it.isNotBlank() && it != "null" }
        val reasoning = message.optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }
        val toolCalls = mutableListOf<AgentToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val call = arr.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                toolCalls += AgentToolCall(
                    id = call.optString("id").takeIf { it != "null" } ?: "",
                    name = fn.optString("name").takeIf { it != "null" } ?: "",
                    arguments = fn.optString("arguments").takeIf { it != "null" } ?: "",
                )
            }
        }
        return AgentChatResponse(
            content = content,
            reasoningContent = reasoning,
            toolCalls = toolCalls,
            finishReason = finishReason,
        )
    }
}

data class AgentMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResultOk: Boolean? = null,
    val toolCallsJson: String? = null,
    val reasoningContent: String? = null,
    val imageBase64: String? = null,
    val durationMs: Long = 0,
    val thinkingMs: Long = 0,
    val outputMs: Long = 0,
    val ttftMs: Long = 0,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class AgentChatResponse(
    val content: String?,
    val reasoningContent: String?,
    val toolCalls: List<AgentToolCall>,
    val finishReason: String? = null,
    val thinkingMs: Long = 0,
    val outputMs: Long = 0,
    val ttftMs: Long = 0,
    val requestBytes: Int = 0,
    val promptCacheHitTokens: Long = 0,
    val promptCacheMissTokens: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
)


data class MutableAgentToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)

sealed class AgentStreamEvent {
    data class Reasoning(val text: String) : AgentStreamEvent()
    data class Content(val text: String) : AgentStreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : AgentStreamEvent()
    data class Done(val response: AgentChatResponse?) : AgentStreamEvent()
}
