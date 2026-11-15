package com.voiceconfig.app.ai

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class DeepSeekTtsStreamSession internal constructor(
    private val webSocket: WebSocket,
) {
    fun close() {
        runCatching { webSocket.close(1000, "stopped") }
    }
}

@Singleton
class DeepSeekTtsStreamClient @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun start(
        text: String,
        voiceId: String,
        onAudio: (ByteArray) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ): DeepSeekTtsStreamSession? {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            onError("未配置 DeepSeek API Key")
            return null
        }
        if (text.isBlank()) {
            onError("待播报文本为空")
            return null
        }
        if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            onError("TTS 请求体超过 64 KiB 限制")
            return null
        }
        val url = "https://api.deepseek.com/v1/tools/tts"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("voice_id", voiceId.ifBlank { "stella" })
            ?.addQueryParameter("format", "pcm")
            ?.addQueryParameter("filter_markdown", "true")
            ?.build()
            ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        val finished = AtomicBoolean(false)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val chunks = splitText(text)
                chunks.forEach { chunk ->
                    webSocket.send(JSONObject().apply {
                        put("event", "text")
                        put("text", chunk)
                    }.toString())
                }
                webSocket.send(JSONObject().put("event", "finish_text").toString())
            }

            override fun onMessage(webSocket: WebSocket, textMessage: String) {
                val obj = runCatching { JSONObject(textMessage) }.getOrNull() ?: return
                if (obj.optString("event") == "finish") {
                    val code = obj.optString("code")
                    if (code == "success") {
                        if (finished.compareAndSet(false, true)) onComplete()
                    } else {
                        if (finished.compareAndSet(false, true)) {
                            onError(obj.optString("message", "TTS 流式合成失败：$code"))
                        }
                        webSocket.close(1000, "error")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (finished.get()) return
                onAudio(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished.compareAndSet(false, true)) {
                    onError(t.message ?: "TTS WebSocket 失败")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.get() && code != 1000) {
                    if (finished.compareAndSet(false, true)) onError("TTS WebSocket 已关闭：$code $reason")
                }
            }
        })
        return DeepSeekTtsStreamSession(socket)
    }

    private fun splitText(text: String, maxChars: Int = 2_000): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val end = (index + maxChars).coerceAtMost(text.length)
            result += text.substring(index, end)
            index = end
        }
        return result
    }
}
