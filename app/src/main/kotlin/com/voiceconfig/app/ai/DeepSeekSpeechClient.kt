package com.voiceconfig.app.ai

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DeepSeekSpeechError(
    val code: Int,
    val message: String,
    val retryable: Boolean = code == 0 || code == 429 || code >= 500,
)

@Singleton
class DeepSeekSpeechClient @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
) {
    suspend fun transcribe(wavFile: File): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyStore.deepSeekApiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 DeepSeek API Key"))
        if (!wavFile.exists() || wavFile.length() <= 0) {
            return@withContext Result.failure(IllegalStateException("录音文件为空"))
        }
        if (wavFile.length() > 8L * 1024L * 1024L) {
            return@withContext Result.failure(IllegalStateException("录音文件超过 DeepSeek ASR 8 MiB 限制"))
        }
        val boundary = "VoiceConfig-" + UUID.randomUUID()
        val conn = (URL(API_BASE + "/v1/tools/asr").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                writeFile(out, boundary, "file", "recording.wav", "audio/wav", wavFile.readBytes())
                out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("DeepSeek ASR HTTP $code ${body.take(300)}"))
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                return@withContext Result.failure(IllegalStateException("ASR 响应不是 JSON：${body.take(200)}"))
            }
            val text = json.optString("text")
                .ifBlank { json.optJSONArray("segments")?.optJSONObject(0)?.optString("text").orEmpty() }
            if (text.isBlank()) Result.failure(IllegalStateException("ASR 未返回识别文本"))
            else Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun synthesize(
        text: String,
        voiceId: String,
        format: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val key = apiKeyStore.deepSeekApiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 DeepSeek API Key"))
        if (text.isBlank()) return@withContext Result.failure(IllegalStateException("待播报文本为空"))
        if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            return@withContext Result.failure(IllegalStateException("TTS 请求体超过 64 KiB 限制"))
        }
        if (billedCharCount(text) > 10_000) {
            return@withContext Result.failure(IllegalStateException("TTS 文本超过 10,000 计费字符限制"))
        }
        val safeFormat = if (format.lowercase() == "pcm") "pcm" else "wav"
        val body = JSONObject().apply {
            put("text", text)
            put("voice_id", voiceId.ifBlank { "stella" })
            put("format", safeFormat)
            put("filter_markdown", true)
        }
        val conn = (URL(API_BASE + "/v1/tools/tts").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext Result.failure(IllegalStateException("DeepSeek TTS HTTP $code ${err.take(300)}"))
            }
            Result.success(conn.inputStream.use { it.readBytes() })
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun billedCharCount(text: String): Int = text.fold(0) { acc, ch ->
        acc + if (ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF) 2 else 1
    }
    private fun writeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeFile(
        out: java.io.OutputStream,
        boundary: String,
        name: String,
        filename: String,
        mime: String,
        bytes: ByteArray,
    ) {
        out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val API_BASE = "https://api.deepseek.com"
    }
}
