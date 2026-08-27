package com.voiceconfig.app.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class HomeAssistantDevice(
    val entityId: String,
    val domain: String,
    val friendlyName: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
)

data class HomeAssistantResponse(
    val ok: Boolean,
    val devices: List<HomeAssistantDevice> = emptyList(),
    val message: String = "",
)

@Singleton
class HomeAssistantClient @Inject constructor() {

    suspend fun fetchStates(config: HomeAssistantConfig): HomeAssistantResponse =
        withContext(Dispatchers.IO) {
            val conn = open(config, "/api/states", "GET")
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext HomeAssistantResponse(
                        ok = false,
                        message = "Home Assistant HTTP $code ${readError(conn).take(200)}",
                    )
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                HomeAssistantResponse(
                    ok = true,
                    devices = parseStates(body),
                    message = "读取到 ${parseStates(body).size} 个设备",
                )
            } catch (e: Exception) {
                HomeAssistantResponse(ok = false, message = e.message ?: e.javaClass.simpleName)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun callService(
        config: HomeAssistantConfig,
        domain: String,
        service: String,
        entityId: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ): HomeAssistantResponse = withContext(Dispatchers.IO) {
        val safeDomain = domain.trim().lowercase()
        val safeService = service.trim().lowercase()
        if (safeDomain.isBlank() || safeService.isBlank()) {
            return@withContext HomeAssistantResponse(false, message = "缺少 domain/service")
        }
        val body = JSONObject().apply {
            if (!entityId.isNullOrBlank()) {
                put("entity_id", entityId)
            }
            data.forEach { (key, value) -> put(key, value) }
        }
        val conn = open(config, "/api/services/$safeDomain/$safeService", "POST", body.toString())
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext HomeAssistantResponse(
                    ok = false,
                    message = "Home Assistant 调用失败 HTTP $code ${readError(conn).take(200)}",
                )
            }
            val resp = runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            HomeAssistantResponse(
                ok = true,
                message = "已调用 $safeDomain.$safeService${entityId?.let { " ($it)" } ?: ""}",
            )
        } catch (e: Exception) {
            HomeAssistantResponse(ok = false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        config: HomeAssistantConfig,
        path: String,
        method: String,
        body: String? = null,
    ): HttpURLConnection {
        val url = URL(config.baseUrl.trim().trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer ${config.token}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    private fun readError(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
            .getOrNull().orEmpty()

    companion object {
        fun parseStates(json: String): List<HomeAssistantDevice> {
            val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
            val result = mutableListOf<HomeAssistantDevice>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entityId = obj.optString("entity_id")
                if (entityId.isBlank()) continue
                val attrs = obj.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name")?.takeIf { it.isNotBlank() }
                    ?: entityId.substringAfterLast('.')
                val attributes = buildMap<String, Any?> {
                    attrs?.keys()?.forEach { key ->
                        put(key, attrs.opt(key))
                    }
                }
                result += HomeAssistantDevice(
                    entityId = entityId,
                    domain = entityId.substringBefore('.'),
                    friendlyName = friendly,
                    state = obj.optString("state"),
                    attributes = attributes,
                )
            }
            return result
        }
    }
}
