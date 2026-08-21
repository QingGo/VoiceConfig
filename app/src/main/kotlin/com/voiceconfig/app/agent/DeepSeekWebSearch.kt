package com.voiceconfig.app.agent

import com.voiceconfig.app.ai.ApiKeyStore
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek Harness 风格 web_search 工具。
 *
 * 复用 DeepSeek API Key，通过 Anthropic 兼容接口请求内置 web_search 工具。
 * 返回搜索结果文本（含引用摘要）。
 */
@Singleton
class DeepSeekWebSearch @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
) {

    @Volatile
    var lastError: String? = null
        private set

    suspend fun search(query: String, maxUses: Int = 3): WebSearchResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastError = "未配置 DeepSeek API Key"
            return@withContext WebSearchResult(ok = false, error = lastError, text = "")
        }
        try {
            val url = URL("https://api.deepseek.com/anthropic/v1/messages")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 45_000

                val body = JSONObject().apply {
                    put("model", apiKeyStore.deepSeekModel)
                    put("max_tokens", 4096)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "Perform a web search for the query: $query")
                                })
                            })
                        })
                    })
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", maxUses)
                        })
                    })
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    lastError = "HTTP $code ${errorBody?.take(300) ?: ""}"
                    return@withContext WebSearchResult(ok = false, error = lastError, text = "")
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val text = parseResponse(response)
                WebSearchResult(ok = text.isNotBlank(), text = text, error = if (text.isBlank()) "搜索无结果" else null)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            WebSearchResult(ok = false, error = lastError, text = "")
        }
    }

    private fun parseResponse(json: String): String {
        val root = JSONObject(json)
        val content = root.optJSONArray("content") ?: return root.optString("content", "")
        val parts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            val type = block.optString("type")
            when (type) {
                "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                "web_search_tool_result" -> {
                    val citations = block.optJSONArray("citations")
                    if (citations != null) {
                        for (j in 0 until citations.length()) {
                            val citation = citations.optJSONObject(j) ?: continue
                            val title = citation.optString("title").ifBlank { "引用" }
                            val url = citation.optString("url")
                            val contentText = citation.optString("content").ifBlank { citation.optString("snippet") }
                            parts.add("[$title]($url) ${contentText.take(600)}".trim())
                        }
                    }
                    block.optString("content").takeIf { it.isNotBlank() }?.let(parts::add)
                }
                else -> block.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        return parts.joinToString("\n\n")
    }
}

data class WebSearchResult(
    val ok: Boolean,
    val text: String,
    val error: String? = null,
)
